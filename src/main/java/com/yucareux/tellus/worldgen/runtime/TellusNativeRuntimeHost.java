package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.platform.TellusRuntimePlatform;
import com.yucareux.tellus.worldgen.persistence.TellusNativeSaveFence;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import com.yucareux.tellus.compat.MinecraftRelease;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.slf4j.LoggerFactory;

/** Native lifecycle authority over the existing registry/adapters, not a second generation or storage service. */
public final class TellusNativeRuntimeHost {
    // Accepted native release default; -Dtellus.worldgen.pipeline=legacy restores the untouched old route at startup.
    private static final boolean RELEASE_DEFAULT = true;
    private static final IdentityHashMap<MinecraftServer, Session> sessions = new IdentityHashMap<>();
    private static boolean installed;
    private TellusNativeRuntimeHost() { }

    public record Observation(boolean bootstrapInstalled, String configuredMode, int registrations, int attached,
                              long tickCallbacks, long chunkUnloadCallbacks, boolean targetAttached, String targetAdapter,
                              List<AdapterObservation> adapters) { }
    public record AdapterObservation(String adapter, boolean attached, long nativeDeferredTickCallbacks, long nativeUnloadCallbacks) { }

    public static void install(TellusRuntimePlatform platform) {
        java.util.Objects.requireNonNull(platform, "platform");
        String version = MinecraftRelease.VERSION;
        if (!version.equals("1.20.1") && !version.equals("1.21.1")) return;
        if (installed) throw new IllegalStateException("native runtime platform already installed");
        installed = true;
        TellusNativeProductionPipeline.nativeBootstrapDefault(RELEASE_DEFAULT);
    }

    /** Called only after durable startup recovery and before prepareLevels can load/generate chunks. */
    public static void prepareBeforeLevels(MinecraftServer server) {
        if (!installed) return;
        if (TellusNativeProductionPipeline.configuredMode() == TellusNativeProductionPipeline.Mode.LEGACY) {
            LoggerFactory.getLogger("tellus-native-runtime").info("TELLUS_NATIVE_RUNTIME_LEGACY no default adapters registered");
            return;
        }
        if (!server.isSameThread()) throw new IllegalStateException("native runtime bootstrap must use the server thread");
        if (sessions.containsKey(server)) throw new IllegalStateException("native runtime server already prepared");
        Session session = new Session();
        try {
            session.synchronize(server);
            sessions.put(server, session);
            LoggerFactory.getLogger("tellus-native-runtime").info("TELLUS_NATIVE_RUNTIME_ATTACHED_BEFORE_LEVELS count={}", session.registry.size());
        } catch (RuntimeException | Error failure) {
            try { session.manager.onServerStopping(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static void tick(MinecraftServer server) {
        Session session = sessions.get(server);
        if (session == null) return;
        session.synchronize(server);
        session.manager.onServerTick();
        session.ticks++;
        if (session.ticks == 1) LoggerFactory.getLogger("tellus-native-runtime").info(
            "TELLUS_NATIVE_RUNTIME_TICK_READY attached={} ticks=1", session.registry.size());
    }
    public static boolean ownsDeferredTick(ServerLevel level) {
        Session session = sessions.get(level.getServer());
        if (session == null) return false;
        var resolution = session.registry.resolve(level.getChunkSource().getGenerator());
        return resolution.found() && resolution.runtime().orElseThrow().managesDeferredChunkDetails();
    }
    public static boolean chunkUnloaded(ServerLevel level, ChunkPos position) {
        Session session = sessions.get(level.getServer());
        if (session == null) return false;
        var resolution = session.registry.resolve(level.getChunkSource().getGenerator());
        if (!resolution.found()) return false;
        boolean handled = resolution.runtime().orElseThrow().onNativeChunkUnload(position);
        if (handled) session.unloads++;
        return handled;
    }
    public static void stop(MinecraftServer server) {
        Session session = sessions.get(server);
        if (session == null) return;
        int attached = session.registry.size();
        var previous = session.registry.orderedAdapters();
        session.manager.onServerStopping();
        if (session.registry.size() != 0 || previous.stream().anyMatch(TellusGeneratorRuntime::attached))
            throw new IllegalStateException("native runtime detach left live adapters or registry entries");
        sessions.remove(server);
        LoggerFactory.getLogger("tellus-native-runtime").info("TELLUS_NATIVE_RUNTIME_STOP detached={} remaining=0 ticks={} unloads={}",
            attached, session.ticks, session.unloads);
    }
    public static Observation observation(ServerLevel target) {
        Session session = sessions.get(target.getServer());
        if (session == null) return new Observation(installed, TellusNativeProductionPipeline.configuredMode().name(), 0, 0, 0, 0, false, "", List.of());
        var found = session.registry.resolve(target.getChunkSource().getGenerator());
        int attached = (int)session.registry.orderedAdapters().stream().filter(TellusGeneratorRuntime::attached).count();
        return new Observation(installed, TellusNativeProductionPipeline.configuredMode().name(), session.registry.size(), attached,
            session.ticks, session.unloads, found.found() && found.runtime().orElseThrow().attached(),
            found.found() ? found.runtime().orElseThrow().getClass().getSimpleName() : "",
            session.registry.orderedAdapters().stream().map(adapter -> new AdapterObservation(adapter.getClass().getSimpleName(),
                adapter.attached(), adapter instanceof EarthGeneratorRuntimeAdapter earth ? earth.nativeTickCallbacks() : 0,
                adapter instanceof EarthGeneratorRuntimeAdapter earth ? earth.nativeUnloadCallbacks() : 0)).toList());
    }

    private static final class Session {
        final TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
        final TellusGeneratorRuntimeLifecycleManager manager = new TellusGeneratorRuntimeLifecycleManager(registry, true);
        final IdentityHashMap<ChunkGenerator, ServerLevel> bindings = new IdentityHashMap<>();
        long ticks, unloads;
        void synchronize(MinecraftServer server) {
            IdentityHashMap<ChunkGenerator, ServerLevel> current = new IdentityHashMap<>();
            for (ServerLevel level : server.getAllLevels()) {
                ChunkGenerator generator = level.getChunkSource().getGenerator();
                if (!(generator instanceof TellusNativeProductionOwner owner) || !owner.nativeProductionPipeline().enabled()) continue;
                if (TellusNativeSaveFence.blocksChunkLoads(level)) throw new IllegalStateException("native runtime level remains quarantined");
                if (current.put(generator, level) != null) throw new IllegalStateException("one generator identity belongs to multiple native levels");
                if (bindings.containsKey(generator)) {
                    if (bindings.get(generator) != level) throw new IllegalStateException("native runtime level identity changed");
                    continue;
                }
                TellusGeneratorRuntime adapter = owner.createNativeRuntimeAdapter();
                adapter.bindNativeLevel(level);
                var token = registry.registerWithRollback(generator, adapter);
                if (!token.registered()) throw new IllegalStateException("native runtime factory registration failed: " + token.result().status());
                try {
                    if (manager.onGeneratorRegistered(generator).status() != BridgeResult.Status.ATTACHED)
                        throw new IllegalStateException("native runtime did not attach its exact generator");
                    bindings.put(generator, level);
                } catch (RuntimeException | Error failure) {
                    try { token.rollback(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
            for (ChunkGenerator removed : new ArrayList<>(bindings.keySet())) if (!current.containsKey(removed)) {
                manager.onGeneratorUnregistered(removed);
                bindings.remove(removed);
            }
        }
    }
}
