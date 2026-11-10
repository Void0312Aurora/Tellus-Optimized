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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
                 .then(phase("capture")).then(phase("verify")).then(phase("generate")).then(phase("sea_codec")).then(phase("runtime_reload"))
                .then(phase("crash_batch")).then(phase("recover_batch")).then(phase("verify_batch")))));
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
            result.add("runtimeLifecycle", JSON.toJsonTree(com.yucareux.tellus.worldgen.runtime.TellusNativeRuntimeHost.observation(level)));
            if (Boolean.getBoolean("tellus.persistence.probe.runtimeFamilies")) {
                ServerLevel earth = null, sphere = null;
                for (ServerLevel candidate : source.getServer().getAllLevels()) {
                    if (candidate.dimension().location().toString().equals("tellus:native_runtime_earth")) earth = candidate;
                    if (candidate.dimension().location().toString().equals("tellus:native_runtime_sphere")) sphere = candidate;
                }
                if (earth == null || sphere == null || !(earth.getChunkSource().getGenerator() instanceof EarthChunkGenerator)
                    || !(sphere.getChunkSource().getGenerator() instanceof SphereFaceChunkGenerator))
                    throw new IllegalStateException("real native runtime family levels missing");
                var host = com.yucareux.tellus.worldgen.runtime.TellusNativeRuntimeHost.observation(earth);
                if (!host.targetAttached() || !host.targetAdapter().equals("EarthGeneratorRuntimeAdapter")
                    || host.adapters().stream().noneMatch(a -> a.adapter().equals("EarthGeneratorRuntimeAdapter") && a.nativeDeferredTickCallbacks() == host.tickCallbacks() && a.nativeDeferredTickCallbacks() > 0))
                    throw new IllegalStateException("native Earth deferred detail callback did not run exactly once per platform tick");
                if (!com.yucareux.tellus.worldgen.runtime.TellusNativeRuntimeHost.observation(sphere).targetAttached())
                    throw new IllegalStateException("native Sphere lifecycle did not attach");
                if (!com.yucareux.tellus.worldgen.runtime.TellusNativeRuntimeHost.chunkUnloaded(earth, pos))
                    throw new IllegalStateException("native Earth owned unload dispatch was not handled");
                result.addProperty("explicitOwnedEarthUnloadDispatchWithoutDemandLoad", true);
                result.add("runtimeLifecycle", JSON.toJsonTree(com.yucareux.tellus.worldgen.runtime.TellusNativeRuntimeHost.observation(level)));
            }
            result.addProperty("sourceBoundary", "pre-existing native FULL world chunk; GIS provenance recorded by isolated runner");
            if (phase.equals("sea_codec") || phase.equals("runtime_reload")) {
                if (phase.equals("sea_codec")) {
                    if (!(level.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)
                        || !(generator.getBiomeSource() instanceof CubeNetBiomeSource))
                        throw new IllegalStateException("real offset restart requires the native settings-carrying Cube biome source");
                    int offset = Integer.parseInt(System.getProperty("tellus.persistence.probe.expectedHeightOffset"));
                    int sea = Integer.parseInt(System.getProperty("tellus.persistence.probe.expectedSeaLevel"));
                    if (generator.heightOffset() != offset || generator.getSeaLevel() != sea)
                        throw new IllegalStateException("decoded native height offset/sea level drifted across restart");
                    result.addProperty("nativeHeightOffset", generator.heightOffset());
                    result.addProperty("nativeSeaLevel", generator.getSeaLevel());
                    result.addProperty("settingsCarryingBiomeSource", true);
                }
                persistence.readExistingNBT(pos).orElseThrow(() -> new IllegalStateException("offset probe requires existing real native FULL terrain"));
                result.addProperty("diskReadBeforeDemandLoad", true);
                ChunkAccess loaded = ChunkBenchmarkVersionCompat.requestFullChunk(level.getChunkSource(), x, z);
                if (loaded == null) throw new IllegalStateException("native offset reload returned no FULL chunk");
                CompoundTag materialized = persistence.snapshot(loaded);
                result.addProperty("blockDigest", blockDigest(loaded));
                result.addProperty("biomeDigest", biomeDigest(loaded));
                result.addProperty("nativeNBTDigest", TellusNativeChunkPersistence.digest(materialized, SAVE_CLOCKS));
                loaded.setUnsaved(true);
                source.getServer().saveEverything(false, true, true);
                CompoundTag saved = persistence.readExistingNBT(pos).orElseThrow();
                if (!TellusNativeChunkPersistence.sameNBT(materialized, saved, SAVE_CLOCKS))
                    throw new IllegalStateException("offset native materialization differs through engine save");
                result.addProperty("nativeSavedPayloadEqual", true);
                Files.writeString(directory.resolve(id + (phase.equals("sea_codec") ? "-sea-codec.snbt" : "-runtime-reload.snbt")), saved.toString(), StandardCharsets.UTF_8);
                return complete(source, phase, result, output);
            }
            if (phase.endsWith("_batch")) {
                if (!Boolean.getBoolean("tellus.persistence.probe.crash"))
                    throw new IllegalStateException("explicit copied-world crash probe property required");
                var positions = java.util.List.of(pos, new ChunkPos(Math.addExact(x, 1), z));
                var originals = new java.util.ArrayList<CompoundTag>();
                var candidates = new java.util.ArrayList<CompoundTag>();
                var digests = new com.google.gson.JsonArray();
                for (int ordinal = 0; ordinal < positions.size(); ordinal++) {
                    var p = positions.get(ordinal);
                    if (((com.yucareux.tellus.worldgen.persistence.TellusNativeChunkMapAccess)level.getChunkSource().chunkMap)
                        .tellus$hasNativeHolder(p.toLong())) throw new IllegalStateException("crash probe targets must stay unloaded");
                    CompoundTag nativeDisk = persistence.readExistingNBT(p).orElseThrow(() -> new IllegalStateException("existing native crash baseline required"));
                    Path original = directory.resolve(id + "-batch-original-" + ordinal + ".snbt");
                    if (phase.equals("crash_batch")) {
                        if (Files.exists(original)) throw new IllegalStateException("batch original already exists");
                        forceProbeFile(original, nativeDisk.toString());
                        CompoundTag candidate = nativeDisk.copy();
                        boolean changed = false;
                        for (var sectionTag : candidate.getList("sections", 10)) {
                            var section = (CompoundTag)sectionTag;
                            for (var paletteTag : section.getCompound("block_states").getList("palette", 10)) {
                                var palette = (CompoundTag)paletteTag;
                                if (palette.getString("Name").equals("minecraft:stone")) {
                                    palette.putString("Name", "minecraft:gold_block"); changed = true; break;
                                }
                            }
                            if (changed) break;
                        }
                        if (!changed) throw new IllegalStateException("real stone palette required for crash mutation");
                        candidates.add(candidate); originals.add(nativeDisk);
                    } else {
                        if (Files.size(original) > MAX_SNBT_BYTES) throw new IllegalStateException("batch original exceeds probe budget");
                        var expected = TagParser.parseTag(Files.readString(original, StandardCharsets.UTF_8));
                        if (!TellusNativeChunkPersistence.sameNBT(expected, nativeDisk, Set.of()))
                            throw new IllegalStateException("recovered complete native disk NBT differs at " + p);
                        originals.add(expected);
                    }
                    digests.add(TellusNativeChunkPersistence.digest(originals.get(ordinal)));
                }
                result.addProperty("diskReadBeforeDemandLoad", true);
                result.add("batchOriginalDigests", digests);
                result.addProperty("nativeTargetsUnloaded", true);
                if (phase.equals("crash_batch")) {
                    var entries = java.util.List.of(new TellusNativeChunkPersistence.BatchEntry(positions.get(0), candidates.get(0)),
                        new TellusNativeChunkPersistence.BatchEntry(positions.get(1), candidates.get(1)));
                    Path crashOutput = output;
                    persistence.commitBatch(entries, (ordinal, p, point) -> {
                        if (ordinal == 0 && point == TellusNativeChunkPersistence.Point.AFTER_READBACK) {
                            if (!level.noSave || !com.yucareux.tellus.worldgen.persistence.TellusNativeSaveFence.deferEngineSave(level))
                                throw new IllegalStateException("native save fence was not active");
                            level.getChunkSource().chunkMap.flushWorker();
                            var first = level.getChunkSource().chunkMap.read(positions.get(0)).join().orElseThrow();
                            var second = level.getChunkSource().chunkMap.read(positions.get(1)).join().orElseThrow();
                            if (!TellusNativeChunkPersistence.sameNBT(candidates.get(0), first, Set.of())
                                || !TellusNativeChunkPersistence.sameNBT(originals.get(1), second, Set.of()))
                                throw new IllegalStateException("native batch crash boundary did not contain one changed and one original chunk");
                            result.addProperty("firstNativeChunkWritten", true);
                            result.addProperty("secondNativeChunkUntouched", true);
                            result.addProperty("nativeSaveFenceActive", true);
                            result.addProperty("crashedServerPid", ProcessHandle.current().pid());
                            result.addProperty("status", "CRASH_BOUNDARY");
                            try { forceProbeFile(crashOutput, JSON.toJson(result) + "\n"); }
                            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                            LOGGER.info("TELLUS_NATIVE_BATCH_CRASH_BOUNDARY result={}", crashOutput);
                            Runtime.getRuntime().halt(86);
                        }
                    });
                    throw new IllegalStateException("native crash boundary was not reached");
                }
                var startup = com.yucareux.tellus.worldgen.persistence.TellusNativeSaveFence.startupDiagnostics(level);
                result.add("startupRecoveryDiagnostics", JSON.toJsonTree(startup));
                if (phase.equals("recover_batch") && startup.stream().noneMatch(s -> s.startsWith("recovered native batch journal")))
                    throw new IllegalStateException("batch recovery did not execute before prepareLevels");
                result.addProperty("batchCompleteOriginalNBTEqual", true);
                result.addProperty("engineSavingResumed", !level.noSave
                    && !com.yucareux.tellus.worldgen.persistence.TellusNativeSaveFence.deferEngineSave(level));
                if (!result.get("engineSavingResumed").getAsBoolean()) throw new IllegalStateException("native saving remained fenced after recovery");
                return complete(source, phase, result, output);
            }
            if (phase.equals("generate")) {
                if (persistence.readExistingNBT(pos).isPresent())
                    throw new IllegalStateException("generation probe requires an absent disk chunk; existing terrain is not generation evidence");
                result.addProperty("diskReadBeforeDemandLoad", true);
                result.addProperty("targetAbsentBeforeGeneration", true);
                result.addProperty("sourceBoundary", "new native FULL generation on the copied real world/cache; no synthetic sampler");
                if (Boolean.getBoolean("tellus.persistence.probe.awaitDetails")) {
                    if (!(level.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator))
                        throw new IllegalStateException("settled real detail probe requires Cube-Net");
                    generator.sampleFeatures(pos, 96.0, com.yucareux.tellus.world.data.osm.OsmQueryMode.BLOCKING).orElseThrow();
                    var details = generator.sampleFeatures(pos, 96.0, com.yucareux.tellus.world.data.osm.OsmQueryMode.NON_BLOCKING).orElseThrow();
                    if (details.buildings().hadCacheMiss() || details.roads().hadCacheMiss() || details.roadAreas().hadCacheMiss()
                        || details.water().coverageStatus() != com.yucareux.tellus.world.data.osm.TellusOsmWaterSource.CoverageStatus.COMPLETE)
                        throw new IllegalStateException("real detail sources are not settled before A/B demand generation");
                    JsonObject inputs = new JsonObject();
                    inputs.add("buildingIds", JSON.toJsonTree(details.buildings().features().stream().map(f -> String.valueOf(f.buildingId())).sorted().toList()));
                    inputs.add("roadIds", JSON.toJsonTree(details.roads().features().stream().map(f -> f.wayId()).sorted().toList()));
                    inputs.add("waterIds", JSON.toJsonTree(details.water().features().stream().map(f -> f.featureId()).sorted().toList()));
                    inputs.addProperty("roadAreaCount", details.roadAreas().features().size());
                    result.add("settledNativeDetailInputs", inputs);
                }
                long started = System.nanoTime();
                ChunkAccess generated = ChunkBenchmarkVersionCompat.requestFullChunk(level.getChunkSource(), x, z);
                if (generated == null) throw new IllegalStateException("native generation returned no FULL chunk");
                result.addProperty("generationNanos", System.nanoTime() - started);
                if (Boolean.getBoolean("tellus.persistence.probe.structureMixins")) {
                    result.add("monumentNativeMixin", TellusNativeStructureMixinProbe.verify(level));
                    result.addProperty("worldGenerateStructures", source.getServer().getWorldData().worldGenOptions().generateStructures());
                    result.addProperty("naturalStartCountInGeneratedChunk", generated.getAllStarts().values().stream()
                        .filter(start -> start != null && start.isValid()).count());
                    result.addProperty("naturalStructureAcceptance", false);
                }
                if (level.getChunkSource().getGenerator() instanceof com.yucareux.tellus.worldgen.runtime.TellusNativeProductionOwner owner) {
                    var pipeline = owner.nativeProductionPipeline();
                    result.addProperty("nativeProductionMode", pipeline.mode().name());
                    var observation = pipeline.observation(pos);
                    result.add("nativeProductionObservation", JSON.toJsonTree(observation));
                    if (pipeline.enabled() && (!observation.stages().equals(java.util.List.of("BIOMES", "TERRAIN", "CARVERS", "DECORATION"))
                        || !observation.failures().isEmpty()))
                        throw new IllegalStateException("real native generation did not complete all four unified production stages");
                }
                CompoundTag expected = persistence.snapshot(generated);
                result.addProperty("generatedBlockDigest", blockDigest(generated));
                result.addProperty("generatedBiomeDigest", biomeDigest(generated));
                result.addProperty("generatedNativeNBTDigest", TellusNativeChunkPersistence.digest(expected, SAVE_CLOCKS));
                result.add("generatedLifecycleCounts", lifecycleCounts(expected));
                result.addProperty("generatedNativeStatus", expected.getString("Status"));
                result.addProperty("generatedDataVersion", expected.getInt("DataVersion"));
                generated.setUnsaved(true);
                source.getServer().saveEverything(false, true, true);
                CompoundTag saved = persistence.readExistingNBT(pos).orElseThrow(() ->
                    new IllegalStateException("new generated chunk was not saved through the native queue"));
                if (!TellusNativeChunkPersistence.sameNBT(expected, saved, SAVE_CLOCKS))
                    throw new IllegalStateException("new generated FULL chunk changed through native save");
                result.addProperty("generatedDiskMatchesMaterialized", true);
                Files.writeString(directory.resolve(id + "-generated.snbt"), saved.toString(), StandardCharsets.UTF_8);
                return complete(source, phase, result, output);
            }
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
                if (Boolean.getBoolean("tellus.persistence.probe.populateLifecycle")) {
                    populateLiveLifecycle(level, chunk, changed);
                    // Fault recovery must restore a nonempty on-disk baseline, not
                    // merely the old terrain-only tag. Use the engine's save path.
                    source.getServer().saveEverything(false, true, true);
                    requireLiveLifecycle(persistence.readExistingNBT(pos).orElseThrow(), changed);
                    result.addProperty("liveLifecyclePayloadsPopulated", true);
                } else result.addProperty("liveLifecyclePayloadsPopulated", false);
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
                if (Boolean.getBoolean("tellus.persistence.probe.populateLifecycle")) {
                    requireLiveLifecycle(expected, changed);
                    result.add("expectedLifecycleCounts", lifecycleCounts(expected));
                }
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
                boolean liveLifecycle = capture.has("liveLifecyclePayloadsPopulated")
                    && capture.get("liveLifecyclePayloadsPopulated").getAsBoolean();
                BlockPos lifecycleAnchor = new BlockPos(capture.get("changedX").getAsInt(), capture.get("changedY").getAsInt(), capture.get("changedZ").getAsInt());
                if (liveLifecycle) {
                    requireLiveLifecycle(diskBeforeLoad, lifecycleAnchor);
                    result.add("restartedDiskLifecycleCounts", lifecycleCounts(diskBeforeLoad));
                }
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
                boolean nativeLifecycleEqual = false;
                if (liveLifecycle) {
                    // Native FULL promotion consumes PostProcessing offsets. Do
                    // not hide this by excluding the field: check the exact
                    // expected transition and compare every other root field.
                    ((LevelChunk) reloaded).postProcessGeneration();
                    materialized = persistence.snapshot(reloaded);
                    CompoundTag afterPostProcessing = expected.copy();
                    ListTag consumed = new ListTag();
                    for (int i = 0; i < expected.getList("PostProcessing", 9).size(); i++) consumed.add(new ListTag());
                    afterPostProcessing.put("PostProcessing", consumed);
                    nativeLifecycleEqual = TellusNativeChunkPersistence.sameNBT(afterPostProcessing, materialized, SAVE_CLOCKS);
                    result.addProperty("materializedNativeLifecycleEqual", nativeLifecycleEqual);
                    result.add("materializedLifecycleCounts", lifecycleCounts(materialized));
                    if (!reloaded.getBlockState(lifecycleAnchor.offset(2, 0, 1)).equals(Blocks.WATER.defaultBlockState())
                        || !(reloaded.getBlockEntity(lifecycleAnchor.offset(1, 0, 1)) instanceof ChestBlockEntity chest)
                        || !chest.getItem(0).is(Items.DIAMOND) || chest.getItem(0).getCount() != 3)
                        throw new IllegalStateException("materialized live chest inventory or water cell differs");
                    result.addProperty("liveChestInventoryAndWaterVerified", true);
                }
                if (!materializedEqual && !nativeLifecycleEqual) {
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
            return complete(source, phase, result, output);
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

    private static int complete(CommandSourceStack source, String phase, JsonObject result, Path output) throws java.io.IOException {
        result.addProperty("status", "PASS");
        Files.writeString(output, JSON.toJson(result) + "\n", StandardCharsets.UTF_8);
        String marker = "TELLUS_PERSISTENCE_" + phase.toUpperCase(java.util.Locale.ROOT) + "_PASS";
        LOGGER.info("{} result={}", marker, output);
        source.sendSuccess(() -> Component.literal(marker), false);
        // No extra tick between the same-tick snapshot and the native shutdown save.
        if (Boolean.getBoolean("tellus.persistence.probe.populateLifecycle") || phase.equals("generate") || phase.equals("sea_codec") || phase.endsWith("_batch"))
            source.getServer().halt(false);
        return 1;
    }

    private static void forceProbeFile(Path file, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
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

    private static void populateLiveLifecycle(ServerLevel level, ChunkAccess chunk, BlockPos anchor) {
        BlockPos entityPos = anchor.offset(1, 0, 1);
        BlockState chest = Blocks.CHEST.defaultBlockState();
        MinecraftVersionCompat.setBlockState(chunk, entityPos, chest);
        BlockEntity entity = BlockEntityType.CHEST.create(entityPos, chest);
        if (entity == null) throw new IllegalStateException("native chest block entity factory returned null");
        chunk.setBlockEntity(entity);
        ((ChestBlockEntity) entity).setItem(0, new ItemStack(Items.DIAMOND, 3));
        BlockPos waterPos = anchor.offset(2, 0, 1);
        MinecraftVersionCompat.setBlockState(chunk, waterPos, Blocks.WATER.defaultBlockState());
        level.scheduleTick(entityPos, Blocks.CHEST, 1_000_000);
        level.scheduleTick(waterPos, Fluids.WATER, 1_000_000);
        // FULL chunks do not support markPosForPostprocessing; this is the
        // native packed-offset API used by their serialized pending markers.
        short marker = ProtoChunk.packOffsetCoordinates(anchor);
        chunk.addPackedPostProcess(marker, chunk.getSectionIndex(anchor.getY()));
        chunk.addPackedPostProcess(marker, chunk.getSectionIndex(anchor.getY()));
        chunk.setUnsaved(true);
    }

    private static JsonObject lifecycleCounts(CompoundTag tag) {
        JsonObject value = new JsonObject();
        for (String field : java.util.List.of("block_entities", "block_ticks", "fluid_ticks"))
            value.addProperty(field, tag.getList(field, 10).size());
        int markers = 0;
        ListTag lists = tag.getList("PostProcessing", 9);
        for (int i = 0; i < lists.size(); i++) markers += lists.getList(i).size();
        value.addProperty("postProcessingOffsets", markers);
        return value;
    }

    private static void requireLiveLifecycle(CompoundTag tag, BlockPos anchor) {
        JsonObject counts = lifecycleCounts(tag);
        for (String field : java.util.List.of("block_entities", "block_ticks", "fluid_ticks"))
            if (counts.get(field).getAsInt() < 1) throw new IllegalStateException("live payload missing: " + field);
        ListTag markers = tag.getList("PostProcessing", 9).getList(Math.floorDiv(anchor.getY(), 16) - tag.getInt("yPos"));
        short packed = ProtoChunk.packOffsetCoordinates(anchor); int occurrences = 0;
        for (int i = 0; i < markers.size(); i++) if (markers.getShort(i) == packed) occurrences++;
        if (occurrences != 2) throw new IllegalStateException("duplicate native post-processing markers were lost");
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
