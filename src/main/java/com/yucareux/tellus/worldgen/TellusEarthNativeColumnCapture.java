package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.decision.BiomeDecision;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.TellusEarthColumnSnapshot;
import com.yucareux.tellus.worldgen.decision.TellusEarthNativeColumnSnapshotReader;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.WaterDecision;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.write.TellusNativeSemanticMaterials;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Bounded opt-in observation of one exact native chunk object and one resolved column. */
public final class TellusEarthNativeColumnCapture {
    private static final long MAX_COLUMN_CELLS = 1_048_576L;
    private TellusEarthNativeColumnCapture() { }

    /** Excludes subsequent snow, shell skin, roads and structures. */
    public enum Stage { POST_SURFACE_PRE_SNOW_AND_SHELL_SKIN }

    /** Raw cells ascend from minY through maxYInclusive, with no sky suffix. */
    public record Snapshot(TellusGenerationContext generation, TellusCoordinateContext coordinate,
                           int terrainY, WaterDecision water, BiomeDecision biome,
                           List<BlockState> before, List<BlockState> after,
                           Optional<TellusEarthColumnSnapshot> semanticBefore,
                           Optional<TellusEarthColumnSnapshot> semanticAfter,
                           Optional<EarthChunkGenerator.ResolvedSurfaceContext> surfaceContext) {
        public Snapshot {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(water, "water");
            Objects.requireNonNull(biome, "biome");
            before = List.copyOf(before);
            after = List.copyOf(after);
            Objects.requireNonNull(semanticBefore, "semanticBefore");
            Objects.requireNonNull(semanticAfter, "semanticAfter");
            Objects.requireNonNull(surfaceContext, "surfaceContext");
            long expected = (long) upperY(generation, terrainY, water) - generation.minY() + 1L;
            if (before.size() != expected || after.size() != expected) {
                throw new IllegalArgumentException("native capture cell range differs from payload");
            }
        }
        public int minY() { return generation.minY(); }
        public int maxYInclusive() { return Math.max(terrainY, water.surfaceY().orElse(terrainY)); }
        public Stage stage() { return Stage.POST_SURFACE_PRE_SNOW_AND_SHELL_SKIN; }
    }

    /** Native generator profile validation belongs to the caller. */
    public static Lease open(AtomicReference<Lease> slot, ChunkAccess target,
                             TellusGenerationContext generation, TellusCoordinateContext coordinate) {
        Lease lease = new Lease(slot, target, generation, coordinate);
        if (!slot.compareAndSet(null, lease)) throw new IllegalStateException("native column capture already active");
        return lease;
    }

    public static final class Lease implements AutoCloseable {
        private final AtomicReference<Lease> slot;
        private ChunkAccess target;
        private final TellusGenerationContext generation;
        private final TellusCoordinateContext coordinate;
        private final int worldX;
        private final int worldZ;
        private final CompletableFuture<Snapshot> result = new CompletableFuture<>();
        private final CompletionStage<Snapshot> view = result.minimalCompletionStage();
        private Builder claimed;
        private boolean terminal;

        private Lease(AtomicReference<Lease> slot, ChunkAccess target,
                      TellusGenerationContext generation, TellusCoordinateContext coordinate) {
            this.slot = Objects.requireNonNull(slot, "slot");
            this.target = Objects.requireNonNull(target, "target");
            this.generation = Objects.requireNonNull(generation, "generation");
            this.coordinate = Objects.requireNonNull(coordinate, "coordinate");
            if (!(coordinate.owner() instanceof EarthOwnerView owner)) {
                throw new IllegalArgumentException("native capture requires Earth ownership");
            }
            worldX = Math.toIntExact(owner.point().floorBlockX());
            worldZ = Math.toIntExact(owner.point().floorBlockZ());
            if ((worldX >> 4) != MinecraftVersionCompat.chunkX(target.getPos())
                || (worldZ >> 4) != MinecraftVersionCompat.chunkZ(target.getPos())) {
                throw new IllegalArgumentException("native capture coordinate is outside target chunk");
            }
            if ((long) generation.maxYExclusive() - generation.minY() > MAX_COLUMN_CELLS) {
                throw new IllegalArgumentException("native capture exceeds cell budget");
            }
        }
        public TellusGenerationContext generation() { return generation; }
        public CompletionStage<Snapshot> result() { return view; }

        /** Same-position but distinct ChunkAccess objects never consume the one-shot claim. */
        synchronized Builder claim(ChunkAccess chunk) {
            if (terminal || claimed != null || chunk != target || slot.get() != this) return null;
            claimed = new Builder(this);
            return claimed;
        }

        /** Call after native writer release. Dependent observer exceptions stay on their stages. */
        void complete(Builder builder) {
            Snapshot snapshot = null;
            Throwable failure = null;
            synchronized (this) {
                if (terminal) return;
                try {
                    if (builder == null || builder != claimed) throw new IllegalStateException("native capture has no matching claim");
                    snapshot = builder.snapshot();
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                release();
            }
            // Delivery linearizes at release(), not in observer code. A subsequent close may
            // return while delivery finishes; no user callback runs under the lease monitor.
            if (failure == null) result.complete(snapshot);
            else result.completeExceptionally(failure);
        }

        /** Reporting native failure never introduces claim/cleanup exceptions. */
        public void failed(Throwable failure) {
            synchronized (this) {
                if (terminal) return;
                release();
            }
            result.completeExceptionally(failure == null
                ? new IllegalStateException("native capture failed without a cause") : failure);
        }
        @Override public void close() {
            synchronized (this) {
                if (terminal) return;
                release();
            }
            result.completeExceptionally(new CancellationException("native column capture closed"));
        }

        /** Caller holds this monitor; slot CAS preserves any independently installed successor. */
        private void release() {
            terminal = true;
            target = null;
            claimed = null;
            slot.compareAndSet(this, null);
        }
    }

    /** Explicit native-overload argument, never a global collector or thread-local. */
    static final class Builder {
        private final Lease lease;
        private final ChunkAccess target;
        private RuntimeException failure;
        private List<BlockState> before;
        private List<BlockState> after;
        private Optional<TellusEarthColumnSnapshot> semanticBefore;
        private Optional<TellusEarthColumnSnapshot> semanticAfter;
        private EarthChunkGenerator.ResolvedSurfaceContext surfaceContext;
        private int terrainY;
        private WaterDecision water;
        private BiomeDecision biome;
        private Builder(Lease lease) { this.lease = lease; this.target = lease.target; }
        boolean matches(int worldX, int worldZ) { return worldX == lease.worldX && worldZ == lease.worldZ; }

        void before(ChunkAccess chunk, EarthChunkGenerator.ChunkSectionWriter writer,
                    int x, int z, int terrainY, WaterDecision water, BiomeDecision biome) {
            if (!matches(x, z) || failure != null) return;
            try {
                requireTarget(chunk);
                if (before != null) throw new IllegalStateException("duplicate native before capture");
                this.terrainY = terrainY;
                this.water = Objects.requireNonNull(water, "water");
                this.biome = Objects.requireNonNull(biome, "biome");
                before = read(chunk, writer, x, z, lease.generation.minY(), upperY(lease.generation, terrainY, water));
                semanticBefore = semantic(before);
            } catch (RuntimeException exception) { failure = exception; }
        }
        void surfaceContext(EarthChunkGenerator.ResolvedSurfaceContext context) {
            if (failure != null) return;
            try {
                if (surfaceContext != null) throw new IllegalStateException("duplicate native surface context");
                surfaceContext = Objects.requireNonNull(context, "surfaceContext");
            } catch (RuntimeException exception) { failure = exception; }
        }
        void after(ChunkAccess chunk, EarthChunkGenerator.ChunkSectionWriter writer, int x, int z, int terrainY) {
            if (!matches(x, z) || failure != null) return;
            try {
                requireTarget(chunk);
                if (before == null || after != null || this.terrainY != terrainY) {
                    throw new IllegalStateException("native after capture has no matching before");
                }
                after = read(chunk, writer, x, z, lease.generation.minY(), upperY(lease.generation, terrainY, water));
                semanticAfter = semantic(after);
            } catch (RuntimeException exception) { failure = exception; }
        }
        private void requireTarget(ChunkAccess chunk) {
            if (chunk != target) throw new IllegalArgumentException("native capture chunk identity changed");
        }
        private Optional<TellusEarthColumnSnapshot> semantic(List<BlockState> states) {
            return TellusEarthNativeColumnSnapshotReader.read(lease.generation, lease.coordinate,
                terrainY, water, biome, y -> TellusNativeSemanticMaterials
                    .material(states.get(y - lease.generation.minY())).orElse(SemanticMaterial.UNKNOWN));
        }
        private Snapshot snapshot() {
            if (failure != null) throw failure;
            if (before == null || after == null) throw new IllegalStateException("native capture is incomplete");
            return new Snapshot(lease.generation, lease.coordinate, terrainY, water, biome,
                before, after, semanticBefore, semanticAfter, Optional.ofNullable(surfaceContext));
        }
    }

    private static int upperY(TellusGenerationContext generation, int terrainY, WaterDecision water) {
        if (terrainY < generation.minY() || terrainY >= generation.maxYExclusive()) {
            throw new IllegalArgumentException("native terrain outside capture range");
        }
        if (water.surfaceY().isPresent() && (water.surfaceY().getAsInt() < generation.minY()
            || water.surfaceY().getAsInt() >= generation.maxYExclusive())) {
            throw new IllegalArgumentException("native water outside capture range");
        }
        return Math.max(terrainY, water.surfaceY().orElse(terrainY));
    }
    private static List<BlockState> read(ChunkAccess chunk, EarthChunkGenerator.ChunkSectionWriter writer,
                                         int worldX, int worldZ, int minY, int maxY) {
        if (minY < MinecraftVersionCompat.minY(chunk)
            || (long) maxY >= (long) MinecraftVersionCompat.minY(chunk) + chunk.getHeight()) {
            throw new IllegalArgumentException("native capture range is outside chunk");
        }
        int localX = worldX & 15;
        int localZ = worldZ & 15;
        if (writer != null) return List.copyOf(writer.copyColumnSnapshot(localX, localZ, minY, maxY));
        List<BlockState> states = new ArrayList<>(maxY - minY + 1);
        LevelChunkSection[] sections = chunk.getSections();
        long y = minY;
        while (y <= maxY) {
            LevelChunkSection section = Objects.requireNonNull(sections[chunk.getSectionIndex((int) y)], "section");
            int endY = (int) Math.min((long) maxY, y | 15L);
            section.acquire();
            try {
                // Read palettes directly, not hasOnlyAir() or chunk.getBlockState().
                var palette = section.getStates();
                for (; y <= endY; y++) states.add(palette.get(localX, (int) y & 15, localZ));
            } finally { section.release(); }
        }
        return List.copyOf(states);
    }
}
