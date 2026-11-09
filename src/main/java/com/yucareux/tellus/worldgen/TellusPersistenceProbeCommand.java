package com.yucareux.tellus.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yucareux.tellus.compat.ChunkBenchmarkVersionCompat;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.platform.TellusRuntimePlatform;
import com.yucareux.tellus.worldgen.persistence.TellusNativeChunkPersistence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit isolated-world native persistence probe; never registers a runtime or replaces normal saves. */
public final class TellusPersistenceProbeCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("tellus-persistence-probe");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    // Native save clocks may change between capture, shutdown and materialized reload.
    // No section, tick, marker, light, heightmap, biome or structure field is excluded.
    private static final Set<String> SAVE_CLOCKS = Set.of("LastUpdate", "InhabitedTime");
    private static final long MAX_SNBT_BYTES = 16L * 1024 * 1024;
    private TellusPersistenceProbeCommand() { }

    public static void register(TellusRuntimePlatform platform) {
        platform.registerCommands(dispatcher -> dispatcher.register(Commands.literal("tellus")
            .then(Commands.literal("persistence")
                .requires(source -> Boolean.getBoolean("tellus.persistence.probe") && source.hasPermission(4))
                .then(phase("capture")).then(phase("verify")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> phase(String phase) {
        return Commands.literal(phase).then(Commands.argument("chunkX", IntegerArgumentType.integer())
            .then(Commands.argument("chunkZ", IntegerArgumentType.integer()).executes(context -> run(context.getSource(), phase,
                IntegerArgumentType.getInteger(context, "chunkX"), IntegerArgumentType.getInteger(context, "chunkZ")))));
    }

    private static int run(CommandSourceStack source, String phase, int x, int z) {
        JsonObject result = new JsonObject();
        Path output = null;
        try {
            ServerLevel level = source.getLevel();
            if (!Boolean.getBoolean("tellus.persistence.probe") || !source.getServer().isSameThread())
                throw new IllegalStateException("explicit main-thread persistence probe required");
            if (Boolean.getBoolean("tellus.sphere.syntheticSampler"))
                throw new IllegalStateException("synthetic surface sampler is excluded from this real-world probe");
            String id = UUID.fromString(System.getProperty("tellus.persistence.probe.id", "")).toString();
            Path actualWorld = source.getServer().getWorldPath(LevelResource.ROOT).toRealPath();
            Path allowedWorld = Path.of(System.getProperty(TellusNativeChunkPersistence.PROBE_ROOT_PROPERTY, "")).toRealPath();
            if (!actualWorld.equals(allowedWorld)) throw new IllegalStateException("isolated copied-world identity differs");
            Path directory = TellusPlatform.gameDir().resolve("tellus/persistence").toAbsolutePath().normalize();
            Files.createDirectories(directory);
            output = directory.resolve(id + "-" + phase + ".json");
            if (Files.exists(output)) throw new IllegalStateException("probe report already exists; choose a fresh run id");
            Path expectedPath = directory.resolve(id + "-expected.snbt");
            Path capturePath = directory.resolve(id + "-capture.json");
            var persistence = TellusNativeChunkPersistence.open(true, level);
            ChunkPos pos = new ChunkPos(x, z);
            result.addProperty("schemaVersion", 1);
            result.addProperty("phase", phase);
            result.addProperty("probeId", id);
            result.addProperty("chunkX", x); result.addProperty("chunkZ", z);
            result.add("binding", bindingJson(persistence.binding()));
            result.addProperty("syntheticSampler", false);
            result.addProperty("sourceBoundary", "pre-existing native FULL world chunk; GIS provenance recorded by isolated runner");
            var diskBeforeLoad = persistence.readExistingNBT(pos).orElseThrow(() ->
                new IllegalStateException("existing disk chunk required; regeneration is not persistence evidence"));
            result.addProperty("diskReadBeforeDemandLoad", true);
            result.addProperty("diskBeforeLoadDigest", TellusNativeChunkPersistence.digest(diskBeforeLoad));
            if (phase.equals("capture")) {
                if (Files.exists(expectedPath)) throw new IllegalStateException("existing expected snapshot");
                ChunkAccess chunk = ChunkBenchmarkVersionCompat.requestFullChunk(level.getChunkSource(), x, z);
                if (chunk == null) throw new IllegalStateException("native FULL chunk unavailable");
                CompoundTag loadedBaseline = persistence.snapshot(chunk);
                result.addProperty("originalDiskDataVersion", diskBeforeLoad.getInt("DataVersion"));
                result.addProperty("nativeDataVersion", loadedBaseline.getInt("DataVersion"));
                if (diskBeforeLoad.getInt("DataVersion") != loadedBaseline.getInt("DataVersion")) {
                    if (!Boolean.getBoolean("tellus.persistence.probe.allowUpgrade")
                        || diskBeforeLoad.getInt("DataVersion") > loadedBaseline.getInt("DataVersion"))
                        throw new IllegalStateException("native upgrade requires explicit copied-world admission");
                    // The existing FULL disk tag was verified before loading. Only native
                    // DataFixer/materialization and the existing server save queue perform migration.
                    chunk.setUnsaved(true);
                    source.getServer().saveEverything(false, true, true);
                    CompoundTag upgraded = persistence.readExistingNBT(pos).orElseThrow();
                    if (upgraded.getInt("DataVersion") != loadedBaseline.getInt("DataVersion"))
                        throw new IllegalStateException("native copied-world upgrade was not durably saved");
                    result.addProperty("nativeCopiedWorldUpgradeSaved", true);
                } else result.addProperty("nativeCopiedWorldUpgradeSaved", false);
                BlockPos changed = findStone(chunk);
                BlockState original = chunk.getBlockState(changed);
                String originalCells = blockDigest(chunk);
                String originalBiomes = biomeDigest(chunk);
                result.addProperty("originalBlockDigest", originalCells);
                result.addProperty("originalBiomeDigest", originalBiomes);
                result.addProperty("changedX", changed.getX()); result.addProperty("changedY", changed.getY()); result.addProperty("changedZ", changed.getZ());
                result.addProperty("oldState", original.toString());
                result.addProperty("newState", Blocks.GOLD_BLOCK.defaultBlockState().toString());
                int rollbacks = 0;
                CompoundTag beforeFaults = persistence.snapshot(chunk);
                for (var point : TellusNativeChunkPersistence.Point.values()) {
                    boolean dirty = chunk.isUnsaved();
                    MinecraftVersionCompat.setBlockState(chunk, changed, Blocks.GOLD_BLOCK.defaultBlockState());
                    try {
                        CompoundTag proposed = persistence.snapshot(chunk);
                        var recovered = persistence.commit(pos, proposed, p -> {
                            if (p == point) throw new IllegalStateException("probe fault " + point);
                        });
                        if (recovered.status() != TellusNativeChunkPersistence.Status.ROLLED_BACK)
                            throw new IllegalStateException("native rollback did not complete: " + recovered.diagnostics());
                        rollbacks++;
                    } finally {
                        MinecraftVersionCompat.setBlockState(chunk, changed, original);
                        chunk.setUnsaved(dirty);
                    }
                    if (!originalCells.equals(blockDigest(chunk)) || !originalBiomes.equals(biomeDigest(chunk)))
                        throw new IllegalStateException("loaded chunk differs after disk rollback and loaded-cell recovery");
                    if (!TellusNativeChunkPersistence.sameNBT(beforeFaults, persistence.snapshot(chunk), SAVE_CLOCKS))
                        throw new IllegalStateException("loaded complete native metadata differs after fault recovery at " + point);
                }
                result.addProperty("loadedFullNBTRecoveredForEveryFault", true);
                result.addProperty("nativeFaultRollbacks", rollbacks);
                MinecraftVersionCompat.setBlockState(chunk, changed, Blocks.GOLD_BLOCK.defaultBlockState());
                chunk.setUnsaved(true);
                CompoundTag expected = persistence.snapshot(chunk);
                var committed = persistence.commit(pos, expected, TellusNativeChunkPersistence.FaultInjector.NONE);
                if (committed.status() != TellusNativeChunkPersistence.Status.COMMITTED)
                    throw new IllegalStateException("native disk publication failed: " + committed.diagnostics());
                byte[] text = expected.toString().getBytes(StandardCharsets.UTF_8);
                if (text.length > MAX_SNBT_BYTES) throw new IllegalStateException("expected native tag exceeds archive bound");
                Files.write(expectedPath, text);
                result.addProperty("expectedExactNBTDigest", TellusNativeChunkPersistence.digest(expected));
                result.addProperty("expectedStableNBTDigest", TellusNativeChunkPersistence.digest(expected, SAVE_CLOCKS));
                result.addProperty("expectedBlockDigest", blockDigest(chunk));
                result.addProperty("expectedBiomeDigest", biomeDigest(chunk));
                result.addProperty("height", chunk.getHeight());
                result.addProperty("minY", ChunkBenchmarkVersionCompat.chunkMinY(chunk));
                result.addProperty("transactionScope", "native queued complete-NBT publication+readback+prior-NBT recovery; one loaded opaque cell recovered separately");
            } else {
                if (!Files.isRegularFile(capturePath) || !Files.isRegularFile(expectedPath) || Files.size(expectedPath) > MAX_SNBT_BYTES)
                    throw new IllegalStateException("bounded original capture artifacts required");
                JsonObject capture = JSON.fromJson(Files.readString(capturePath), JsonObject.class);
                if (!"PASS".equals(capture.get("status").getAsString()) || !capture.get("binding").equals(result.get("binding"))
                    || capture.get("chunkX").getAsInt() != x || capture.get("chunkZ").getAsInt() != z)
                    throw new IllegalStateException("world/dimension/seed/generator/chunk capture identity differs");
                CompoundTag expected = TagParser.parseTag(Files.readString(expectedPath, StandardCharsets.UTF_8));
                if (!capture.get("expectedExactNBTDigest").getAsString().equals(TellusNativeChunkPersistence.digest(expected)))
                    throw new IllegalStateException("expected capture tag integrity differs");
                if (!TellusNativeChunkPersistence.sameNBT(expected, diskBeforeLoad, SAVE_CLOCKS))
                    throw new IllegalStateException("native disk state changed across stop/restart (excluding two save clocks only)");
                result.addProperty("restartedDiskMatchesExpected", true);
                ChunkAccess reloaded = ChunkBenchmarkVersionCompat.requestFullChunk(level.getChunkSource(), x, z);
                if (reloaded == null) throw new IllegalStateException("materialized native reload unavailable");
                String blocks = blockDigest(reloaded), biomes = biomeDigest(reloaded);
                if (!capture.get("expectedBlockDigest").getAsString().equals(blocks)
                    || !capture.get("expectedBiomeDigest").getAsString().equals(biomes))
                    throw new IllegalStateException("materialized native reload block/biome mismatch");
                BlockPos changed = new BlockPos(capture.get("changedX").getAsInt(), capture.get("changedY").getAsInt(), capture.get("changedZ").getAsInt());
                if (!reloaded.getBlockState(changed).equals(Blocks.GOLD_BLOCK.defaultBlockState()))
                    throw new IllegalStateException("published opaque sentinel was regenerated or lost");
                CompoundTag materialized = persistence.snapshot(reloaded);
                result.addProperty("materializedNativeNBTDigest", TellusNativeChunkPersistence.digest(materialized, SAVE_CLOCKS));
                boolean materializedEqual = TellusNativeChunkPersistence.sameNBT(expected, materialized, SAVE_CLOCKS);
                result.addProperty("materializedFullNBTEqual", materializedEqual);
                if (!materializedEqual) {
                    var differingKeys = new com.google.gson.JsonArray();
                    var keys = new java.util.TreeSet<String>(expected.getAllKeys());
                    keys.addAll(materialized.getAllKeys());
                    for (String key : keys) {
                        if (SAVE_CLOCKS.contains(key)) continue;
                        CompoundTag a = new CompoundTag(), b = new CompoundTag();
                        if (expected.get(key) != null) a.put(key, expected.get(key).copy());
                        if (materialized.get(key) != null) b.put(key, materialized.get(key).copy());
                        if (!TellusNativeChunkPersistence.sameNBT(a, b, Set.of())) differingKeys.add(key);
                    }
                    result.add("materializedDifferingRootKeys", differingKeys);
                    throw new IllegalStateException("materialized full native NBT differs: " + differingKeys);
                }
                result.addProperty("materializedBlockDigest", blocks);
                result.addProperty("materializedBiomeDigest", biomes);
                result.addProperty("restartedMaterializedCellsMatch", true);
            }
            result.addProperty("status", "PASS");
            Files.writeString(output, JSON.toJson(result) + "\n", StandardCharsets.UTF_8);
            String marker = "TELLUS_PERSISTENCE_" + phase.toUpperCase(java.util.Locale.ROOT) + "_PASS";
            LOGGER.info("{} result={}", marker, output);
            source.sendSuccess(() -> Component.literal(marker), false);
            return 1;
        } catch (Exception failure) {
            result.addProperty("status", "FAIL"); result.addProperty("error", failure.toString());
            if (output != null && !Files.exists(output)) {
                try { Files.writeString(output, JSON.toJson(result) + "\n", StandardCharsets.UTF_8); }
                catch (Exception writeFailure) { failure.addSuppressed(writeFailure); }
            }
            LOGGER.error("TELLUS_PERSISTENCE_{}_FAIL result={}", phase.toUpperCase(java.util.Locale.ROOT), output, failure);
            source.sendFailure(Component.literal("Persistence probe failed: " + failure.getClass().getName()));
            return 0;
        }
    }

    private static JsonObject bindingJson(TellusNativeChunkPersistence.Binding binding) {
        JsonObject value = new JsonObject();
        value.addProperty("worldRoot", binding.worldRoot().toString());
        value.addProperty("dimension", binding.dimension());
        value.addProperty("seed", binding.seed());
        value.addProperty("generatorType", binding.generatorType());
        value.addProperty("generatorProfile", binding.generatorProfile());
        value.addProperty("minY", binding.minY());
        value.addProperty("height", binding.height());
        value.addProperty("seaLevel", binding.seaLevel());
        return value;
    }

    private static BlockPos findStone(ChunkAccess chunk) {
        int min = ChunkBenchmarkVersionCompat.chunkMinY(chunk), max = Math.addExact(min, chunk.getHeight());
        if (chunk.getHeight() <= 0 || chunk.getHeight() > 4096) throw new IllegalArgumentException("probe height budget");
        for (int y = min + 1; y < max; y++) for (int z = 2; z < 14; z++) for (int x = 2; x < 14; x++) {
            var pos = new BlockPos(Math.addExact(chunk.getPos().getMinBlockX(), x), y, Math.addExact(chunk.getPos().getMinBlockZ(), z));
            if (chunk.getBlockState(pos).equals(Blocks.STONE.defaultBlockState())) return pos;
        }
        throw new IllegalStateException("existing plain non-entity stone sentinel cell required");
    }

    private static String blockDigest(ChunkAccess chunk) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Map<BlockState, byte[]> encodings = new HashMap<>();
        int min = ChunkBenchmarkVersionCompat.chunkMinY(chunk), height = chunk.getHeight();
        if (height <= 0 || height > 4096) throw new IllegalArgumentException("probe height budget");
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < height; dy++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            cursor.set(chunk.getPos().getMinBlockX() + x, min + dy, chunk.getPos().getMinBlockZ() + z);
            byte[] bytes = encodings.computeIfAbsent(chunk.getBlockState(cursor), state -> (state.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String biomeDigest(ChunkAccess chunk) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int min = Math.floorDiv(ChunkBenchmarkVersionCompat.chunkMinY(chunk), 4);
        int count = chunk.getHeight() / 4;
        if (count <= 0 || count > 1024) throw new IllegalArgumentException("biome height budget");
        for (int y = min; y < min + count; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            var biome = chunk.getNoiseBiome(chunk.getPos().x * 4 + x, y, chunk.getPos().z * 4 + z);
            String key = biome.unwrapKey().map(keyValue -> MinecraftVersionCompat.keyId(keyValue)).orElseThrow();
            digest.update((key + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
