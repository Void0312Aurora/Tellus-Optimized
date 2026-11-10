package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.write.TellusTargetWriteGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Shared admission/execution at the real native generation boundaries. Minecraft
 * still schedules the stages and owns status, neighbour access and persistence.
 * A single exact-target lease spans each native stage and its nested shared sinks.
 * This does not pretend that neighbour decoration is a one-chunk disk transaction.
 */
public final class TellusNativeProductionPipeline {
    public static final String MODE_PROPERTY = "tellus.worldgen.pipeline";
    public enum Stage { BIOMES, TERRAIN, CARVERS, DECORATION }
    public enum Mode { LEGACY, UNIFIED }
    private static volatile boolean nativeBootstrapDefault;
    public record Observation(String family, int chunkX, int chunkZ, List<String> stages, List<String> backends,
                              List<String> failures) { }
    private final String family;
    private final Mode mode;
    private final LinkedHashMap<Long, Progress> observations = new LinkedHashMap<>();
    private static final class Progress {
        final List<String> stages = new ArrayList<>(), backends = new ArrayList<>(), failures = new ArrayList<>();
    }

    public TellusNativeProductionPipeline(String family) { this(family, configuredMode()); }
    public TellusNativeProductionPipeline(String family, Mode mode) {
        this.family = Objects.requireNonNull(family, "family");
        this.mode = Objects.requireNonNull(mode, "mode");
    }
    public static Mode configuredMode() {
        return switch (System.getProperty(MODE_PROPERTY, nativeBootstrapDefault ? "unified" : "legacy")) {
            case "legacy" -> Mode.LEGACY;
            case "unified" -> Mode.UNIFIED;
            default -> throw new IllegalArgumentException("unknown Tellus native production pipeline mode");
        };
    }
    /** Only the supported, real loader bootstrap selects a release default; standalone callers stay explicit. */
    static void nativeBootstrapDefault(boolean enabled) { nativeBootstrapDefault = enabled; }
    public boolean enabled() { return mode == Mode.UNIFIED; }
    public Mode mode() { return mode; }

    public <T> T run(Stage stage, ChunkAccess target, String backend, Function<TellusTargetWriteGuard.Lease, T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (!enabled()) return operation.apply(null);
        Objects.requireNonNull(stage, "stage"); Objects.requireNonNull(target, "target"); Objects.requireNonNull(backend, "backend");
        var acquired = TellusTargetWriteGuard.tryAcquire(target);
        if (acquired.isEmpty()) throw new IllegalStateException("native production target already has a participating writer");
        try (var lease = acquired.orElseThrow()) {
            lease.requireOwned(target);
            T result = operation.apply(lease);
            lease.requireOwned(target);
            record(target.getPos(), stage, backend, null);
            return result;
        } catch (RuntimeException | Error failure) {
            record(target.getPos(), stage, backend, failure);
            throw failure; // No legacy retry after an admitted stage may already have written.
        }
    }
    private synchronized void record(ChunkPos position, Stage stage, String backend, Throwable failure) {
        long key = position.toLong();
        Progress progress = observations.computeIfAbsent(key, ignored -> new Progress());
        if (failure == null) { progress.stages.add(stage.name()); progress.backends.add(backend); }
        else progress.failures.add(stage.name() + ":" + failure.getClass().getName());
        while (observations.size() > 4096) observations.remove(observations.keySet().iterator().next());
    }
    public synchronized Observation observation(ChunkPos position) {
        Progress p = observations.get(position.toLong());
        return new Observation(family, position.x, position.z, p == null ? List.of() : List.copyOf(p.stages),
            p == null ? List.of() : List.copyOf(p.backends), p == null ? List.of() : List.copyOf(p.failures));
    }
}
