package com.yucareux.tellus.worldgen.persistence;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native save admission during synchronous queue publication/recovery, not an alternative scheduler. */
public final class TellusNativeSaveFence {
    private static final Logger LOGGER = LoggerFactory.getLogger("tellus-native-recovery");
    private static final Map<ServerLevel, Boolean> ACTIVE = new IdentityHashMap<>();
    private static final Map<ServerLevel, Boolean> FAILED = new IdentityHashMap<>();
    private static final Map<ServerLevel, java.util.List<String>> STARTUP = new IdentityHashMap<>();
    private TellusNativeSaveFence() { }

    public static synchronized boolean deferEngineSave(ServerLevel level) {
        return ACTIVE.containsKey(level) || FAILED.containsKey(level);
    }
    public static synchronized boolean blocksChunkLoads(ServerLevel level) { return FAILED.containsKey(level); }
    public static synchronized java.util.List<String> startupDiagnostics(ServerLevel level) {
        return STARTUP.getOrDefault(level, java.util.List.of());
    }

    static TellusNativeChunkPersistence.SaveFence acquire(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("native save fence requires main thread");
        final boolean previous;
        synchronized (TellusNativeSaveFence.class) {
            if (ACTIVE.containsKey(level)) throw new IllegalStateException("native save fence is non-reentrant");
            previous = FAILED.getOrDefault(level, level.noSave);
            ACTIVE.put(level, previous);
        }
        level.noSave = true;
        return () -> {
            synchronized (TellusNativeSaveFence.class) {
                ACTIVE.remove(level);
                level.noSave = FAILED.containsKey(level) || previous;
            }
        };
    }

    static synchronized void failed(ServerLevel level) {
        FAILED.putIfAbsent(level, ACTIVE.getOrDefault(level, level.noSave)); level.noSave = true;
    }
    static synchronized void recovered(ServerLevel level) { FAILED.remove(level); }

    /** Runs before prepareLevels starts spawn loading or ordinary server ticks/saves. */
    public static void recoverBeforeLevels(MinecraftServer server) {
        var root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(root.resolve("tellus/persistence-journal"))) {
            LOGGER.info("TELLUS_NATIVE_STARTUP_RECOVERY_READY no pending journal directory; native storage untouched");
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            try {
                TellusNativeChunkPersistence.FaultInjector faults = TellusNativeChunkPersistence.FaultInjector.NONE;
                if (Boolean.getBoolean("tellus.persistence.probe.recoveryFailure")) {
                    if (!Boolean.getBoolean("tellus.persistence.probe")
                        || !root.toRealPath().equals(java.nio.file.Path.of(System.getProperty(
                            TellusNativeChunkPersistence.PROBE_ROOT_PROPERTY, "")).toRealPath()))
                        throw new IllegalStateException("startup fault injection requires the exact copied probe world");
                    faults = point -> { if (point == TellusNativeChunkPersistence.Point.AFTER_SUBMIT)
                        throw new IllegalStateException("explicit copied-world startup recovery fault"); };
                }
                var persistence = TellusNativeChunkPersistence.open(true, level, root, faults);
                synchronized (TellusNativeSaveFence.class) { STARTUP.put(level, persistence.startupRecoveryDiagnostics()); }
                LOGGER.info("TELLUS_NATIVE_STARTUP_RECOVERY_READY dimension={} recovered={}",
                    level.dimension().location(), persistence.startupRecoveryDiagnostics());
            } catch (java.io.IOException | RuntimeException | Error failure) {
                for (ServerLevel other : server.getAllLevels()) failed(other);
                throw new IllegalStateException("native startup recovery failed before ordinary chunk loading", failure);
            }
        }
    }
}
