package com.yucareux.tellus.client;

import com.yucareux.tellus.network.CubeNetTransitionPreparePayload;
import com.yucareux.tellus.network.CubeNetTransitionReadyPayload;
import com.yucareux.tellus.network.CubeNetTransitionChunksReceivedPayload;
import com.yucareux.tellus.network.CubeNetTransitionCommitPayload;
import com.yucareux.tellus.network.CubeNetTransitionMotionPayload;
import com.yucareux.tellus.TellusCommon;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Client-side staging acknowledgement for a native cube-net rebase. */
@Environment(EnvType.CLIENT)
public final class CubeNetClientTransition {
   private static final int MAX_WAIT_TICKS = 1200;
   private static final int MOTION_HISTORY_TICKS = 3;
   private static final int MAX_MOTION_AGE_TICKS = 6;
   private static final double MIN_MOTION_SAMPLE = 0.01;
   private static final double MAX_MOTION_SAMPLE = 0.8;
   private static final double MIN_RESTORE_RATIO = 0.75;
   private static volatile Pending pending;
   private static PostCommitMotionTrace postCommitMotionTrace;
   private static OutboundMotionTrace outboundMotionTrace;
   private static int traceFrames;
   /** Cumulative target-to-source chart rotation used by directional shading. */
   private static volatile int shadeQuarterTurns;
   private static final ThreadLocal<BlockPos> shadeRenderPosition = new ThreadLocal<>();

   private CubeNetClientTransition() { }

   /** Returns the chart-relative direction whose vanilla face factor should be sampled. */
   public static Direction shadeDirection(Direction direction) {
      BlockPos position = shadeRenderPosition.get();
      int turns = shadeQuarterTurns;
      Pending current = pending;
      if (current != null && position != null
         && current.renderContains(position.getX() >> 4, position.getZ() >> 4)) {
         turns = current.targetShadeQuarterTurns;
      }
      for (int i = 0; i < Math.floorMod(turns, 4); i++) direction = clockwise(direction);
      return direction;
   }

   /** Marks the block position currently being tessellated on this worker thread. */
   public static void beginShadeRender(BlockPos position) {
      shadeRenderPosition.set(position);
   }

   public static void endShadeRender() {
      shadeRenderPosition.remove();
   }

   /** A disconnect starts a fresh chart; a cancelled transfer keeps the current chart. */
   public static void resetShadeOrientation() {
      shadeQuarterTurns = 0;
      shadeRenderPosition.remove();
   }

   private static Direction clockwise(Direction direction) {
      return switch (direction) {
         case NORTH -> Direction.EAST;
         case EAST -> Direction.SOUTH;
         case SOUTH -> Direction.WEST;
         case WEST -> Direction.NORTH;
         default -> direction;
      };
   }

   public static void prepare(CubeNetTransitionPreparePayload payload) {
      Pending existing = pending;
      if (existing != null && existing.area.transitionId().equals(payload.transitionId())
         && existing.area.centerChunkX() == payload.centerChunkX()
         && existing.area.centerChunkZ() == payload.centerChunkZ() && existing.area.radius() == payload.radius()) {
         existing.holdMovement |= payload.holdMovement();
         return;
      }
      reset();
      Minecraft client = Minecraft.getInstance();
      if (client.level == null || client.player == null) return;
      if (payload.radius() < client.options.getEffectiveRenderDistance() + 1) {
         TellusCommon.LOGGER.warn("Cube-net target area does not cover the configured view: radius={} view={}",
            payload.radius(), client.options.getEffectiveRenderDistance());
         return;
      }
      ClientChunkCache cache = client.level.getChunkSource();
      ChunkPos source = client.player.chunkPosition();
      pending = new Pending(payload, cache, client.options.getEffectiveRenderDistance());
      TellusCommon.LOGGER.info("Cube-net client preparing {} source={} target=({}, {})",
         payload.transitionId(), source, payload.centerChunkX(), payload.centerChunkZ());
   }

   public static LevelChunk stagedChunk(ClientChunkCache cache, int x, int z) {
      Pending current = pending;
      return current == null || current.cache != cache ? null : current.remote.get(ChunkPos.asLong(x, z));
   }

   public static boolean stageCenter(ClientChunkCache cache, int x, int z) {
      Pending current = pending;
      return current != null && current.cache == cache
         && x == current.area.centerChunkX() && z == current.area.centerChunkZ();
   }

   public static LevelChunk stageChunk(ClientChunkCache cache, int x, int z, FriendlyByteBuf data,
      CompoundTag heights, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities) {
      Pending current = pending;
      Minecraft client = Minecraft.getInstance();
      if (current == null || current.cache != cache || !current.contains(x, z) || client.level == null) return null;
      ChunkPos pos = new ChunkPos(x, z);
      LevelChunk chunk = current.remote.computeIfAbsent(pos.toLong(), key -> new LevelChunk(client.level, pos));
      chunk.replaceWithPacketData(data, heights, blockEntities);
      client.level.onChunkLoaded(pos);
      return chunk;
   }

   public static void receivedChunk(ClientboundLevelChunkWithLightPacket packet) {
      Pending current = pending;
      if (current != null && current.contains(packet.getX(), packet.getZ())) {
         current.packets.put(ChunkPos.asLong(packet.getX(), packet.getZ()), packet);
      }
   }

   public static boolean holdsVehicle(Entity vehicle) {
      Minecraft client = Minecraft.getInstance();
      Pending current = pending;
      return current != null && current.holdMovement && !current.readySent
         && client.player != null && client.player.getRootVehicle() == vehicle;
   }

   /**
    * A player handoff is held from the server's source snapshot until the
    * commit packet installs the destination chart and pose.  Local prediction
    * must be held for that whole interval; otherwise gravity or input moves
    * the client away from the snapshot while the server keeps restoring it.
    */
   public static boolean holdsPlayer() {
      Pending current = pending;
      return current != null && current.holdMovement;
   }

   public static void cancel(java.util.UUID id) {
      if (pending != null && pending.area.transitionId().equals(id)) reset();
   }

   public static void tick(Minecraft client) {
      tracePostCommitMotion(client);
      Pending current = pending;
      if (current == null) return;
      if (client.level == null || client.player == null || client.getConnection() == null
         || client.level.getChunkSource() != current.cache
         || client.options.getEffectiveRenderDistance() != current.viewDistance) {
         reset();
         return;
      }
      if (current.holdMovement) {
         // Keep local gravity from accumulating while the server owns the
         // source snapshot. The authoritative velocity/fall distance is
         // restored by the commit payload.
         client.player.setDeltaMovement(Vec3.ZERO);
         client.player.fallDistance = 0.0f;
      }
      samplePlayerMotion(current, client.player);
      // Once this client has acknowledged the compiled chart, the staged view
      // is part of the pending handoff. Do not let the loading watchdog discard
      // it while the player pauses in the apron; the server owns the eventual
      // commit/cancel decision.
      if (current.readySent) return;
      if (--current.remainingTicks <= 0) {
         TellusCommon.LOGGER.warn("Cube-net client staging timed out: {}", current.area.transitionId());
         reset();
         return;
      }
      for (int dz = -current.area.radius(); dz <= current.area.radius(); dz++) {
         for (int dx = -current.area.radius(); dx <= current.area.radius(); dx++) {
            if (!current.remote.containsKey(ChunkPos.asLong(current.area.centerChunkX() + dx,
               current.area.centerChunkZ() + dz))) {
               if (current.remainingTicks % 200 == 0) TellusCommon.LOGGER.info(
                  "Cube-net client waiting for chunk {} ({}, {}) remote={} packets={}", current.area.transitionId(),
                  current.area.centerChunkX() + dx, current.area.centerChunkZ() + dz,
                  current.remote.size(), current.packets.size());
               return;
            }
         }
      }
      if (!current.chunksReceivedSent && ClientPlayNetworking.canSend(CubeNetTransitionChunksReceivedPayload.TYPE)) {
         ClientPlayNetworking.send(new CubeNetTransitionChunksReceivedPayload(current.area.transitionId()));
         current.chunksReceivedSent = true;
         TellusCommon.LOGGER.info("Cube-net client received all destination chunks {} count={}",
            current.area.transitionId(), current.remote.size());
      }
      // Light packet work is queued by vanilla after the chunk data. Allow a
      // complete client tick, then build with the normal region/light sampler.
      if (++current.dataReadyTicks < 2 || !client.level.isLightUpdateQueueEmpty()) return;
      if (current.staged == null) {
         current.dataReadyNanos = System.nanoTime();
         current.staged = new ViewArea(client.levelRenderer.getChunkRenderDispatcher(), client.level,
            current.viewDistance, client.levelRenderer);
         current.staged.repositionCamera(current.area.centerChunkX() * 16.0 + 8,
            current.area.centerChunkZ() * 16.0 + 8);
         for (var section : current.staged.chunks) {
            if (current.renderContains(section.getOrigin().getX() >> 4, section.getOrigin().getZ() >> 4)) {
               current.sections.add(section);
            }
         }
         TellusCommon.LOGGER.info("Cube-net client compiling {} sections={}",
            current.area.transitionId(), current.sections.size());
      }
      // Keep native workers busy without putting thousands of rebuild tasks
      // ahead of the source chart. Region snapshot creation stays on this thread.
      long deadline = System.nanoTime() + 3_000_000L;
      long batchStarted = System.nanoTime();
      RenderRegionCache regions = current.regions;
      var dispatcher = client.levelRenderer.getChunkRenderDispatcher();
      current.inFlight.removeIf(section -> section.getCompiledChunk()
         != ChunkRenderDispatcher.CompiledChunk.UNCOMPILED);
      for (var section : current.inFlight) {
         if (section.isDirty()) {
            section.rebuildChunkAsync(dispatcher, regions);
            section.setNotDirty();
         }
      }
      Vec3 camera = dispatcher.getCameraPosition();
      dispatcher.setCamera(new Vec3(current.area.centerChunkX() * 16.0 + 8,
         client.player.getY(), current.area.centerChunkZ() * 16.0 + 8));
      try {
         do {
            if (current.nextSection >= current.sections.size() || current.inFlight.size() >= 64) break;
            var section = current.sections.get(current.nextSection++);
            section.rebuildChunkAsync(dispatcher, regions);
            section.setNotDirty();
            current.inFlight.add(section);
         } while (System.nanoTime() < deadline);
      } finally {
         dispatcher.setCamera(camera);
         current.compileNanos += System.nanoTime() - batchStarted;
         current.compileBatches++;
      }
      if (current.nextSection < current.sections.size() || !current.inFlight.isEmpty()) {
         if (current.remainingTicks % 200 == 0) TellusCommon.LOGGER.info(
            "Cube-net client compile progress {} completed={}/{} cpuMs={}", current.area.transitionId(),
            current.nextSection - current.inFlight.size(), current.sections.size(), current.compileNanos / 1_000_000L);
         return;
      }
      if (current.compileFinishedNanos == 0) current.compileFinishedNanos = System.nanoTime();
      long uncompiled = current.sections.stream().filter(section ->
         section.getCompiledChunk() == ChunkRenderDispatcher.CompiledChunk.UNCOMPILED).count();
      if (uncompiled != 0) {
         if (current.remainingTicks % 200 == 0) TellusCommon.LOGGER.info(
            "Cube-net client waiting for mesh uploads {} uncompiled={}", current.area.transitionId(), uncompiled);
         return;
      }
      if (ClientPlayNetworking.canSend(CubeNetTransitionReadyPayload.TYPE)) {
         ClientPlayNetworking.send(new CubeNetTransitionReadyPayload(current.area.transitionId()));
         current.readySent = true;
         TellusCommon.LOGGER.info("Cube-net client render staging ready {} sections={} elapsedMs={}",
            current.area.transitionId(), current.sections.size(), (System.nanoTime() - current.startedNanos) / 1_000_000L);
         TellusCommon.LOGGER.info("Cube-net client staging phases {} dataLightMs={} compileWallMs={} compileCpuMs={} uploadWaitMs={} batches={}",
            current.area.transitionId(), (current.dataReadyNanos - current.startedNanos) / 1_000_000L,
            (current.compileFinishedNanos - current.dataReadyNanos) / 1_000_000L, current.compileNanos / 1_000_000L,
            (System.nanoTime() - current.compileFinishedNanos) / 1_000_000L, current.compileBatches);
      }
   }

   /** Vanilla workers must sort translucent target geometry in its own chart. */
   public static Vec3 compilationCamera(ChunkRenderDispatcher.RenderChunk section, Vec3 ordinary) {
      Pending current = pending;
      if (current == null || !current.renderContains(section.getOrigin().getX() >> 4,
         section.getOrigin().getZ() >> 4)) return ordinary;
      return current.compileCamera;
   }

   /** Called in the player position packet's main-thread handler, before a frame can render. */
   public static void playerPositionApplied(Minecraft client) {
      Pending current = pending;
      if (current == null || !current.readySent || current.staged == null || client.player == null
         || client.level == null || client.level.getChunkSource() != current.cache
         || !current.contains(client.player.chunkPosition().x, client.player.chunkPosition().z)) return;
      commitPrepared(client, current, client.player.position(), client.player.getYRot(),
         client.player.getXRot(), client.player.getDeltaMovement(), client.player.fallDistance, false);
   }

   /**
    * Installs the chart and authoritative pose from the custom server commit
    * packet on the client main thread.  This is the atomic path; the vanilla
    * position packet remains available for older clients and failure fallback.
    */
   public static void commit(CubeNetTransitionCommitPayload payload) {
      Minecraft client = Minecraft.getInstance();
      Pending current = pending;
      if (current == null || !current.readySent || current.staged == null || client.player == null
         || client.level == null || client.level.getChunkSource() != current.cache
         || !current.area.transitionId().equals(payload.transitionId())
         || !current.contains((int)Math.floor(payload.x() / 16.0), (int)Math.floor(payload.z() / 16.0))) {
         TellusCommon.LOGGER.warn("Cube-net custom commit ignored id={} pending={}",
            payload.transitionId(), current == null ? null : current.area.transitionId());
         return;
      }
      client.player.absMoveTo(payload.x(), payload.y(), payload.z(), payload.yaw(), payload.pitch());
      client.player.setDeltaMovement(payload.velocity());
      client.player.fallDistance = payload.fallDistance();
      client.player.setOldPosAndRot();
      commitPrepared(client, current, client.player.position(), client.player.getYRot(),
         client.player.getXRot(), client.player.getDeltaMovement(), client.player.fallDistance, true);
      sendInitialMotion(client, payload.transitionId());
      if (client.player.connection != null) {
         client.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
            client.player.getX(), client.player.getY(), client.player.getZ(),
            client.player.getYRot(), client.player.getXRot(), client.player.onGround()));
      }
   }

   private static void commitPrepared(Minecraft client, Pending current, Vec3 position,
      float yaw, float pitch, Vec3 velocity, float fallDistance, boolean customCommit) {
      // Promote through the ordinary packet path so the native cache owns all
      // data after commit. Source reception stayed active until this point.
      pending = null;
      current.cache.updateViewCenter(current.area.centerChunkX(), current.area.centerChunkZ());
      for (var packet : current.packets.values()) client.getConnection().handleLevelChunkWithLight(packet);
      ((CubeNetRenderStaging)client.levelRenderer).tellus$installStagedView(current.staged);
      current.staged = null; // ownership passes to LevelRenderer
      int previousShade = shadeQuarterTurns;
      shadeQuarterTurns = current.targetShadeQuarterTurns;
      traceFrames = 8;
      TellusCommon.LOGGER.info("Cube-net client render chart committed {} player={} vehicle={} shadingTurns={} previous={}",
         current.area.transitionId(), position, client.player.getVehicle(), shadeQuarterTurns, previousShade);
      restoreMeasuredMotion(client, current);
      postCommitMotionTrace = new PostCommitMotionTrace(current.area.transitionId(), position);
      outboundMotionTrace = new OutboundMotionTrace(current.area.transitionId(), customCommit);
      TellusCommon.LOGGER.info("Cube-net client pose commit {} mode={} pose=({}, {}, {}) yaw={} pitch={} velocity=({}, {}, {})",
         current.area.transitionId(), customCommit ? "custom" : "vanilla", position.x, position.y, position.z,
         yaw, pitch, velocity.x, velocity.y, velocity.z);
   }

   /**
    * Release the server's bounded seam correction gate as soon as the client
    * installs the atomic commit, rather than waiting for the next ordinary
    * LocalPlayer.sendPosition call. The movement hook below remains a fallback
    * if this direct sample cannot be queued.
    */
   private static void sendInitialMotion(Minecraft client, java.util.UUID transitionId) {
      OutboundMotionTrace trace = outboundMotionTrace;
      if (trace == null || !trace.customCommit || trace.firstMotionSent || client.player == null
         || client.player.connection == null
         || !ClientPlayNetworking.canSend(CubeNetTransitionMotionPayload.TYPE)) return;
      Vec3 position = client.player.position();
      Vec3 velocity = client.player.getDeltaMovement();
      ClientPlayNetworking.send(new CubeNetTransitionMotionPayload(
         transitionId, position, client.player.getYRot(), client.player.getXRot(),
         velocity, client.player.onGround()));
      trace.firstMotionSent = true;
      TellusCommon.LOGGER.info(
         "Cube-net client sent first custom motion at commit {} gameTime={} position=({}, {}, {}) velocity=({}, {}, {})",
         transitionId, client.level == null ? -1L : client.level.getGameTime(),
         position.x, position.y, position.z, velocity.x, velocity.y, velocity.z);
   }

   /** Called at the client movement packet send boundary while a trace is active. */
   public static void outboundMovementTrace(Minecraft client, String phase) {
      OutboundMotionTrace trace = outboundMotionTrace;
      if (trace == null || client.player == null || client.level == null) return;
      Vec3 position = client.player.position();
      Vec3 velocity = client.player.getDeltaMovement();
      TellusCommon.LOGGER.info(
         "Cube-net client outbound movement {} phase={} sample={} gameTime={} position=({}, {}, {}) "
            + "velocity=({}, {}, {}) keys={}/{}/{}/{} onGround={}",
         trace.transitionId, phase, trace.samples + 1, client.level.getGameTime(),
         position.x, position.y, position.z, velocity.x, velocity.y, velocity.z,
         client.options.keyUp.isDown(), client.options.keyDown.isDown(),
         client.options.keyLeft.isDown(), client.options.keyRight.isDown(), client.player.onGround());
      if ("head".equals(phase) && trace.customCommit && !trace.firstMotionSent
         && ClientPlayNetworking.canSend(CubeNetTransitionMotionPayload.TYPE)) {
         // Keep a fallback for clients where the immediate commit send was not
         // available; otherwise the server gate already received its sample.
         ClientPlayNetworking.send(new CubeNetTransitionMotionPayload(
            trace.transitionId, position, client.player.getYRot(), client.player.getXRot(),
            velocity, client.player.onGround()));
         trace.firstMotionSent = true;
         TellusCommon.LOGGER.info("Cube-net client sent first custom motion before vanilla movement {} position=({}, {}, {})",
            trace.transitionId, position.x, position.y, position.z);
      }
      if ("tail".equals(phase) && ++trace.samples >= 12) {
         outboundMotionTrace = null;
      }
   }

   private static void tracePostCommitMotion(Minecraft client) {
      PostCommitMotionTrace trace = postCommitMotionTrace;
      if (trace == null || client.player == null) return;
      Vec3 position = client.player.position();
      Vec3 velocity = client.player.getDeltaMovement();
      TellusCommon.LOGGER.info(
         "Cube-net client post-commit motion {} tick={} position=({}, {}, {}) delta=({}, {}, {}) "
            + "velocity=({}, {}, {}) keys={}/{}/{}/{} onGround={}",
         trace.transitionId, trace.samples + 1, position.x, position.y, position.z,
         position.x - trace.lastPosition.x, position.y - trace.lastPosition.y, position.z - trace.lastPosition.z,
         velocity.x, velocity.y, velocity.z, client.options.keyUp.isDown(), client.options.keyDown.isDown(),
         client.options.keyLeft.isDown(), client.options.keyRight.isDown(), client.player.onGround());
      trace.lastPosition = position;
      if (++trace.samples >= 12) postCommitMotionTrace = null;
   }

   private static void samplePlayerMotion(Pending current, Entity player) {
      current.motionTicks++;
      if (player.isPassenger()) return;
      Vec3 position = player.position();
      if (current.lastMotionPosition != null) {
         Vec3 delta = position.subtract(current.lastMotionPosition);
         double horizontalSpeed = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
         if (horizontalSpeed >= MIN_MOTION_SAMPLE && horizontalSpeed <= MAX_MOTION_SAMPLE) {
            current.motionHistory[current.nextMotionSample] = new Vec3(delta.x, 0.0, delta.z);
            current.nextMotionSample = (current.nextMotionSample + 1) % MOTION_HISTORY_TICKS;
            current.motionSamples = Math.min(current.motionSamples + 1, MOTION_HISTORY_TICKS);
            current.lastMotionTick = current.motionTicks;
            current.stationarySamples = 0;
         } else if (horizontalSpeed < MIN_MOTION_SAMPLE) {
            if (++current.stationarySamples >= 2) {
               current.motionSamples = 0;
               current.nextMotionSample = 0;
               current.lastMotionTick = 0;
            }
         }
      }
      current.lastMotionPosition = position;
      current.lastMotionYaw = player.getYRot();
   }

   private static void restoreMeasuredMotion(Minecraft client, Pending current) {
      if (current.motionSamples == 0 || client.player == null || client.player.isPassenger()
         || current.motionTicks - current.lastMotionTick > MAX_MOTION_AGE_TICKS
         || !(client.options.keyUp.isDown() || client.options.keyDown.isDown()
            || client.options.keyLeft.isDown() || client.options.keyRight.isDown())) return;
      Vec3 measured = Vec3.ZERO;
      for (int i = 0; i < current.motionSamples; i++) measured = measured.add(current.motionHistory[i]);
      measured = measured.scale(1.0 / current.motionSamples);
      double measuredSpeed = Math.sqrt(measured.x * measured.x + measured.z * measured.z);
      if (measuredSpeed < MIN_MOTION_SAMPLE) return;

      Vec3 existing = client.player.getDeltaMovement();
      double existingSpeed = Math.sqrt(existing.x * existing.x + existing.z * existing.z);
      if (existingSpeed >= measuredSpeed * MIN_RESTORE_RATIO) {
         TellusCommon.LOGGER.info(
            "Cube-net client motion preserved {} measuredSpeed={} existingSpeed={}",
            current.area.transitionId(), measuredSpeed, existingSpeed);
         return;
      }

      double angle = Math.toRadians(Mth.wrapDegrees(client.player.getYRot() - current.lastMotionYaw));
      double cos = Math.cos(angle), sin = Math.sin(angle);
      double x = measured.x * cos - measured.z * sin;
      double z = measured.x * sin + measured.z * cos;
      client.player.setDeltaMovement(x, existing.y, z);
      TellusCommon.LOGGER.info(
         "Cube-net client restored measured motion {} samples={} yawDelta={} measured=({}, {}, {}) "
            + "existingSpeed={} restored=({}, {}, {}) sampleAgeTicks={}",
         current.area.transitionId(), current.motionSamples,
         Mth.wrapDegrees(client.player.getYRot() - current.lastMotionYaw),
         measured.x, measured.y, measured.z, existingSpeed, x, existing.y, z,
         current.motionTicks - current.lastMotionTick);
   }

   public static void vehicleMoveStarting(Minecraft client, ClientboundMoveVehiclePacket packet) {
      Pending current = pending;
      if (current == null || !current.readySent || client.player == null
         || !current.contains((int)Math.floor(packet.getX() / 16.0), (int)Math.floor(packet.getZ() / 16.0))) return;
      current.movingRoot = client.player.getRootVehicle();
      current.rootYaw = current.movingRoot.getYRot();
      current.rootVelocity = current.movingRoot.getDeltaMovement();
   }

   public static void vehiclePositionApplied(Minecraft client, ClientboundMoveVehiclePacket packet) {
      Pending current = pending;
      if (current != null && client.player != null) TellusCommon.LOGGER.info(
         "Cube-net client vehicle packet {} packet=({}, {}, {}) root={} captured={} ready={} controlled={}",
         current.area.transitionId(), packet.getX(), packet.getY(), packet.getZ(),
         client.player.getRootVehicle(), current.movingRoot, current.readySent,
         client.player.getRootVehicle().isControlledByLocalInstance());
      if (current == null || current.movingRoot == null || client.player == null
         || current.movingRoot != client.player.getRootVehicle()) return;
      Entity root = current.movingRoot;
      if (!current.contains(root.chunkPosition().x, root.chunkPosition().z)) return;
      float turn = Mth.wrapDegrees(root.getYRot() - current.rootYaw);
      double angle = Math.toRadians(turn), cos = Math.cos(angle), sin = Math.sin(angle);
      Vec3 velocity = current.rootVelocity;
      root.setDeltaMovement(velocity.x * cos - velocity.z * sin, velocity.y,
         velocity.x * sin + velocity.z * cos);
      root.setOldPosAndRot();
      rotatePassengers(root, turn);
      playerPositionApplied(client);
   }

   private static void rotatePassengers(Entity vehicle, float turn) {
      for (Entity passenger : vehicle.getPassengers()) {
         passenger.setYRot(passenger.getYRot() + turn);
         if (passenger instanceof LivingEntity living) {
            living.yHeadRot += turn;
            living.yBodyRot += turn;
         }
         vehicle.positionRider(passenger);
         passenger.setOldPosAndRot();
         if (passenger instanceof LivingEntity living) {
            living.yHeadRotO = living.yHeadRot;
            living.yBodyRotO = living.yBodyRot;
         }
         rotatePassengers(passenger, turn);
      }
   }

   public static void renderedFrame(Minecraft client) {
      if (traceFrames <= 0 || client.player == null) return;
      TellusCommon.LOGGER.info("Cube-net client committed frame={} camera={} chunks={}",
         9 - traceFrames--, client.gameRenderer.getMainCamera().getPosition(), client.levelRenderer.getChunkStatistics());
   }

   public static void reset() {
      Pending old = pending;
      Minecraft client = Minecraft.getInstance();
      if (old != null && client.level != null && client.level.getChunkSource() == old.cache) {
         for (LevelChunk chunk : old.remote.values()) client.level.unload(chunk);
      }
      pending = null;
      traceFrames = 0;
      outboundMotionTrace = null;
      if (old != null && old.staged != null) old.staged.releaseAllBuffers();
   }

   private static final class Pending {
      private final CubeNetTransitionPreparePayload area;
      private final ClientChunkCache cache;
      private final Map<Long, LevelChunk> remote = new ConcurrentHashMap<>();
      private final Map<Long, ClientboundLevelChunkWithLightPacket> packets = new java.util.LinkedHashMap<>();
      private final int viewDistance;
      private final long startedNanos = System.nanoTime();
      private final List<ChunkRenderDispatcher.RenderChunk> sections = new ArrayList<>();
      private final List<ChunkRenderDispatcher.RenderChunk> inFlight = new ArrayList<>();
      private final RenderRegionCache regions = new RenderRegionCache();
      private final Vec3 compileCamera;
      private final int targetShadeQuarterTurns;
      private int remainingTicks = MAX_WAIT_TICKS;
      private int dataReadyTicks;
      private int nextSection;
      private boolean holdMovement;
      private long dataReadyNanos;
      private long compileFinishedNanos;
      private long compileNanos;
      private int compileBatches;
      private boolean readySent;
      private boolean chunksReceivedSent;
      private ViewArea staged;
      private Entity movingRoot;
      private float rootYaw;
      private Vec3 rootVelocity = Vec3.ZERO;
      private final Vec3[] motionHistory = new Vec3[MOTION_HISTORY_TICKS];
      private Vec3 lastMotionPosition;
      private float lastMotionYaw;
      private int nextMotionSample;
      private int motionSamples;
      private int motionTicks;
      private int lastMotionTick;
      private int stationarySamples;

      private Pending(CubeNetTransitionPreparePayload area, ClientChunkCache cache, int viewDistance) {
         this.area = area;
         this.cache = cache;
         this.viewDistance = viewDistance;
         this.holdMovement = area.holdMovement();
         this.targetShadeQuarterTurns = Math.floorMod(shadeQuarterTurns + area.shadingQuarterTurns(), 4);
         this.compileCamera = new Vec3(area.centerChunkX() * 16.0 + 8,
            Minecraft.getInstance().player.getY(), area.centerChunkZ() * 16.0 + 8);
         this.lastMotionPosition = Minecraft.getInstance().player.position();
         this.lastMotionYaw = Minecraft.getInstance().player.getYRot();
      }

      private boolean contains(int x, int z) {
         return Math.abs((long)x - area.centerChunkX()) <= area.radius()
            && Math.abs((long)z - area.centerChunkZ()) <= area.radius();
      }

      private boolean renderContains(int x, int z) {
         int radius = viewDistance;
         return Math.abs((long)x - area.centerChunkX()) <= radius
            && Math.abs((long)z - area.centerChunkZ()) <= radius;
      }
   }

   private static final class PostCommitMotionTrace {
      private final java.util.UUID transitionId;
      private Vec3 lastPosition;
      private int samples;

      private PostCommitMotionTrace(java.util.UUID transitionId, Vec3 position) {
         this.transitionId = transitionId;
         this.lastPosition = position;
      }
   }

   private static final class OutboundMotionTrace {
      private final java.util.UUID transitionId;
      private final boolean customCommit;
      private boolean firstMotionSent;
      private int samples;

      private OutboundMotionTrace(java.util.UUID transitionId, boolean customCommit) {
         this.transitionId = transitionId;
         this.customCommit = customCommit;
      }
   }
}
