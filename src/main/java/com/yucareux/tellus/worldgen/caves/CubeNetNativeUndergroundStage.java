package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.write.TellusCompositeColumnWriter.Cell;
import com.yucareux.tellus.worldgen.write.TellusNativePostProcessing;
import com.yucareux.tellus.worldgen.write.TellusTargetWriteGuard;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;

/**
 * Opt-in bridge to the unchanged native Cube cave sampler. Preparation runs on a
 * detached ProtoChunk with a frozen surface halo and seed. Replay reads no source:
 * an opaque actual-target-bound snapshot must still match under the participant
 * lease. This is block/marker/dirty recovery, not a world/save/source transaction.
 */
public final class CubeNetNativeUndergroundStage {
    public enum PreparationStatus { READY, INVALID, QUEUED }
    public record Preparation(PreparationStatus status, Optional<Prepared> prepared, List<String> diagnostics) {
        public Preparation {
            Objects.requireNonNull(status); prepared = Objects.requireNonNull(prepared);
            diagnostics = List.copyOf(diagnostics);
            if ((status == PreparationStatus.READY) != prepared.isPresent()) throw new IllegalArgumentException("prepared status differs");
        }
    }

    /** Unforgeable capture: original target and native baseline never escape. */
    public static final class Prepared {
        private final ProtoChunk target;
        private final BlockState[] baseline;
        private final UndergroundStagePlan plan;
        private Prepared(ProtoChunk target, BlockState[] baseline, UndergroundStagePlan plan) {
            this.target = target; this.baseline = baseline; this.plan = plan;
        }
        public UndergroundStagePlan plan() { return plan; }
    }

    public Preparation prepare(boolean enabled, ChunkAccess chunk, UndergroundStagePlan.Request request, Registry<Biome> biomes) {
        if (!enabled || request == null) return stopped(PreparationStatus.INVALID, "underground preparation disabled or missing request");
        try { UndergroundStagePlanner.validateRequest(request); }
        catch (RuntimeException failure) { return stopped(PreparationStatus.INVALID, failure.getClass().getName()); }
        if (request.resolution().kind() == com.yucareux.tellus.worldgen.sample.SamplingResolution.Kind.NON_BLOCKING) return stopped(PreparationStatus.QUEUED, "nonblocking preparation remains target/registry cold");
        if (!(chunk instanceof ProtoChunk target) || biomes == null) return stopped(PreparationStatus.INVALID, "preparation requires ProtoChunk and biome registry");
        var acquired = TellusTargetWriteGuard.tryAcquire(target);
        if (acquired.isEmpty()) return stopped(PreparationStatus.QUEUED, "participating native writer active");
        try (var lease = acquired.orElseThrow()) {
            lease.requireOwned(target);
            try {
                validateTarget(target, request);
                BlockState[] baseline = snapshot(target, request);
                var detached = new ProtoChunk(target.getPos(), UpgradeData.EMPTY,
                    LevelHeightAccessor.create(request.generation().minY(), request.generation().maxYExclusive() - request.generation().minY()), biomes, null);
                for (int i = 0; i < baseline.length; i++) detached.setBlockState(position(request, i), baseline[i], false);
                int[] surface = request.surfaceY(), halo = request.surfaceHaloY();
                int baseX = Math.multiplyExact(request.chunkX(), 16), baseZ = Math.multiplyExact(request.chunkZ(), 16);
                List<UndergroundStructureExclusion.Box> exclusions = request.structureExclusions().stream()
                    .map(b -> new UndergroundStructureExclusion.Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), b.carvingMargin(), b.blocksFeaturePlacement())).toList();
                List<UndergroundStagePlan.Delta> deltas = new ArrayList<>();
                var flags = request.flags();
                new CubeNetSphericalCaveSampler().apply(request.model(), request.address(),
                    new CubeNetSphericalCaveField(request.generation().worldSeed()), detached,
                    request.generation().minY(), request.generation().seaLevel(), flags.applyCaves(), flags.cavesReachSurface(),
                    flags.applyOreVeins(), flags.applyGeologicalStonePatches(), surface,
                    flags.applyGeologicalStonePatches() ? (x,z) -> {
                        int hx = Math.toIntExact((long)x - baseX + 2), hz = Math.toIntExact((long)z - baseZ + 2);
                        if (hx < 0 || hx >= 20 || hz < 0 || hz >= 20) throw new IllegalArgumentException("sampler exceeded frozen halo");
                        return halo[hz * 20 + hx];
                    } : null,
                    request.floodGuardY(), request.generationFloorY(), exclusions, (written,pos,state,fluid) -> {
                        var material = material(state);
                        deltas.add(new UndergroundStagePlan.Delta(pos.getX(),pos.getY(),pos.getZ(),material,fluid));
                        MinecraftVersionCompat.setBlockState(written,pos,state);
                    });
                var plan = UndergroundStagePlan.available(request, deltas, List.of("unchanged native sampler evaluated against detached target and frozen halo"));
                return new Preparation(PreparationStatus.READY, Optional.of(new Prepared(target,baseline,plan)), List.of());
            } catch (RuntimeException failure) {
                return stopped(PreparationStatus.INVALID, "detached native preparation failed: " + failure.getClass().getName());
            }
        }
    }

    public CubeNetUndergroundStageAdapter.ReplayResult replay(boolean enabled, ChunkAccess target, Prepared prepared) {
        if (!enabled || prepared == null || target != prepared.target) return invalid("disabled, missing or foreign prepared target");
        ProtoChunk chunk = prepared.target;
        var request = prepared.plan.request();
        var markers = new TellusNativePostProcessing(chunk);
        CubeNetUndergroundStageAdapter.PostProcessing metadata = new CubeNetUndergroundStageAdapter.PostProcessing() {
            public Object snapshot() {
                ShortList[] refs = chunk.getPostProcessing().clone();
                short[][] values = new short[refs.length][];
                for (int i=0;i<refs.length;i++) if(refs[i]!=null) values[i]=refs[i].toShortArray();
                return new MarkerSnapshot(markers.snapshot(),refs,values);
            }
            public int occurrences(int x,int y,int z) { return markers.occurrences(new Cell(x,y,z)); }
            public void mark(int x,int y,int z) { markers.mark(new Cell(x,y,z)); }
            public void restore(Object value) { markers.restore(((MarkerSnapshot)value).nativeSnapshot()); }
            public boolean matchesSnapshot(Object value) {
                var old = (MarkerSnapshot)value; var now = chunk.getPostProcessing();
                if (now.length != old.refs().length) return false;
                for(int i=0;i<now.length;i++) if(now[i]!=old.refs()[i] || now[i]!=null && !Arrays.equals(now[i].toShortArray(),old.values()[i])) return false;
                return true;
            }
        };
        return new CubeNetUndergroundStageAdapter().replay(prepared.plan, new CubeNetUndergroundStageAdapter.Target() {
            public Object identity() { return chunk; }
            public int chunkX() { return chunk.getPos().x; }
            public int chunkZ() { return chunk.getPos().z; }
            public int minY() { return chunk.getMinBuildHeight(); }
            public int maxYExclusive() { return chunk.getMaxBuildHeight(); }
            public void preflight() {
                validateTarget(chunk, request);
                for(int i=0;i<prepared.baseline.length;i++) if(!prepared.baseline[i].equals(chunk.getBlockState(position(request,i))))
                    throw new IllegalStateException("detached underground target snapshot is stale");
            }
            public Object read(int x,int y,int z) { return chunk.getBlockState(new BlockPos(x,y,z)); }
            public boolean replaceable(Object current, UndergroundStagePlan.UndergroundMaterial material) { return current instanceof BlockState; }
            public void write(int x,int y,int z,UndergroundStagePlan.UndergroundMaterial material) { MinecraftVersionCompat.setBlockState(chunk,new BlockPos(x,y,z),state(material)); }
            public void writePrevious(int x,int y,int z,Object previous) { MinecraftVersionCompat.setBlockState(chunk,new BlockPos(x,y,z),(BlockState)previous); }
            public boolean matches(Object current,UndergroundStagePlan.UndergroundMaterial material) { return state(material).equals(current); }
            public CubeNetUndergroundStageAdapter.PostProcessing postProcessing() { return metadata; }
            public boolean dirty() { return chunk.isUnsaved(); }
            public void setDirty(boolean value) { chunk.setUnsaved(value); }
        });
    }

    private record MarkerSnapshot(Object nativeSnapshot, ShortList[] refs, short[][] values) { }
    private static Preparation stopped(PreparationStatus status,String diagnostic) { return new Preparation(status,Optional.empty(),List.of(diagnostic)); }
    private static CubeNetUndergroundStageAdapter.ReplayResult invalid(String diagnostic) {
        return new CubeNetUndergroundStageAdapter.ReplayResult(CubeNetUndergroundStageAdapter.ReplayStatus.INVALID,0,List.of(diagnostic));
    }
    private static void validateTarget(ProtoChunk target,UndergroundStagePlan.Request request) {
        if(target.getPos().x!=request.chunkX() || target.getPos().z!=request.chunkZ()
            || target.getMinBuildHeight()!=request.generation().minY() || target.getMaxBuildHeight()!=request.generation().maxYExclusive())
            throw new IllegalArgumentException("native underground target identity or bounds differ");
        new com.yucareux.tellus.worldgen.global.SphereSingularityPolicy(request.model(),
            com.yucareux.tellus.worldgen.global.SphereSingularityPolicy.DEFAULT_CONFIG).requireTerrainMaterialization(request.address());
        var sections=target.getSections();
        if(sections.length != (request.generation().maxYExclusive()-request.generation().minY())/16) throw new IllegalArgumentException("section count differs");
        Set<Object> identities=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var section:sections) if(section==null || section.getClass()!=LevelChunkSection.class || !identities.add(section)
            || section.getStates().getClass()!=PalettedContainer.class || !identities.add(section.getStates()))
            throw new IllegalArgumentException("native underground requires unique exact sections and state palettes");
    }
    private static BlockState[] snapshot(ProtoChunk target,UndergroundStagePlan.Request request) {
        int count=Math.toIntExact(((long)request.generation().maxYExclusive()-request.generation().minY())*256);
        if(count>1_048_576) throw new IllegalArgumentException("underground snapshot budget");
        var values=new BlockState[count];
        for(int i=0;i<count;i++) values[i]=Objects.requireNonNull(target.getBlockState(position(request,i)));
        return values;
    }
    private static BlockPos position(UndergroundStagePlan.Request request,int index) {
        return new BlockPos(request.chunkX()*16+(index&15),request.generation().minY()+(index>>>8),request.chunkZ()*16+((index>>>4)&15));
    }
    private static UndergroundStagePlan.UndergroundMaterial material(BlockState state) {
        for(var material:UndergroundStagePlan.UndergroundMaterial.values()) if(state(material).equals(state)) return material;
        throw new IllegalArgumentException("native cave sampler returned unsupported material");
    }
    public static BlockState state(UndergroundStagePlan.UndergroundMaterial material) {
        return switch(material) {
            case CAVE_AIR -> Blocks.CAVE_AIR.defaultBlockState(); case WATER -> Blocks.WATER.defaultBlockState();
            case LAVA -> Blocks.LAVA.defaultBlockState(); case COPPER_ORE -> Blocks.COPPER_ORE.defaultBlockState();
            case DEEPSLATE_IRON_ORE -> Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
            case GRANITE -> Blocks.GRANITE.defaultBlockState(); case TUFF -> Blocks.TUFF.defaultBlockState();
        };
    }
}
