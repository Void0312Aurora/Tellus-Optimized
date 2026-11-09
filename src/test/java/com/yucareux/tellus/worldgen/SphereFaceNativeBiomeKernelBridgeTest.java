package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapCodec;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real fixed source, native biome/terrain fills and explicit semantic bridge; not live GIS or save acceptance. */
class SphereFaceNativeBiomeKernelBridgeTest {
   static final double SCALE = 1000.0;
   static final int MIN = -64, HEIGHT = 384, SEA = 64;
   static final int[][] LOCALS = {{1,2}, {15,14}, {3,7}, {12,0}};
   @TempDir static Path directory;
   static String oldGame, oldConfig;
   static ClassLoader oldLoader;
   static HolderLookup.Provider lookup;
   static HolderLookup.RegistryLookup<Biome> biomes;
   static Registry<Biome> registry;
   static Constructor<?> proto;
   @BeforeAll static void bootstrap() throws Exception {
      oldGame = System.getProperty("tellus.gameDir"); oldConfig = System.getProperty("tellus.configDir");
      oldLoader = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(SphereFaceNativeBiomeKernelBridgeTest.class.getClassLoader());
      System.setProperty("tellus.gameDir", directory.toString()); System.setProperty("tellus.configDir", directory.resolve("config").toString());
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      lookup = MinecraftTestRegistries.create(); biomes = lookup.lookupOrThrow(Registries.BIOME);
      var mapped = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
      for (var key : List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.FROZEN_OCEAN)) Registry.register(mapped, key, biomes.getOrThrow(key).value());
      registry = mapped.freeze();
      proto = ProtoChunk.class.getDeclaredConstructor(ChunkPos.class, UpgradeData.class, LevelHeightAccessor.class,
         Registry.class, Class.forName("net.minecraft.world.level.levelgen.blending.BlendingData")); proto.setAccessible(true);
   }
   @AfterAll static void restore() {
      if (oldGame == null) System.clearProperty("tellus.gameDir"); else System.setProperty("tellus.gameDir", oldGame);
      if (oldConfig == null) System.clearProperty("tellus.configDir"); else System.setProperty("tellus.configDir", oldConfig);
      Thread.currentThread().setContextClassLoader(oldLoader);
   }
   static SphereFaceChunkGenerator generator(int face, Holder<Biome> biome, AtomicInteger reads, SphereChunkSampler.SurfaceData data) {
      return new SphereFaceChunkGenerator(new FixedBiomeSource(biome), face, SCALE, SEA, MIN, HEIGHT,
         (point, resolution) -> { reads.incrementAndGet(); return data; });
   }
   static TellusGenerationContext generation(SphereFaceChunkGenerator gen, long seed) {
      return new TellusGenerationContext(seed, gen.worldScale(), gen.getSeaLevel(), gen.getMinY(), gen.getMinY() + gen.getGenDepth(),
         GeometryIdentity.forProvider("tellus:sphere-face", "fixed-biome-r50", gen.model().mappingId(), "spherical-noise-v1", gen.worldScale()));
   }
   static TellusCoordinateContext coordinate(SphereFaceChunkGenerator gen, int x, int z) {
      return TellusCoordinateContextFactory.fromSphericalBlock(gen.model(), gen.topology().blockAddress(gen.face(), x, z));
   }
   static TellusSample sample(SphereFaceChunkGenerator gen, TellusGenerationContext g, TellusCoordinateContext c,
                             SphereChunkSampler.SurfaceData data, boolean nonBlocking) {
      var sampler = new SphereChunkSampler(gen.model(), (point, resolution) -> data);
      var provider = c.owner() instanceof SphericalOwnerView ? TellusSphericalSampleProvider.forSphereFaceProduction(sampler)
         : TellusSphericalSampleProvider.forCubeNetProduction(sampler);
      return provider.sample(TellusSampleRequest.fromContext(c, SamplingPurpose.TERRAIN_COLUMN,
         nonBlocking ? SamplingResolution.nonBlocking(g.worldScale()) : SamplingResolution.exact(g.worldScale()), g.geometry(), g.worldSeed()));
   }
   static ProtoChunk chunk(ChunkPos position) throws Exception {
      return (ProtoChunk)proto.newInstance(position, UpgradeData.EMPTY, LevelHeightAccessor.create(MIN, HEIGHT), registry, null);
   }
   static void biomeStatus(ProtoChunk chunk) throws Exception {
      var setters = Arrays.stream(ProtoChunk.class.getMethods()).filter(method ->
         (method.getName().equals("setStatus") || method.getName().equals("setPersistedStatus"))
         && method.getParameterCount() == 1).toList();
      assertEquals(1, setters.size(), "Expected the version's native chunk status setter");
      var setter = setters.get(0);
      setter.invoke(chunk, setter.getParameterTypes()[0].getField("BIOMES").get(null));
   }
   static String id(Holder<Biome> biome) { return biome.unwrapKey().orElseThrow().location().toString(); }

   @Test void realFixedBiomeAndNativeChunkContainersMatchKernelAcrossFacesBiomesAndSeeds() throws Exception {
      int columns = 0, cells = 0, blockStates = 0, quartCells = 0, nativeSamples = 0;
      List<ResourceKey<Biome>> keys = List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.FROZEN_OCEAN);
      var inputs = List.of(new SphereChunkSampler.SurfaceData(10.1, 30),
         new SphereChunkSampler.SurfaceData(-1010.1, 60, 20, SphereChunkSampler.SurfaceData.WaterKind.INLAND, null),
         new SphereChunkSampler.SurfaceData(-5010.1, 70, 0));
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         for (int face = 0; face < 6; face++) for (int i = 0; i < keys.size(); i++) for (long seed : new long[]{0,42}) {
            AtomicInteger reads = new AtomicInteger(); var data = inputs.get(i); var key = keys.get(i);
            var gen = generator(face, biomes.getOrThrow(key), reads, data); gen.setWorldSeed(seed);
            var kernel = gen.semanticColumnKernel(); var g = generation(gen, seed);
            var pos = face % 2 == 0 ? new ChunkPos(-1,2) : new ChunkPos(2,-3); var target = chunk(pos);
            if (!key.equals(Biomes.PLAINS)) assertNotEquals(key.location().toString(),
               id(target.getSections()[0].getBiomes().get(0,0,0)), "Native biome fill must change the initial palette");
            assertSame(target, gen.createBiomes(Runnable::run, null, null, null, target).join());
            assertEquals(0, reads.get(), "Native fixed-biome filling must not query terrain");
            biomeStatus(target);
            for (int y = MIN; y < MIN + HEIGHT; y += 4) for (int z = pos.getMinBlockZ(); z < pos.getMinBlockZ() + 16; z += 4)
               for (int x = pos.getMinBlockX(); x < pos.getMinBlockX() + 16; x += 4) {
                  assertEquals(key.location().toString(), id(target.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z))));
                  quartCells++;
               }
            assertSame(target, gen.fillFromNoise(Runnable::run, null, null, null, target).join());
            assertEquals(256, reads.get()); nativeSamples += reads.get();
            for (int[] local : LOCALS) {
               int x = pos.getMinBlockX() + local[0], z = pos.getMinBlockZ() + local[1];
               var c = coordinate(gen, x, z); var s = sample(gen, g, c, data, false);
               var old = new TellusSphericalDecisionKernel(TellusSphericalDecisionKernel.Settings.DEFAULT).decide(g, c, s);
               var result = TellusColumnDecisionEngine.withSphericalProductionRules(kernel).decide(g, c, s);
               assertEquals(DecisionStatus.AVAILABLE, result.status()); assertEquals(key.location().toString(), result.biome().id());
               assertEquals(256, reads.get(), "Explicit fixed-biome bridge must not resample native terrain");
               assertFalse(old.biome().known()); assertEquals(old.terrainY(), result.terrainY()); assertEquals(old.surface(), result.surface());
               assertEquals(old.water(), result.water()); assertEquals(old.strata(), result.strata());
               var context = WriteContext.canonical(c, g, "native-fixed-biome"); var materializer = new TellusColumnMaterializer();
               var before = materializer.materializeCells(context, old); var after = materializer.materializeCells(context, result);
               assertEquals(WriteStatus.WRITTEN, after.status()); assertEquals(before.mappedWrites(), after.mappedWrites());
               var actualPosition = CanonicalColumnPosition.resolve(context); assertEquals(x, actualPosition.x()); assertEquals(z, actualPosition.z());
               Map<Integer,BlockState> states = new HashMap<>();
               for (var cell : after.mappedWrites()) states.put(cell.y(), TellusNativeSemanticMaterials.state(cell.material()));
               for (int y = MIN; y < MIN + HEIGHT; y++) {
                  assertEquals(states.getOrDefault(y, Blocks.AIR.defaultBlockState()), target.getBlockState(new BlockPos(x,y,z)));
                  blockStates++;
               }
               assertEquals(result.biome().id(), id(target.getNoiseBiome(QuartPos.fromBlock(x),
                  QuartPos.fromBlock(result.terrainY().orElseThrow()), QuartPos.fromBlock(z))));
               cells += after.mappedWrites().size(); columns++;
            }
         }
      }
      assertEquals(144, columns); assertEquals(18672, cells); assertEquals(55296, blockStates); assertEquals(55296, quartCells); assertEquals(9216, nativeSamples);
      System.out.println("SPHERE_FIXED_BIOME_KERNEL columns=" + columns + " materializedCells=" + cells + " comparedBlockStates=" + blockStates
         + " biomeQuartCells=" + quartCells + " biomeMismatches=0 terrainMismatches=0");
   }

   @Test void mismatchedFaceScaleSeaBoundsSeedAndCubeOwnerAreRejectedWithoutTerrainReads() {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         AtomicInteger reads = new AtomicInteger(); var data = new SphereChunkSampler.SurfaceData(10.1,30);
         var gen = generator(0,biomes.getOrThrow(Biomes.PLAINS),reads,data); var g = generation(gen,0); var c = coordinate(gen,1,2);
         var kernel = gen.semanticColumnKernel();
         var wrong = List.of(new TellusGenerationContext(1,SCALE,SEA,MIN,MIN+HEIGHT,g.geometry()),
            new TellusGenerationContext(0,SCALE*2,SEA,MIN,MIN+HEIGHT), new TellusGenerationContext(0,SCALE,SEA+1,MIN,MIN+HEIGHT),
            new TellusGenerationContext(0,SCALE,SEA,MIN-16,MIN+HEIGHT), new TellusGenerationContext(0,SCALE,SEA,MIN,MIN+HEIGHT+16));
         for (var bad : wrong) assertThrows(IllegalArgumentException.class, () -> kernel.decide(bad,c,sample(gen,bad,c,data,false)));
         var faceOther = TellusCoordinateContextFactory.fromSphericalBlock(gen.model(),
            new SphereWorldModel.SphereBlockAddress(1,gen.model().config().faceBlocks()/2,gen.model().config().faceBlocks()/2));
         assertThrows(IllegalArgumentException.class, () -> kernel.decide(g,faceOther,sample(gen,g,faceOther,data,false)));
         var layout = CubeNetLayout.standard(gen.model().config().faceBlocks());
         var cube = TellusCoordinateContextFactory.fromCubeNetCanonical(gen.model(),layout,
            new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X,layout.faceSize()/2.0+1.5,layout.faceSize()/2.0+2.5));
         assertThrows(IllegalArgumentException.class, () -> kernel.decide(g,cube,sample(gen,g,cube,data,false)));
         var owner = (SphericalOwnerView)c.owner();
         var invalidGeo = ImmutableTellusCoordinateContext.withoutCubeNetView(faceOther.geo(),faceOther.earth(),faceOther.sphericalNoise(),owner);
         assertThrows(IllegalArgumentException.class, () -> kernel.decide(g,invalidGeo,sample(gen,g,invalidGeo,data,false)));
         assertEquals(0,reads.get());
      }
   }

   @Test void existingKernelTracksSeedChangesAndDoesNotFreezeAnObsoleteBinding() {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         var data = new SphereChunkSampler.SurfaceData(10.1,30); AtomicInteger reads = new AtomicInteger();
         var gen = generator(0,biomes.getOrThrow(Biomes.DESERT),reads,data); var c = coordinate(gen,1,2); var zero = generation(gen,0);
         var kernel = gen.semanticColumnKernel(); assertTrue(kernel.decide(zero,c,sample(gen,zero,c,data,false)).biome().known());
         gen.setWorldSeed(99); var fresh = generation(gen,99);
         assertThrows(IllegalArgumentException.class, () -> kernel.decide(zero,c,sample(gen,zero,c,data,false)));
         assertEquals("minecraft:desert",kernel.decide(fresh,c,sample(gen,fresh,c,data,false)).biome().id());
         gen.setWorldSeed(0); assertTrue(kernel.decide(zero,c,sample(gen,zero,c,data,false)).biome().known()); assertEquals(0,reads.get());
      }
   }

   @Test void singularityCapsFailClosedAndQueuedOrUnknownRequestsDoNotReadTerrain() {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         var data = new SphereChunkSampler.SurfaceData(10.1,30); AtomicInteger reads = new AtomicInteger();
         var gen = generator(0,biomes.getOrThrow(Biomes.PLAINS),reads,data); var g = generation(gen,0); var kernel = gen.semanticColumnKernel();
         var center = coordinate(gen,1,2); var queued = sample(gen,g,center,data,true);
         assertEquals(DecisionStatus.QUEUED,kernel.decide(g,center,queued).status());
         assertEquals(DecisionStatus.UNKNOWN,kernel.decide(g,center,TellusSample.unknown(queued.request(),"fixture unavailable")).status());
         var corner = TellusCoordinateContextFactory.fromSphericalBlock(gen.model(),new SphereWorldModel.SphereBlockAddress(0,0,0));
         assertEquals(SphereSingularityPolicy.Status.EXCLUDED,gen.singularityPolicy().status(((SphericalOwnerView)corner.owner()).chunk()));
         assertThrows(IllegalStateException.class, () -> kernel.decide(g,corner,sample(gen,g,corner,data,false)));
         assertEquals(0,reads.get());
      }
   }

   @Test void nativeUnkeyedFixedBiomeDoesNotInventAnIdentity() {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         var data = new SphereChunkSampler.SurfaceData(10.1,30); AtomicInteger reads = new AtomicInteger();
         var gen = generator(0,Holder.direct(biomes.getOrThrow(Biomes.PLAINS).value()),reads,data); var g = generation(gen,0); var c = coordinate(gen,1,2);
         var decision = gen.semanticColumnKernel().decide(g,c,sample(gen,g,c,data,false));
         assertEquals(DecisionStatus.AVAILABLE,decision.status()); assertEquals(BiomeDecision.unknown(),decision.biome()); assertEquals(0,reads.get());
      }
   }

   @SuppressWarnings("unchecked") static RegistryOps<com.google.gson.JsonElement> ops() throws Exception {
      try {
         var method = HolderLookup.Provider.class.getMethod("createSerializationContext",com.mojang.serialization.DynamicOps.class);
         return (RegistryOps<com.google.gson.JsonElement>)method.invoke(lookup,JsonOps.INSTANCE);
      } catch (NoSuchMethodException legacy) { return RegistryOps.create(JsonOps.INSTANCE,lookup); }
   }
   @SuppressWarnings("unchecked") static Codec<SphereFaceChunkGenerator> codec() throws Exception {
      Object codec = SphereFaceChunkGenerator.class.getField("CODEC").get(null);
      return codec instanceof MapCodec<?> map ? (Codec<SphereFaceChunkGenerator>)map.codec() : (Codec<SphereFaceChunkGenerator>)codec;
   }
   @Test void fullGeneratorCodecIsUnchangedByExplicitFactoryAndRetainsFixedSource() throws Exception {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         var data = new SphereChunkSampler.SurfaceData(10.1,30); AtomicInteger reads = new AtomicInteger();
         var gen = generator(4,biomes.getOrThrow(Biomes.FROZEN_OCEAN),reads,data); gen.setWorldSeed(99);
         var codec = codec(); var ops = ops(); var encoded = codec.encodeStart(ops,gen).result().orElseThrow();
         assertNotNull(gen.semanticColumnKernel()); assertEquals(encoded,codec.encodeStart(ops,gen).result().orElseThrow());
         var restored = codec.parse(ops,encoded).result().orElseThrow();
         assertEquals(encoded,codec.encodeStart(ops,restored).result().orElseThrow());
         assertInstanceOf(FixedBiomeSource.class,restored.getBiomeSource()); assertEquals(gen.face(),restored.face());
         assertEquals(gen.worldScale(),restored.worldScale()); assertEquals(gen.getMinY(),restored.getMinY());
         assertEquals(gen.getSeaLevel(),restored.getSeaLevel()); assertEquals(gen.getGenDepth(),restored.getGenDepth());
         var g = generation(restored,0); var c = coordinate(restored,1,2);
         assertEquals("minecraft:frozen_ocean",restored.semanticColumnKernel().decide(g,c,sample(restored,g,c,data,false)).biome().id());
         assertEquals(0,reads.get()); // Seed is still runtime-initialized, not newly added to the Codec.
      }
   }
}
