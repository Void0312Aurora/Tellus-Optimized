package com.yucareux.tellus.probe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.GraphDirection;
import com.yucareux.tellus.client.CubeNetClientTransition;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Independent opt-in test control; no mixins and no persistent renderer options. */
public final class RenderProbe implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("tellus-render-probe");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private Path root;
    private String lastId;
    private String pendingId;
    private int captureFrames;
    private int moveTicks;
    private String moveKey = "none";
    private int pollTicks;
    private int timingFrames;
    private long lastFrameNanos;
    private long captureNotBeforeNanos;
    private long requestStartedNanos;
    private final ArrayDeque<FrameSample> frameSamples = new ArrayDeque<>();

    @Override public void onInitializeClient() {
        root = Minecraft.getInstance().gameDirectory.toPath().resolve("tellus-render-probe");
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        WorldRenderEvents.END.register(context -> {
            Minecraft client = Minecraft.getInstance();
            recordFrame(client);
            if (pendingId != null && --captureFrames <= 0 && System.nanoTime() >= captureNotBeforeNanos) capture(client);
        });
        LOG.info("Test probe installed; commands are opt-in via {}/request.json", root);
    }

    private void tick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        if (moveTicks > 0) {
            if (--moveTicks == 0) setMovement(client, "none");
        }
        if (++pollTicks % 5 != 0) return;
        Path request = root.resolve("request.json");
        if (!Files.isRegularFile(request)) return;
        try {
            JsonObject command = JSON.fromJson(Files.readString(request), JsonObject.class);
            String id = command.get("id").getAsString();
            if (!id.matches("[A-Za-z0-9_-]{1,90}") || id.equals(lastId)) return;
            lastId = id;
            if (command.has("debug")) client.options.renderDebug = command.get("debug").getAsBoolean();
            if (command.has("yaw")) client.player.setYRot(command.get("yaw").getAsFloat());
            if (command.has("pitch")) client.player.setXRot(command.get("pitch").getAsFloat());
            if (command.has("yaw") || command.has("pitch")) client.player.setOldPosAndRot();
            if (command.has("flying")) {
                client.player.getAbilities().flying = command.get("flying").getAsBoolean();
                client.player.onUpdateAbilities();
                client.player.setDeltaMovement(0, 0, 0);
            }
            if (command.has("fogOcclusion")) {
                SodiumClientMod.options().performance.useFogOcclusion = command.get("fogOcclusion").getAsBoolean();
                SodiumWorldRenderer.instance().scheduleTerrainUpdate();
            }
            if (command.has("smartCull")) {
                client.smartCull = command.get("smartCull").getAsBoolean();
                SodiumWorldRenderer.instance().scheduleTerrainUpdate();
            }
            if (command.has("shaders")) {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Object config = iris.getMethod("getIrisConfig").invoke(null);
                config.getClass().getMethod("setShadersEnabled", boolean.class)
                    .invoke(config, command.get("shaders").getAsBoolean());
                iris.getMethod("reload").invoke(null);
            }
            if (command.has("dh")) setDh(command.get("dh").getAsBoolean());
            if (command.has("reloadTerrain") && command.get("reloadTerrain").getAsBoolean()) {
                SodiumWorldRenderer.instance().reload();
            }
            if (command.has("move")) {
                moveTicks = Math.max(0, Math.min(600, command.has("ticks") ? command.get("ticks").getAsInt() : 0));
                setMovement(client, moveTicks > 0 ? command.get("move").getAsString() : "none");
            }
            pendingId = id;
            captureFrames = Math.max(3, Math.min(300, command.has("waitFrames") ? command.get("waitFrames").getAsInt() : 3));
            timingFrames = Math.max(0, Math.min(7200, command.has("timingFrames") ? command.get("timingFrames").getAsInt() : 0));
            requestStartedNanos = System.nanoTime();
            long waitMillis = Math.max(0, Math.min(60000, command.has("waitMillis") ? command.get("waitMillis").getAsLong() : 0));
            captureNotBeforeNanos = requestStartedNanos + waitMillis * 1_000_000L;
            if (command.has("resetTiming") && command.get("resetTiming").getAsBoolean()) {
                frameSamples.clear();
                lastFrameNanos = requestStartedNanos;
            }
            Files.createDirectories(root);
            Files.writeString(root.resolve(id + "-accepted.json"), JSON.toJson(command));
            LOG.info("Accepted render probe request {}", id);
        } catch (Exception error) {
            LOG.error("Render probe request failed", error);
            setMovement(client, "none");
            moveTicks = 0;
        }
    }

    private void setMovement(Minecraft client, String key) {
        moveKey = key;
        client.options.keyUp.setDown("forward".equals(key));
        client.options.keyDown.setDown("backward".equals(key));
        client.options.keyLeft.setDown("left".equals(key));
        client.options.keyRight.setDown("right".equals(key));
    }

    private void capture(Minecraft client) {
        String id = pendingId;
        pendingId = null;
        if (client.player == null || client.level == null) return;
        try {
            var camera = client.gameRenderer.getMainCamera();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("utc", Instant.now().toString());
            data.put("gameTime", client.level.getGameTime());
            data.put("dimension", client.level.dimension().location().toString());
            data.put("player", new double[] {client.player.getX(), client.player.getY(), client.player.getZ()});
            data.put("camera", new double[] {camera.getPosition().x, camera.getPosition().y, camera.getPosition().z});
            data.put("yaw", camera.getYRot());
            data.put("pitch", camera.getXRot());
            data.put("viewDistance", client.options.getEffectiveRenderDistance());
            data.put("fogOcclusion", SodiumClientMod.options().performance.useFogOcclusion);
            data.put("smartCull", client.smartCull);
            data.put("move", moveKey);
            data.put("moveTicksRemaining", moveTicks);
            data.put("requestElapsedMs", (System.nanoTime() - requestStartedNanos) / 1_000_000.0);
            data.put("chunkStatistics", client.levelRenderer.getChunkStatistics());
            data.put("glRenderer", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
            data.put("shaders", shaderState());
            data.put("irisLighting", irisLighting());
            data.put("dh", dhState());
            data.put("transition", CubeNetClientTransition.diagnostics());
            var shadeDirections = new LinkedHashMap<String, String>();
            var shadeFactors = new LinkedHashMap<String, Float>();
            for (Direction direction : Direction.values()) {
                shadeDirections.put(direction.name(), CubeNetClientTransition.shadeDirection(direction).name());
                shadeFactors.put(direction.name(), client.level.getShade(direction, true));
            }
            data.put("directionalShade", Map.of("mappedDirections", shadeDirections,
                "factors", shadeFactors));
            data.put("frameTiming", frameTiming());
            RenderSectionManager manager = (RenderSectionManager) field(SodiumWorldRenderer.instance(), "renderSectionManager");
            @SuppressWarnings("unchecked")
            Long2ReferenceMap<RenderSection> sections = (Long2ReferenceMap<RenderSection>) field(manager, "sectionByPosition");
            @SuppressWarnings("unchecked")
            Map<ChunkUpdateType, java.util.ArrayDeque<RenderSection>> rebuildLists =
                (Map<ChunkUpdateType, java.util.ArrayDeque<RenderSection>>) field(manager, "rebuildLists");
            var rebuildQueues = new LinkedHashMap<String, Integer>();
            for (var entry : rebuildLists.entrySet()) rebuildQueues.put(entry.getKey().name(), entry.getValue().size());
            data.put("manager", Map.of("sections", sections.size(), "visibleGeometry", manager.getVisibleChunkCount(),
                "needsUpdate", manager.needsUpdate(), "lastUpdatedFrame", field(manager, "lastUpdatedFrame"),
                "rebuildQueues", rebuildQueues));
            var rows = new ArrayList<Map<String, Object>>();
            int neighborMismatch = 0;
            int built = 0;
            int geometry = 0;
            var pendingUpdates = new LinkedHashMap<String, Integer>();
            for (RenderSection section : sections.values()) {
                if (section.isBuilt()) built++;
                if (section.getFlags() != 0) geometry++;
                String pending = String.valueOf(section.getPendingUpdate());
                pendingUpdates.merge(pending, 1, Integer::sum);
                for (int direction = 0; direction < 6; direction++) {
                    RenderSection expected = sections.get(SectionPos.asLong(section.getChunkX() + GraphDirection.x(direction),
                        section.getChunkY() + GraphDirection.y(direction), section.getChunkZ() + GraphDirection.z(direction)));
                    if (section.getAdjacent(direction) != expected) neighborMismatch++;
                }
                var chunk = client.level.getChunkSource().getChunk(section.getChunkX(), section.getChunkZ(), false);
                boolean nonAir = chunk != null && !chunk.getSection(client.level.getSectionIndexFromSectionY(section.getChunkY())).hasOnlyAir();
                rows.add(Map.of("xyz", new int[] {section.getChunkX(),section.getChunkY(),section.getChunkZ()},
                    "built", section.isBuilt(), "flags", section.getFlags(), "liveNonAir", nonAir,
                    "lastVisible", section.getLastVisibleFrame(), "visibility", section.getVisibilityData(),
                    "pending", String.valueOf(section.getPendingUpdate())));
            }
            data.put("builtSections", built);
            data.put("sectionsWithFlags", geometry);
            data.put("pendingUpdates", pendingUpdates);
            data.put("neighborMismatch", neighborMismatch);
            data.put("sections", rows);
            var drawn = new ArrayList<int[]>();
            for (var iterator = manager.getRenderLists().iterator(); iterator.hasNext();) {
                var list = iterator.next();
                var indices = list.sectionsWithGeometryIterator(false);
                if (indices == null) continue;
                while (indices.hasNext()) {
                    var section = list.getRegion().getSection(indices.nextByteAsInt());
                    if (section != null) drawn.add(new int[] {section.getChunkX(), section.getChunkY(), section.getChunkZ()});
                }
            }
            data.put("drawnSections", drawn);
            Files.createDirectories(root);
            Files.writeString(root.resolve(id + ".json"), JSON.toJson(data));
            try (NativeImage screenshot = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                screenshot.writeToFile(root.resolve(id + ".png"));
            }
            LOG.info("Saved render probe {} visible={} built={} neighborMismatch={}", id, drawn.size(), built, neighborMismatch);
        } catch (Exception error) {
            LOG.error("Render probe capture failed {}", id, error);
        }
    }

    private void recordFrame(Minecraft client) {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L && client.level != null) {
            double millis = (now - lastFrameNanos) / 1_000_000.0;
            Map<String, Object> transition = CubeNetClientTransition.diagnostics();
            frameSamples.addLast(new FrameSample(client.level.getGameTime(), millis, transition));
            while (frameSamples.size() > 7200) frameSamples.removeFirst();
            if (millis >= 100.0) LOG.warn("Render frame gap ms={} gameTime={} transition={}",
                millis, client.level.getGameTime(), transition);
        }
        lastFrameNanos = now;
    }

    private Map<String, Object> frameTiming() {
        int count = Math.min(timingFrames, frameSamples.size());
        var samples = new ArrayList<FrameSample>(frameSamples);
        if (count < samples.size()) samples = new ArrayList<>(samples.subList(samples.size() - count, samples.size()));
        var values = samples.stream().map(sample -> sample.millis).sorted().toList();
        if (values.isEmpty()) return Map.of("requestedFrames", timingFrames, "sampledFrames", 0);
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double p50 = values.get((values.size() - 1) / 2);
        double p95 = values.get(Math.min(values.size() - 1, (int)Math.ceil(values.size() * 0.95) - 1));
        long over16 = values.stream().filter(value -> value > 16.667).count();
        long over33 = values.stream().filter(value -> value > 33.333).count();
        long over50 = values.stream().filter(value -> value > 50.0).count();
        var rows = new ArrayList<Map<String, Object>>();
        for (FrameSample sample : samples) {
            var row = new LinkedHashMap<String, Object>();
            row.put("gameTime", sample.gameTime);
            row.put("ms", sample.millis);
            row.put("transition", sample.transition);
            rows.add(row);
        }
        return Map.of("requestedFrames", timingFrames, "sampledFrames", values.size(),
            "meanMs", sum / values.size(), "p50Ms", p50, "p95Ms", p95,
            "maxMs", values.get(values.size() - 1), "over16ms", over16,
            "over33ms", over33, "over50ms", over50, "samples", rows);
    }

    private record FrameSample(long gameTime, double millis, Map<String, Object> transition) { }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setDh(boolean enabled) throws ReflectiveOperationException {
        Object entry = dhEntry();
        Class type = Class.forName("com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode");
        Object value = Enum.valueOf(type, enabled ? "DEFAULT" : "DISABLED");
        entry.getClass().getMethod("setApiValue", Object.class).invoke(entry, value);
    }

    private static Object dhEntry() throws ReflectiveOperationException {
        return Class.forName("com.seibel.distanthorizons.core.config.Config$Client$Advanced$Debugging")
            .getField("rendererMode").get(null);
    }

    private static Object dhState() {
        try { Object entry = dhEntry(); return entry.getClass().getMethod("get").invoke(entry).toString(); }
        catch (Exception error) { return "unavailable: " + error; }
    }

    private static Object shaderState() {
        try {
            Object config = Class.forName("net.irisshaders.iris.Iris").getMethod("getIrisConfig").invoke(null);
            return config.getClass().getMethod("areShadersEnabled").invoke(config);
        } catch (Exception error) { return "unavailable: " + error; }
    }

    /** Captures the optional Iris vectors after Tellus' chart mixins run. */
    private static Map<String, Object> irisLighting() {
        var data = new LinkedHashMap<String, Object>();
        data.put("shadeQuarterTurns", CubeNetClientTransition.shadeQuarterTurns());
        try {
            Class<?> celestialClass = Class.forName("net.irisshaders.iris.uniforms.CelestialUniforms");
            Object celestial = celestialClass.getConstructor(float.class).newInstance(0.0F);
            data.put("shadowLightWorld", vector(celestialClass.getMethod(
                "getShadowLightPositionInWorldSpace").invoke(celestial)));
            data.put("shadowLightView", vector(celestialClass.getMethod(
                "getShadowLightPosition").invoke(celestial)));

            Class<?> matricesClass = Class.forName("net.irisshaders.iris.shadows.ShadowMatrices");
            PoseStack pose = new PoseStack();
            matricesClass.getMethod("createBaselineModelViewMatrix", PoseStack.class, float.class, float.class)
                .invoke(null, pose, 0.0F, 0.0F);
            Matrix4f matrix = new Matrix4f(pose.last().pose());
            float[] values = new float[16];
            matrix.get(values);
            data.put("shadowBaselineMatrix", values);
            return data;
        } catch (Exception error) {
            data.put("error", error.toString());
            return data;
        }
    }

    private static Object vector(Object value) {
        if (!(value instanceof Vector4f vector)) return String.valueOf(value);
        return new float[] {vector.x(), vector.y(), vector.z(), vector.w()};
    }
}
