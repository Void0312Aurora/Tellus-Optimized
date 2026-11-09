package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.TellusEarthSnowDecisionKernel;
import com.yucareux.tellus.worldgen.write.TellusNativeSemanticMaterials;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Frozen pre-extraction Earth snow loops versus both actual private native backends.
 *
 * <p>Source snapshot: EarthChunkGenerator applySnowCover/snowCoverState/mountainMaterialState,
 * lines12344..12409 read before extraction; cursor writes top down and removes each block
 * entity after setting, sections fill bottom up with int bottom arithmetic. The old
 * SurfaceMaterialDepthPolicy snow-depth/powder noise is copied below, including salts,
 * wrapped coordinate offsets and floating-point operation order. Glacier thresholds/salts
 * and block mapping are literal; only unchanged SurfacePatchNoise.percent remains its
 * existing clustered-noise source boundary. Expectations call neither the new kernel nor
 * snowColumn/glacierSurfaceMaterial. These are resolved/native controlled JUnit fixtures,
 * not live GIS, realtime snow-layer, lifecycle or persistent-world acceptance.</p>
 */
class EarthSnowKernelNativeParityTest {
   private static final int MIN = -64;
   private static final int MAX = 64;
   private static BlockState SENTINEL;
   private static final int[][] POINTS = {{0, 0}, {123, -87}, {-200, 219}, {37, -91},
      {-4097, 8193}, {1200000, -2300000}};
   private static final long[] SEEDS = {0, 42, 987654321L};
   private static final int[] SURFACES = {MIN - 1, MIN, MIN + 1, -49, -48, -1, MAX - 1, MAX, MAX + 1};

   @TempDir static Path gameDirectory;
   private static String oldGameDirectory;
   private static String oldConfigDirectory;
   private static ClassLoader oldLoader;
   private static Registry<Biome> biomes;
   private static Holder<Biome> plains;
   private static Method cursorSnow;
   private static Method sectionSnow;
   private static Field seedField;

   @BeforeAll static void bootstrapNativeBoundary() throws Exception {
      oldGameDirectory = System.getProperty("tellus.gameDir");
      oldConfigDirectory = System.getProperty("tellus.configDir");
      oldLoader = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(EarthSnowKernelNativeParityTest.class.getClassLoader());
      System.setProperty("tellus.gameDir", gameDirectory.toString());
      System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      SENTINEL = Blocks.DRIPSTONE_BLOCK.defaultBlockState();
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Class.forName(EarthChunkGenerator.class.getName(), true, EarthSnowKernelNativeParityTest.class.getClassLoader());
      }
      plains = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
      biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
      Registry.register(biomes, Biomes.PLAINS, plains.value());
      cursorSnow = EarthChunkGenerator.class.getDeclaredMethod("applySnowCover", ChunkAccess.class,
         BlockPos.MutableBlockPos.class, int.class, int.class, int.class, int.class, boolean.class, boolean.class);
      sectionSnow = EarthChunkGenerator.class.getDeclaredMethod("applySnowCover", EarthChunkGenerator.ChunkSectionWriter.class,
         int.class, int.class, int.class, int.class, int.class, int.class, boolean.class, boolean.class);
      cursorSnow.setAccessible(true);
      sectionSnow.setAccessible(true);
      seedField = EarthChunkGenerator.class.getDeclaredField("worldSeed");
      seedField.setAccessible(true);
   }

   @AfterAll static void restoreFixture() {
      restore("tellus.gameDir", oldGameDirectory);
      restore("tellus.configDir", oldConfigDirectory);
      Thread.currentThread().setContextClassLoader(oldLoader);
   }

   @Test void frozenOldLoopsMatchBothNativeBackendsAllCellsOrderMaterialsBoundsAndEntityRemoval() throws Exception {
      int cases = 0;
      long compared = 0;
      Set<SemanticMaterial> observed = EnumSet.noneOf(SemanticMaterial.class);
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         EarthChunkGenerator generator = generator();
         for (long seed : SEEDS) {
            seedField.setLong(generator, seed); // Resolved fixture, not a world-seed lifecycle claim.
            for (int[] point : POINTS) {
               int x = point[0], z = point[1];
               TraceChunk cursorTarget = chunk(x, z);
               TraceChunk sectionTarget = chunk(x, z);
               for (boolean full : new boolean[]{false, true}) {
                  for (boolean glacier : new boolean[]{false, true}) {
                     FrozenColumn column = frozenColumn(seed, x, z, full, glacier);
                     for (int min : new int[]{MIN, MIN + 2}) {
                        var kernel = new TellusEarthSnowDecisionKernel(seed, min, MAX);
                        for (int surface : SURFACES) {
                           String context = "seed=" + seed + " x=" + x + " z=" + z + " min=" + min
                              + " surface=" + surface + " full=" + full + " glacier=" + glacier;
                           reset(cursorTarget, x, z);
                           reset(sectionTarget, x, z);
                           List<Event> expectedCursor = frozenCursor(column, x, z, surface, min);
                           List<Event> expectedSections = frozenSections(column, x, z, surface, min);
                           var input = new TellusEarthSnowDecisionKernel.Input(x, z, surface, full, glacier);
                           assertEquals(expectedCells(expectedCursor), kernel.resolveCursor(input), context);
                           assertEquals(expectedCells(expectedSections), kernel.resolveSections(input), context);
                           for (var cell : kernel.resolveCursor(input)) observed.add(cell.material());
                           BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x + 1, MIN, z + 1);
                           BlockPos before = cursor.immutable();
                           cursorSnow.invoke(generator, cursorTarget, cursor, x, z, surface, min, full, glacier);
                           var writer = new EarthChunkGenerator.ChunkSectionWriter(sectionTarget.getSections(), MIN);
                           try {
                              sectionSnow.invoke(generator, writer, x & 15, z & 15, x, z, surface, min, full, glacier);
                              writer.finish();
                           } finally { writer.close(); }
                           assertEquals(expectedCursor, cursorTarget.cursorEvents, "complete cursor trace: " + context);
                           assertEquals(expectedSections, sectionTarget.sectionEvents, "complete section trace: " + context);
                           if (expectedCursor.isEmpty()) assertEquals(before, cursor);
                           else assertEquals(expectedCursor.get(expectedCursor.size() - 1).position(), cursor);
                           compared += compareColumn(cursorTarget, expectedCursor, x, z, context);
                           compared += compareColumn(sectionTarget, expectedSections, x, z, context);
                           cases++;
                        }
                     }
                  }
               }
            }
         }
      }
      assertEquals(1296, cases);
      assertEquals(331776L, compared);
      assertEquals(EnumSet.of(SemanticMaterial.SNOW, SemanticMaterial.POWDER_SNOW,
         SemanticMaterial.PACKED_ICE, SemanticMaterial.BLUE_ICE), observed);
      System.out.println("EARTH_SNOW_NATIVE_PARITY cases=" + cases + " comparedBlockStates=" + compared
         + " mismatchBlocks=0 exactCursorAndSectionOrder=true exactEntityRemoval=true materials=" + observed);
   }

   @Test void wholeNativeChunksRetainEveryUntouchedCellAndNeverWriteSurfacePlusOne() throws Exception {
      int x = -33, z = 47;
      TraceChunk cursorTarget = chunk(x, z), sectionTarget = chunk(x, z);
      Map<BlockPos, BlockState> expected = new HashMap<>();
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         EarthChunkGenerator generator = generator();
         seedField.setLong(generator, 42);
         for (int local = 0; local < 16; local++) {
            int worldX = (x & ~15) + local;
            int surface = -48 + local;
            boolean glacier = local % 2 == 0;
            FrozenColumn column = frozenColumn(42, worldX, z, true, glacier);
            for (Event event : frozenCursor(column, worldX, z, surface, MIN)) {
               if (!event.removal() && event.y() >= MIN && event.y() < MAX) expected.put(event.position(), event.state());
            }
            cursorSnow.invoke(generator, cursorTarget, new BlockPos.MutableBlockPos(), worldX, z, surface, MIN, true, glacier);
            var writer = new EarthChunkGenerator.ChunkSectionWriter(sectionTarget.getSections(), MIN);
            try {
               sectionSnow.invoke(generator, writer, local, z & 15, worldX, z, surface, MIN, true, glacier);
               writer.finish();
            } finally { writer.close(); }
            assertEquals(SENTINEL, cursorTarget.getBlockState(new BlockPos(worldX, surface + 1, z)));
            assertEquals(SENTINEL, sectionTarget.getBlockState(new BlockPos(worldX, surface + 1, z)));
         }
      }
      int compared = 0;
      for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) for (int y = MIN; y < MAX; y++) {
         BlockPos position = new BlockPos((x & ~15) + dx, y, (z & ~15) + dz);
         BlockState state = expected.getOrDefault(position, SENTINEL);
         assertEquals(state, cursorTarget.getBlockState(position));
         assertEquals(state, sectionTarget.getBlockState(position));
         compared += 2;
      }
      assertEquals(65536, compared);
      System.out.println("EARTH_SNOW_WHOLE_CHUNK_PARITY comparedBlockStates=" + compared + " mismatchBlocks=0");
   }

   @Test void publicGeneratorFactoryBindsCurrentSeedBoundsAndReturnsDetachedSnapshots() throws Exception {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         EarthChunkGenerator generator = generator();
         var original = generator.semanticSnowColumnKernel();
         assertEquals(generator.worldSeed(), original.worldSeed());
         assertEquals(generator.getMinY(), original.minY());
         assertEquals((long)generator.getMinY() + generator.getGenDepth(), original.maxYExclusive());
         seedField.setLong(generator, 42);
         var current = generator.semanticSnowColumnKernel();
         assertEquals(0, original.worldSeed());
         assertEquals(42, current.worldSeed());
         int surface = Math.max(generator.getMinY() + 8, 0);
         var input = new TellusEarthSnowDecisionKernel.Input(0, 0, surface, true, false);
         assertEquals(4, original.resolve(input).size());
         assertEquals(SemanticMaterial.POWDER_SNOW, original.resolve(input).get(0).material());
         assertEquals(5, current.resolve(input).size());
         assertEquals(SemanticMaterial.SNOW, current.resolve(input).get(0).material());
         assertTrue(current.resolve(new TellusEarthSnowDecisionKernel.Input(0, 0,
            Math.toIntExact(current.maxYExclusive() + 8), true, true)).isEmpty());
         assertTrue(current.resolveSections(new TellusEarthSnowDecisionKernel.Input(0, 0,
            current.minY() - 1, true, true)).isEmpty());
      }
   }

   @Test void extremeNativeCursorIntWrapAndSectionNoWriteRemainDifferentAndBounded() throws Exception {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         EarthChunkGenerator generator = generator();
         seedField.setLong(generator, 42);
         for (int surface : new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE}) {
            TraceChunk cursorTarget = chunk(0, 0), sectionTarget = chunk(0, 0);
            FrozenColumn column = frozenColumn(42, 0, 0, true, true);
            var cursor = new BlockPos.MutableBlockPos();
            cursorSnow.invoke(generator, cursorTarget, cursor, 0, 0, surface, Integer.MIN_VALUE, true, true);
            var writer = new EarthChunkGenerator.ChunkSectionWriter(sectionTarget.getSections(), MIN);
            try {
               sectionSnow.invoke(generator, writer, 0, 0, 0, 0, surface, Integer.MIN_VALUE, true, true);
               writer.finish();
            } finally { writer.close(); }
            assertEquals(frozenCursor(column, 0, 0, surface, Integer.MIN_VALUE), cursorTarget.cursorEvents);
            assertTrue(sectionTarget.sectionEvents.isEmpty());
            compareColumn(cursorTarget, List.of(), 0, 0, "extreme cursor");
            compareColumn(sectionTarget, List.of(), 0, 0, "extreme sections");
         }
      }
   }

   private static EarthChunkGenerator generator() {
      return new EarthChunkGenerator(new FixedBiomeSource(plains), EarthGeneratorSettings.DEFAULT);
   }

   private record FrozenColumn(int depth, BlockState state) { }
   private record Event(boolean removal, int x, int y, int z, BlockState state) {
      BlockPos position() { return new BlockPos(x, y, z); }
   }

   /** Literal old cursor loop; int wrap and set-then-remove order intentionally retained. */
   private static List<Event> frozenCursor(FrozenColumn column, int x, int z, int surface, int min) {
      List<Event> events = new ArrayList<>();
      for (int depth = 0; depth < column.depth(); depth++) {
         int y = surface - depth;
         if (y < min) break;
         events.add(new Event(false, x, y, z, column.state()));
         events.add(new Event(true, x, y, z, null));
      }
      return events;
   }

   /** Literal old bottom and bounded effective fillColumnConstant order. */
   private static List<Event> frozenSections(FrozenColumn column, int x, int z, int surface, int min) {
      int bottom = Math.max(min, surface - column.depth() + 1);
      List<Event> events = new ArrayList<>();
      if (surface < bottom || bottom < MIN || bottom >= MAX) return events;
      for (long y = bottom; y <= surface && y < MAX; y++) events.add(new Event(false, x, (int)y, z, column.state()));
      return events;
   }

   private static List<TellusEarthSnowDecisionKernel.Cell> expectedCells(List<Event> events) {
      List<TellusEarthSnowDecisionKernel.Cell> cells = new ArrayList<>();
      for (Event event : events) if (!event.removal() && event.y() >= MIN && event.y() < MAX) {
         // Frozen native block table, not TellusNativeSemanticMaterials.material.
         SemanticMaterial material = event.state().is(Blocks.SNOW_BLOCK) ? SemanticMaterial.SNOW
            : event.state().is(Blocks.POWDER_SNOW) ? SemanticMaterial.POWDER_SNOW
            : event.state().is(Blocks.PACKED_ICE) ? SemanticMaterial.PACKED_ICE : SemanticMaterial.BLUE_ICE;
         cells.add(new TellusEarthSnowDecisionKernel.Cell(event.y(), material));
         assertEquals(event.state(), TellusNativeSemanticMaterials.state(material), "exact default block mapping");
      }
      return cells;
   }

   private static long compareColumn(TraceChunk target, List<Event> events, int x, int z, String context) {
      Map<Integer, BlockState> expected = new HashMap<>();
      for (Event event : events) if (!event.removal() && event.y() >= MIN && event.y() < MAX) expected.put(event.y(), event.state());
      for (int y = MIN; y < MAX; y++) {
         assertEquals(expected.getOrDefault(y, SENTINEL), target.getBlockState(new BlockPos(x, y, z)), context + " y=" + y);
      }
      return MAX - MIN;
   }

   private static void reset(TraceChunk target, int x, int z) {
      for (int y = MIN; y < MAX; y++) MinecraftVersionCompat.setBlockState(target, new BlockPos(x, y, z), SENTINEL);
      target.cursorEvents.clear();
      target.sectionEvents.clear();
   }

   private static TraceChunk chunk(int x, int z) {
      TraceChunk chunk = new TraceChunk(x, z);
      for (int index = 0; index < chunk.getSections().length; index++) {
         var section = chunk.getSections()[index];
         chunk.getSections()[index] = new LevelChunkSection(
            new TracePalette(chunk.sectionEvents, MIN + index * 16, x & ~15, z & ~15), section.getBiomes());
      }
      return chunk;
   }

   private static final class TraceChunk extends ProtoChunk {
      final List<Event> cursorEvents = new ArrayList<>();
      final List<Event> sectionEvents = new ArrayList<>();
      TraceChunk(int x, int z) {
         super(new ChunkPos(x >> 4, z >> 4), UpgradeData.EMPTY, LevelHeightAccessor.create(MIN, MAX - MIN), biomes, null);
      }
      @Override public BlockState setBlockState(BlockPos position, BlockState state, boolean moved) {
         if (this.cursorEvents != null) this.cursorEvents.add(new Event(false, position.getX(), position.getY(), position.getZ(), state));
         return super.setBlockState(position, state, moved);
      }
      @Override public void removeBlockEntity(BlockPos position) {
         if (this.cursorEvents != null) this.cursorEvents.add(new Event(true, position.getX(), position.getY(), position.getZ(), null));
         super.removeBlockEntity(position);
      }
   }

   private static final class TracePalette extends PalettedContainer<BlockState> {
      private final List<Event> trace;
      private final int minY, baseX, baseZ;
      TracePalette(List<Event> trace, int minY, int baseX, int baseZ) {
         super(Block.BLOCK_STATE_REGISTRY, SENTINEL, PalettedContainer.Strategy.SECTION_STATES);
         this.trace = trace; this.minY = minY; this.baseX = baseX; this.baseZ = baseZ;
      }
      @Override public BlockState getAndSetUnchecked(int x, int y, int z, BlockState state) {
         if (this.trace != null) this.trace.add(new Event(false, this.baseX + x, this.minY + y, this.baseZ + z, state));
         return super.getAndSetUnchecked(x, y, z, state);
      }
   }

   /** Frozen policy math, independently copied from the pre-extraction source snapshot. */
   private static FrozenColumn frozenColumn(long seed, int x, int z, boolean full, boolean glacier) {
      double depthNoise = frozenNoise(x, z, 23, seed, 5297092466199082147L) * 0.64
         + frozenNoise(x - 29, z + 41, 8, seed, 2838927217971765761L) * 0.36;
      int depth = full ? 3 + Math.min(5, (int)(depthNoise * 6)) : 1;
      boolean powder = frozenNoise(x, z, 11, seed, 5412538886491830033L) * 0.68
         + frozenNoise(x + 37, z - 53, 4, seed, 8582074519071182603L) * 0.32 < 0.34;
      BlockState state = powder ? Blocks.POWDER_SNOW.defaultBlockState() : Blocks.SNOW_BLOCK.defaultBlockState();
      if (glacier) {
         state = SurfacePatchNoise.percent(x, z, 8724352394416290811L) < 30 ? Blocks.SNOW_BLOCK.defaultBlockState()
            : SurfacePatchNoise.percent(x, z, -2389441726380115407L) < 17 ? Blocks.BLUE_ICE.defaultBlockState()
            : Blocks.PACKED_ICE.defaultBlockState();
      }
      return new FrozenColumn(depth, state);
   }

   private static double frozenNoise(int x, int z, int size, long seed, long salt) {
      int cellX = Math.floorDiv(x, size), cellZ = Math.floorDiv(z, size);
      double fx = frozenFade((double)Math.floorMod(x, size) / size);
      double fz = frozenFade((double)Math.floorMod(z, size) / size);
      double nw = frozenCell(cellX, cellZ, seed, salt), ne = frozenCell(cellX + 1, cellZ, seed, salt);
      double sw = frozenCell(cellX, cellZ + 1, seed, salt), se = frozenCell(cellX + 1, cellZ + 1, seed, salt);
      double north = nw + fx * (ne - nw), south = sw + fx * (se - sw);
      return north + fz * (south - north);
   }
   private static double frozenCell(int x, int z, long seed, long salt) {
      long value = seed ^ salt ^ (long)x * 341873128712L ^ (long)z * 132897987541L;
      value ^= value >>> 33; value *= -49064778989728563L;
      value ^= value >>> 33; value *= -4265267296055464877L;
      value ^= value >>> 33;
      return (double)(value >>> 11) * 0x1.0p-53;
   }
   private static double frozenFade(double value) {
      return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
   }
   private static void restore(String key, String value) {
      if (value == null) System.clearProperty(key); else System.setProperty(key, value);
   }
}
