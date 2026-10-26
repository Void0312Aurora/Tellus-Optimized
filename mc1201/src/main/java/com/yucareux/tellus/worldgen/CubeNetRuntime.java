package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetSeamTransform;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.network.CubeNetTransitionPreparePayload;
import com.yucareux.tellus.network.CubeNetTransitionCancelPayload;
import com.yucareux.tellus.network.CubeNetTransitionCommitPayload;
import com.yucareux.tellus.network.CubeNetTransitionMotionPayload;
import com.yucareux.tellus.network.CubeNetTransitionChunksReceivedPayload;
import com.yucareux.tellus.network.CubeNetBoundaryHintPayload;
import com.yucareux.tellus.worldgen.global.CubeNetSeamApproach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Same-Level movement adapter for the cut edges of the cube net. Direct net
 * neighbors remain ordinary Minecraft movement; only the seven cut pairs use
 * this asynchronous preload and coordinate transport path.
 */
public final class CubeNetRuntime {
   private static volatile CubeNetRuntime ACTIVE;
   private static final TicketType<UUID> CUBE_NET_TRANSFER =
      TicketType.create("tellus_cube_net_transfer", Comparator.<UUID>naturalOrder());
   private static final int LOAD_TIMEOUT_TICKS = 1200;
   private static final int CLIENT_CHUNK_RETRY_TICKS = 160;
   private static final int MAX_CLIENT_CHUNK_SENDS = 2;
   private static final int MAX_CHUNK_PACKETS_PER_TICK = 8;
   private static final int MAX_CONCURRENT_CHUNK_LOADS = 8;
   private static final long ESTIMATED_CHUNK_LOAD_BYTES = 8L * 1024L * 1024L;
   private static final long MAX_CHUNK_LOAD_BYTES =
      MAX_CONCURRENT_CHUNK_LOADS * ESTIMATED_CHUNK_LOAD_BYTES;
   private static final Semaphore CHUNK_LOAD_BUDGET = new Semaphore(
      Math.min(MAX_CONCURRENT_CHUNK_LOADS,
         Math.toIntExact(MAX_CHUNK_LOAD_BYTES / ESTIMATED_CHUNK_LOAD_BYTES)));
   private static final int APRON_REBASE_DEPTH_BLOCKS = 24;
   private static final double SEAM_DESTINATION_INSET = 0.5;
   // Chunk loading and client-side interpolation can leave source-face
   // packets in flight for several seconds on a cold seam. Keep the gate
   // alive until the client acknowledges the destination, with this expiry
   // only as a bounded fallback for a lost connection.
   private static final int CORRECTION_GATE_TICKS = 200;
   // Legacy/non-player probes retain their bounded preload. Staged players
   // use their effective view distance plus vanilla's neighbor halo.
   private static final int TRANSFER_RADIUS_CHUNKS = 2;
   private static final double MAX_LEASH_BUNDLE_DISTANCE_SQUARED = 32.0 * 32.0;
   private static final int MOTION_TRACE_HISTORY_TICKS = 12;
   private static final int MOTION_TRACE_FUTURE_TICKS = 12;
   private static final int MOTION_TRACE_TIMEOUT_TICKS = 1200;
   /**
    * A seam step is produced by ordinary player movement and is sub-block in
    * size. Larger horizontal deltas are command/teleport corrections (or
    * stale client packets) and must remain in vanilla's teleport handling.
    */
   private static final double MAX_SEAM_STEP_SQUARED = 4.0;
   private static final double MAX_CUSTOM_SPEED_SQUARED = 25.0;
   private static final double MAX_CUSTOM_VELOCITY_DELTA_SQUARED = 9.0;
   private static final double MAX_CUBE_NET_PACKET_STEP_SQUARED = 256.0;
   private static final double BOUNDARY_HINT_SEARCH_RADIUS = 96.0;
   private static final double BOUNDARY_HINT_SEARCH_STEP = 4.0;
   private static final int BOUNDARY_HINT_DIRECTIONS = 32;

   private enum TransitionState { PREPARE, STAGED, COMMIT, RELEASE }

   private final Map<UUID, Transfer> pending = new ConcurrentHashMap<>();
   private final Map<UUID, Preparation> preparations = new ConcurrentHashMap<>();
   private final Map<UUID, DeferredTransfer> deferred = new ConcurrentHashMap<>();
   private final Map<UUID, Correction> correctionGates = new ConcurrentHashMap<>();
   private final Map<UUID, TransitionState> transitionStates = new ConcurrentHashMap<>();
   private final Map<UUID, CorrectionCounters> correctionCounters = new ConcurrentHashMap<>();
   private final Map<UUID, MotionCapture> motionCaptures = new ConcurrentHashMap<>();
   private final Map<UUID, Integer> retryAfter = new ConcurrentHashMap<>();
   private final Set<UUID> boundaryHintShown = ConcurrentHashMap.newKeySet();
   private final Map<UUID, Integer> nextBoundaryRibbonTick = new ConcurrentHashMap<>();
   /** Keep an entity from immediately reversing while it remains in the destination apron. */
   private final Set<UUID> entityRebaseHysteresis = ConcurrentHashMap.newKeySet();
   private final Map<UUID, EntityTransfer> entityTransfers = new ConcurrentHashMap<>();
   private ServerLevel level;
   private CubeNetRuntimeSmokeCheck smoke;

   public static void register() {
      var runtime = new CubeNetRuntime();
      ACTIVE = runtime;
      ServerLifecycleEvents.SERVER_STARTED.register(runtime::discover);
      ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
         runtime.clear();
         if (ACTIVE == runtime) ACTIVE = null;
      });
      ServerTickEvents.END_SERVER_TICK.register(runtime::tick);
      ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> runtime.disconnect(handler.getPlayer()));
      CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
         var root = Commands.literal("cube_net").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> {
               context.getSource().sendSuccess(() -> Component.literal("Cube-net Level: "
                  + (runtime.level == null ? "disabled" : runtime.level.dimension().location())
                  + "; pending transfers: " + runtime.pending.size()
                  + "; transition states: " + runtime.transitionStates
                  + "; correction counters: " + runtime.correctionSummary()), false);
               return runtime.level == null ? 0 : 1;
            }))
            .then(Commands.literal("mount_probe").executes(context ->
               runtime.mountProbe(context.getSource().getPlayerOrException())))
            .then(Commands.literal("trace_motion").executes(context ->
               runtime.armMotionTrace(context.getSource().getPlayerOrException())));
         if (Boolean.getBoolean("tellus.benchmark.enabled")) {
            root.then(Commands.literal("light")
               .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
                  ServerLevel sampleLevel = context.getSource().getLevel();
                  BlockPos samplePos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                  int sky = sampleLevel.getBrightness(LightLayer.SKY, samplePos);
                  int block = sampleLevel.getBrightness(LightLayer.BLOCK, samplePos);
                  context.getSource().sendSuccess(() -> Component.literal("Cube-net light at "
                     + samplePos.toShortString() + ": sky=" + sky + ", block=" + block
                     + ", state=" + sampleLevel.getBlockState(samplePos)), false);
                  return 1;
               })));
            root.then(Commands.literal("verify").executes(context -> {
               if (runtime.level == null || runtime.smoke != null) {
                  context.getSource().sendFailure(Component.literal("Cube-net routing is disabled or a check is active"));
                  return 0;
               }
               runtime.smoke = new CubeNetRuntimeSmokeCheck(context.getSource().getServer(), runtime.level);
               return 1;
            }));
         }
         dispatcher.register(Commands.literal("tellus").then(root));
      });
   }

   public static boolean interceptBoundaryMove(ServerPlayer player, Vec3 movement) {
      CubeNetRuntime runtime = ACTIVE;
      return runtime != null && runtime.interceptBoundaryMove0(player, movement);
   }

   public static boolean admitsPlayerDestination(ServerLevel level, double x, double z) {
      if (!(level.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)) return true;
      return generator.admitsOrdinaryPlanarPoint(x, z);
   }

   /** Shows a chat record once and keeps the action-bar hint visible while blocked. */
   public static void notifyBoundaryBlocked(ServerPlayer player) {
      CubeNetRuntime runtime = ACTIVE;
      if (runtime != null) runtime.notifyBoundaryBlocked0(player);
      else {
         Component hint = Component.literal("已到达当前区域边界，无法继续前进。");
         player.sendSystemMessage(hint);
         player.displayClientMessage(hint, true);
      }
   }

   /** Re-arms the next boundary hint after an accepted player teleport. */
   public static void clearBoundaryHint(ServerPlayer player) {
      CubeNetRuntime runtime = ACTIVE;
      if (runtime != null) runtime.boundaryHintShown.remove(player.getUUID());
   }

   private void notifyBoundaryBlocked0(ServerPlayer player) {
      UUID id = player.getUUID();
      Component hint = Component.literal("已到达当前区域边界，无法继续前进。");
      if (this.boundaryHintShown.add(id)) {
         player.sendSystemMessage(hint);
      }
      if (player.server.getTickCount() >= this.nextBoundaryRibbonTick.getOrDefault(id, 0)) {
         this.nextBoundaryRibbonTick.put(id, player.server.getTickCount() + 20);
         this.sendBoundaryHint(player);
      }
      player.displayClientMessage(hint, true);
   }

   private void sendBoundaryHint(ServerPlayer player) {
      if (!ServerPlayNetworking.canSend(player, CubeNetBoundaryHintPayload.TYPE)) {
         TellusCommon.LOGGER.debug("Cube-net boundary hint skipped: client cannot receive player={}",
            player.getGameProfile().getName());
         return;
      }
      if (!(player.serverLevel().getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)) {
         TellusCommon.LOGGER.debug("Cube-net boundary hint skipped: generator={} player={}",
            player.serverLevel().getChunkSource().getGenerator().getClass().getName(),
            player.getGameProfile().getName());
         return;
      }
      BoundaryHint boundary = findBoundaryHint(generator, player.getX(), player.getZ());
      if (boundary != null) {
         TellusCommon.LOGGER.debug("Cube-net boundary hint sent player={} at=({}, {}) tangent=({}, {})",
            player.getGameProfile().getName(), boundary.x(), boundary.z(),
            boundary.tangentX(), boundary.tangentZ());
         ServerPlayNetworking.send(player, new CubeNetBoundaryHintPayload(
            boundary.x(), boundary.z(), boundary.tangentX(), boundary.tangentZ(), 120));
      } else {
         TellusCommon.LOGGER.debug("Cube-net boundary hint unavailable player={} pos=({}, {}) ordinary={}",
            player.getGameProfile().getName(), player.getX(), player.getZ(),
            generator.admitsOrdinaryPlanarPoint(player.getX(), player.getZ()));
      }
   }

   static BoundaryHint findBoundaryHint(CubeNetChunkGenerator generator, double x, double z) {
      if (!generator.admitsBoundaryHintPoint(x, z)) return null;
      double bestDistance = Double.POSITIVE_INFINITY;
      double bestNormalX = 0.0;
      double bestNormalZ = 0.0;
      for (int direction = 0; direction < BOUNDARY_HINT_DIRECTIONS; direction++) {
         double angle = Math.PI * 2.0 * direction / BOUNDARY_HINT_DIRECTIONS;
         double normalX = Math.cos(angle);
         double normalZ = Math.sin(angle);
         double previousDistance = 0.0;
         for (double distance = BOUNDARY_HINT_SEARCH_STEP;
            distance <= Math.min(BOUNDARY_HINT_SEARCH_RADIUS, bestDistance + BOUNDARY_HINT_SEARCH_STEP);
            distance += BOUNDARY_HINT_SEARCH_STEP) {
            if (generator.admitsBoundaryHintPoint(x + normalX * distance, z + normalZ * distance)) {
               previousDistance = distance;
               continue;
            }
            double boundaryDistance = refineBoundaryHint(generator, x, z, normalX, normalZ,
               previousDistance, distance);
            if (boundaryDistance < bestDistance) {
               bestDistance = boundaryDistance;
               bestNormalX = normalX;
               bestNormalZ = normalZ;
            }
            break;
         }
      }
      if (!Double.isFinite(bestDistance) || bestDistance > BOUNDARY_HINT_SEARCH_RADIUS) return null;
      // Keep the visual surface beyond the camera near plane at physical contact.
      double visualDistance = bestDistance + 0.5;
      return new BoundaryHint(x + bestNormalX * visualDistance, z + bestNormalZ * visualDistance,
         -bestNormalZ, bestNormalX);
   }

   private static double refineBoundaryHint(CubeNetChunkGenerator generator, double x, double z,
      double normalX, double normalZ, double lower, double upper) {
      // Coarse refinement ties many rays at contact and can turn the wall edge-on.
      for (int iteration = 0; iteration < 18; iteration++) {
         double middle = (lower + upper) * 0.5;
         if (generator.admitsBoundaryHintPoint(x + normalX * middle, z + normalZ * middle)) {
            lower = middle;
         } else {
            upper = middle;
         }
      }
      return (lower + upper) * 0.5;
   }

   /**
    * Filters old client position packets after a same-level seam rebase. The
    * vanilla packet handler performs its moved-too-quickly check before
    * Entity#move, so the movement hook alone cannot stop a packet that still
    * contains the source-face position from producing a false warning.
    */
   public static boolean interceptMovePacket(ServerPlayer player, ServerboundMovePlayerPacket packet) {
      CubeNetRuntime runtime = ACTIVE;
      return runtime != null && runtime.interceptMovePacket0(player, packet);
   }

   /** Records the vanilla teleport acknowledgement while a seam motion trace is active. */
   public static void observeTeleportConfirmation(ServerPlayer player, int receivedId, int awaitingId) {
      CubeNetRuntime runtime = ACTIVE;
      if (runtime != null) runtime.observeTeleportConfirmation0(player, receivedId, awaitingId);
   }

   /** Drops stale source-chart vehicle packets after a mounted seam rebase. */
   public static boolean interceptVehicleMovePacket(ServerPlayer player, ServerboundMoveVehiclePacket packet) {
      CubeNetRuntime runtime = ACTIVE;
      return runtime != null && runtime.interceptVehicleMovePacket0(player, packet);
   }

   /** Completes the client-side staging half of one server-owned transition. */
   public static void acceptClientReady(ServerPlayer player, UUID transitionId) {
      CubeNetRuntime runtime = ACTIVE;
      if (runtime != null) runtime.acceptClientReady0(player, transitionId);
   }

   /** Stops bounded chunk retransmission once the client has staged every target packet. */
   public static void acceptClientChunksReceived(ServerPlayer player, UUID transitionId) {
      CubeNetRuntime runtime = ACTIVE;
      if (runtime != null) runtime.acceptClientChunksReceived0(player, transitionId);
   }

   /** Applies the first bounded client movement sample after an atomic commit. */
   public static void acceptClientMotion(ServerPlayer player, CubeNetTransitionMotionPayload payload) {
      CubeNetRuntime runtime = ACTIVE;
      if (runtime != null) runtime.acceptClientMotion0(player, payload);
   }

   private void acceptClientReady0(ServerPlayer player, UUID transitionId) {
      Preparation preparation = this.preparations.get(player.getUUID());
      if (preparation != null && preparation.transitionId().equals(transitionId)
         && player.serverLevel() == preparation.level()) {
         preparation.clientReady().complete(null);
         TellusCommon.LOGGER.info("Cube-net approach ready {} source={} elapsedMs={}", transitionId,
            player.getRootVehicle().position(), (System.nanoTime() - preparation.startedNanos()) / 1_000_000L);
         return;
      }
      Transfer transfer = this.pending.get(player.getUUID());
      if (transfer != null) {
         if (!transfer.transitionId().equals(transitionId) || player.serverLevel() != transfer.level()) return;
         transfer.clientReady().complete(null);
         TellusCommon.LOGGER.debug("Cube-net client staged transition for {} id={}",
            player.getGameProfile().getName(), transitionId);
         return;
      }
      EntityTransfer entityTransfer = this.entityTransfers.get(player.getUUID());
      if (entityTransfer == null || !player.getUUID().equals(entityTransfer.playerId())
         || !entityTransfer.transitionId().equals(transitionId)
         || player.serverLevel() != entityTransfer.level()) return;
      entityTransfer.clientReady().complete(null);
      TellusCommon.LOGGER.debug("Cube-net client staged mounted transition for {} id={}",
         player.getGameProfile().getName(), transitionId);
   }

   private void acceptClientChunksReceived0(ServerPlayer player, UUID transitionId) {
      Preparation preparation = this.preparations.get(player.getUUID());
      if (preparation != null && preparation.transitionId().equals(transitionId)
         && player.serverLevel() == preparation.level()
         && preparation.clientChunks().markClientReceived()) {
         TellusCommon.LOGGER.info("Cube-net client confirmed destination chunks {} player={}", transitionId,
            player.getGameProfile().getName());
         return;
      }
      Transfer transfer = this.pending.get(player.getUUID());
      if (transfer != null && transfer.transitionId().equals(transitionId)
         && player.serverLevel() == transfer.level()
         && transfer.clientChunks().markClientReceived()) {
         TellusCommon.LOGGER.info("Cube-net client confirmed destination chunks {} player={}", transitionId,
            player.getGameProfile().getName());
         return;
      }
      EntityTransfer entityTransfer = this.entityTransfers.get(player.getUUID());
      if (entityTransfer != null && player.getUUID().equals(entityTransfer.playerId())
         && entityTransfer.transitionId().equals(transitionId)
         && player.serverLevel() == entityTransfer.level()
         && entityTransfer.clientChunks().markClientReceived()) {
         TellusCommon.LOGGER.info("Cube-net client confirmed destination chunks {} player={}", transitionId,
            player.getGameProfile().getName());
      }
   }

   private void acceptClientMotion0(ServerPlayer player, CubeNetTransitionMotionPayload payload) {
      if (player == null || player.isRemoved() || player.isPassenger()) {
         TellusCommon.LOGGER.warn("Cube-net first custom motion ignored playerState playerNull={} removed={} passenger={}",
            player == null, player != null && player.isRemoved(), player != null && player.isPassenger());
         return;
      }
      UUID id = player.getUUID();
      Correction correction = this.correctionGates.get(id);
      if (correction == null || correction.transitionId() == null
         || !correction.transitionId().equals(payload.transitionId())) {
         TellusCommon.LOGGER.warn(
            "Cube-net first custom motion ignored gate player={} payloadId={} gateId={} gatePresent={}",
            player.getGameProfile().getName(), payload.transitionId(),
            correction == null ? null : correction.transitionId(), correction != null);
         return;
      }
      double dx = payload.x() - player.getX();
      double dy = payload.y() - player.getY();
      double dz = payload.z() - player.getZ();
      if (distanceSquared(dx, dy, dz) > MAX_SEAM_STEP_SQUARED) {
         this.counters(id).rejected++;
         TellusCommon.LOGGER.warn("Cube-net first custom motion rejected player={} id={} stepSquared={}",
            player.getGameProfile().getName(), payload.transitionId(), distanceSquared(dx, dy, dz));
         return;
      }
      if (!isCustomMotionWithinSnapshot(correction.velocity(), correction.onGround(), payload)
         || !player.level().noCollision(player, player.getBoundingBox().move(dx, dy, dz))) {
         this.counters(id).rejected++;
         TellusCommon.LOGGER.warn("Cube-net first custom motion rejected authoritative state player={} id={} "
               + "speedSquared={} onGround={} collisionFree={}",
            player.getGameProfile().getName(), payload.transitionId(), payload.velocity().lengthSqr(),
            payload.onGround(), player.level().noCollision(player, player.getBoundingBox().move(dx, dy, dz)));
         return;
      }
      player.absMoveTo(payload.x(), payload.y(), payload.z(), payload.yaw(), payload.pitch());
      player.setDeltaMovement(payload.velocity());
      player.setOnGround(payload.onGround());
      player.connection.resetPosition();
      this.correctionGates.remove(id, correction);
      this.transitionStates.remove(id);
      this.counters(id).acknowledged++;
      TellusCommon.LOGGER.info(
         "Cube-net first custom motion accepted player={} tick={} id={} position=({}, {}, {})",
         player.getGameProfile().getName(), player.server.getTickCount(), payload.transitionId(),
         payload.x(), payload.y(), payload.z());
   }

   private boolean interceptMovePacket0(ServerPlayer player, ServerboundMovePlayerPacket packet) {
      UUID id = player.getUUID();
      Transfer transfer = this.pending.get(id);
      Correction correction = this.correctionGates.get(id);
      EntityTransfer entityTransfer = this.entityTransfers.get(id);
      MotionCapture capture = this.motionCaptures.get(id);
      int now = player.server.getTickCount();
      if (correction != null && correction.expiresAtTick() < now) {
         this.correctionGates.remove(id);
         this.transitionStates.remove(id);
         this.counters(id).expired++;
         correction = null;
      }
      if (!packet.hasPosition()) {
         this.logMotionPacket(player, packet, now, "pass_no_position", correction, capture);
         return false;
      }
      if (entityTransfer != null && entityTransfer.playerId() != null) {
         this.holdEntity(entityTransfer);
         return true;
      }
      double x = packet.getX(player.getX());
      double y = packet.getY(player.getY());
      double z = packet.getZ(player.getZ());
      double dx = x - player.getX(), dy = y - player.getY(), dz = z - player.getZ();
      // A packet this far from the authoritative cube-net position is an old
      // source-chart update (or a command correction) rather than a walk
      // step. Reject it before vanilla's moved-too-quickly check; subsequent
      // ordinary input packets continue from the authoritative position.
      double packetStepSquared = distanceSquared(dx, dy, dz);
      boolean cubeNetLevel = this.level == player.serverLevel()
         && player.serverLevel().getChunkSource().getGenerator() instanceof CubeNetChunkGenerator;
      if (packetStepSquared > MAX_CUBE_NET_PACKET_STEP_SQUARED && cubeNetLevel) {
         this.counters(id).rejected++;
         this.logMotionPacket(player, packet, now, "reject_large_delta_resend_position", correction, capture);
         this.hold(player, true);
         return true;
      }
      if (transfer != null) {
         if (distanceSquared(dx, dy, dz) > MAX_SEAM_STEP_SQUARED) {
            this.counters(id).rejected++;
            this.logMotionPacket(player, packet, now, "reject_pending_transfer_resend_position", correction, capture);
            this.hold(player, true);
            return true;
         }
         this.logMotionPacket(player, packet, now, "pass_pending_transfer_to_vanilla", correction, capture);
         return false;
      }
      if (correction == null) {
         this.logMotionPacket(player, packet, now, "pass_no_correction_gate", null, capture);
         return false;
      }
      // Once the mounted client has acknowledged the destination, the chart
      // guard follows ordinary movement. A fixed two-block destination ball
      // would reject every legitimate departure until the gate expires.
      if (correction.acknowledged() && player.isPassenger()) return false;
      if (distanceSquared(x - correction.x(), y - correction.y(), z - correction.z())
         > MAX_SEAM_STEP_SQUARED) {
         this.counters(id).rejected++;
         boolean atomicCustom = correction.transitionId() != null && !player.isPassenger();
         this.logMotionPacket(player, packet, now,
            atomicCustom ? "reject_correction_distance_atomic_no_resend"
               : "reject_correction_distance_resend_target", correction, capture);
         if (atomicCustom) {
            // The custom commit packet is the sole client-side handoff for an
            // atomic transition. A stale source-chart packet can arrive after
            // the server commit; sending a vanilla target teleport here would
            // race the custom packet and make the client commit the same chart
            // a second time through playerPositionApplied().
            player.connection.resetPosition();
            return true;
         }
         this.holdCorrection(player, correction);
         return true;
      }
      this.logMotionPacket(player, packet, now, "pass_target_position_acknowledges_gate", correction, capture);
      // A packet close to the authoritative destination is the client's
      // acknowledgement. For the custom atomic path, retain the short-lived
      // gate until the first custom motion sample arrives. The client queues
      // that payload beside its first ordinary movement packet, so removing
      // the gate here would make packet ordering decide whether the fast path
      // can take effect. Ordinary movement remains allowed while this gate is
      // retained; expiry is the bounded fallback if the custom sample is lost.
      if (correction.transitionId() != null && !player.isPassenger()) return false;
      if (player.isPassenger()) {
         this.acknowledgeMountedCorrection(id, correction);
         return false;
      }
      this.correctionGates.remove(id);
      this.transitionStates.remove(id);
      if (!correction.acknowledged()) this.counters(id).acknowledged++;
      return false;
   }

   private void observeTeleportConfirmation0(ServerPlayer player, int receivedId, int awaitingId) {
      MotionCapture capture = this.motionCaptures.get(player.getUUID());
      if (capture == null || !capture.committed) return;
      TellusCommon.LOGGER.info(
         "Cube-net seam teleport confirmation player={} tick={} receivedId={} awaitingId={} matches={} position={}",
         player.getGameProfile().getName(), player.server.getTickCount(), receivedId, awaitingId,
         receivedId == awaitingId, player.position());
   }

   private void logMotionPacket(ServerPlayer player, ServerboundMovePlayerPacket packet, int tick, String action,
      Correction correction, MotionCapture capture) {
      if (capture == null || !capture.committed) return;
      double x = packet.getX(player.getX());
      double y = packet.getY(player.getY());
      double z = packet.getZ(player.getZ());
      TellusCommon.LOGGER.info(
         "Cube-net seam movement packet player={} tick={} action={} hasPosition={} hasRotation={} "
            + "packetPosition=({}, {}, {}) authoritativePosition=({}, {}, {}) teleportId={} gateAcknowledged={}",
         player.getGameProfile().getName(), tick, action, packet.hasPosition(), packet.hasRotation(), x, y, z,
         player.getX(), player.getY(), player.getZ(), correction == null ? -1 : correction.teleportId(),
         correction != null && correction.acknowledged());
   }

   private boolean interceptVehicleMovePacket0(ServerPlayer player, ServerboundMoveVehiclePacket packet) {
      UUID id = player.getUUID();
      EntityTransfer entityTransfer = this.entityTransfers.get(id);
      if (entityTransfer != null && id.equals(entityTransfer.playerId())) {
         this.holdEntity(entityTransfer);
         return true;
      }
      Correction correction = this.correctionGates.get(id);
      int now = player.server.getTickCount();
      if (correction != null && correction.expiresAtTick() < now) {
         this.correctionGates.remove(id);
         this.transitionStates.remove(id);
         this.counters(id).expired++;
         correction = null;
      }
      Entity vehicle = player.getVehicle();
      if (correction == null || vehicle == null) return false;
      double dx = packet.getX() - (correction.acknowledged() ? vehicle.getX() : correction.x());
      double dy = packet.getY() - (correction.acknowledged() ? vehicle.getY() : correction.y());
      double dz = packet.getZ() - (correction.acknowledged() ? vehicle.getZ() : correction.z());
      double limit = correction.acknowledged() ? MAX_CUBE_NET_PACKET_STEP_SQUARED : MAX_SEAM_STEP_SQUARED;
      if (distanceSquared(dx, dy, dz) <= limit) {
         // Keep the gate while mounted: the client can acknowledge the
         // destination and still deliver an older source-chart vehicle packet
         // on a later network tick.
         this.acknowledgeMountedCorrection(id, correction);
         if (player.isPassenger()) return false;
         this.correctionGates.remove(id);
         this.transitionStates.remove(id);
         return false;
      }
      this.counters(id).rejected++;
      if (now - correction.lastVehicleResendTick() >= 10
         && this.correctionGates.replace(id, correction, correction.withVehicleResendTick(now))) {
         player.connection.send(new ClientboundMoveVehiclePacket(player.getRootVehicle()));
         player.connection.send(new ClientboundSetPassengersPacket(vehicle));
         this.counters(id).resent++;
      }
      return true;
   }

   private void acknowledgeMountedCorrection(UUID id, Correction correction) {
      if (!correction.acknowledged()
         && this.correctionGates.replace(id, correction, correction.withAcknowledged())) {
         this.counters(id).acknowledged++;
      }
   }

   private static double distanceSquared(double x, double y, double z) {
      return x * x + y * y + z * z;
   }

   static boolean isCustomMotionWithinSnapshot(Vec3 expectedVelocity, boolean expectedOnGround,
      CubeNetTransitionMotionPayload payload) {
      return payload.onGround() == expectedOnGround
         && payload.velocity().lengthSqr() <= MAX_CUSTOM_SPEED_SQUARED
         && payload.velocity().distanceToSqr(expectedVelocity) <= MAX_CUSTOM_VELOCITY_DELTA_SQUARED;
   }

   private boolean interceptBoundaryMove0(ServerPlayer player, Vec3 movement) {
      UUID playerId = player.getUUID();
      if (this.pending.containsKey(playerId)) return true;
      if (this.deferred.containsKey(playerId)) {
         this.transitionStates.put(playerId, TransitionState.PREPARE);
         this.hold(player);
         return true;
      }
      if ((movement.x == 0.0 && movement.z == 0.0)
         || !(player.serverLevel().getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)) {
         return false;
      }
      // A command teleport can leave one or more old client movement packets
      // in flight. Routing such a packet through the seam table turns an
      // unrelated spawn/correction position into a bogus face address and can
      // request an excluded corner chunk. Seam routing only handles local
      // walking steps; let vanilla validate larger corrections.
      if (movement.x * movement.x + movement.z * movement.z > MAX_SEAM_STEP_SQUARED) {
         return false;
      }
      if (this.level != player.serverLevel()) return false;
      CubeNetLayout layout = generator.adapter().layout();
      if (generator.nativeApronEnabled()) return this.interceptApronMove(player, movement, generator);
      CubeNetLayout.FacePoint source = owningPoint(layout, player.getX(), player.getZ(), movement);
      if (source == null) return false;
      double[] localMovement = layout.localVector(source.face(), movement.x, movement.z);
      double targetU = source.u() + localMovement[0], targetV = source.v() + localMovement[1];
      if (targetU >= 0.0 && targetU <= layout.faceSize() && targetV >= 0.0 && targetV <= layout.faceSize()) {
         if (!generator.admitsOrdinaryFacePoint(new CubeNetLayout.FacePoint(source.face(), targetU, targetV))) {
            this.holdAtBoundary(player);
            return true;
         }
         this.boundaryHintShown.remove(playerId);
         return false;
      }
      if (this.retryAfter.getOrDefault(player.getUUID(), 0) > player.server.getTickCount()) {
         this.hold(player);
         return true;
      }
      try {
         CubeNetLayout.SeamTransfer transfer = layout.crossSeam(source, localMovement[0], localMovement[1]);
         if (!transfer.crossed()) return false;
         if (!generator.admitsOrdinaryFacePoint(transfer.destination())) {
            this.holdAtBoundary(player);
            return true;
         }
         if (layout.arePlanarNeighbors(source.face(), transfer.seam().sourceSide())) {
            // The five direct net edges are exactly what vanilla planar
            // movement should see; no seam transfer is needed there.
            this.boundaryHintShown.remove(playerId);
            return false;
         }
         if (player.isPassenger() || player.isVehicle()) {
            this.hold(player);
            return true;
         }
         CubeNetLayout.FacePoint safeDestination = insetDestination(layout,
            transfer.destination(), transfer.seam().targetSide(), SEAM_DESTINATION_INSET);
         double[] destination = planar(layout, safeDestination);
         double[] localVelocity = layout.localVector(source.face(),
            player.getDeltaMovement().x, player.getDeltaMovement().z);
         double[] targetVelocity = layout.transportVector(source.face(), transfer.seam().sourceSide(),
            localVelocity[0], localVelocity[1]);
         double[] planarVelocity = layout.planarVector(transfer.destination().face(),
            targetVelocity[0], targetVelocity[1]);
         float yaw = transportYaw(layout, source.face(), transfer.seam().sourceSide(),
            transfer.destination().face(), player.getYRot());
         int shadingQuarterTurns = shadingQuarterTurns(layout, transfer.seam());
         // Defer the async transfer until the packet handler has finished its
         // vanilla movement validation. Starting it from Entity#move changes
         // the authoritative position before that validation and produces a
         // false moved-too-quickly warning for the same packet.
         this.deferred.put(playerId, new DeferredTransfer(destination[0], player.getY() + movement.y,
            destination[1], new Vec3(planarVelocity[0], player.getDeltaMovement().y, planarVelocity[1]), yaw,
            shadingQuarterTurns));
         this.transitionStates.put(playerId, TransitionState.PREPARE);
         TellusCommon.LOGGER.info("Cube-net seam deferred for {} source=({}, {}) target=({}, {})",
            player.getGameProfile().getName(), player.getX(), player.getZ(), destination[0], destination[1]);
         this.hold(player);
      } catch (IllegalArgumentException error) {
         // A corner crossing has no unique face continuation under the
         // current corner-removal policy. Hold the entity at the last point.
         this.hold(player);
      }
      return true;
   }

   /** Native apron walking keeps the same engine chart, pose, and velocity. */
   private boolean interceptApronMove(ServerPlayer player, Vec3 movement, CubeNetChunkGenerator generator) {
      CubeNetLayout layout = generator.adapter().layout();
      ChunkPos currentChunk = new ChunkPos(player.blockPosition());
      ChunkPos nextChunk = new ChunkPos(BlockPos.containing(player.getX() + movement.x,
         player.getY() + movement.y, player.getZ() + movement.z));
      var currentProjection = generator.apronProjection(currentChunk).orElse(null);
      var nextProjection = generator.apronProjection(nextChunk).orElse(null);
      var nextCanonical = generator.address(nextChunk).orElse(null);
      if (nextProjection != null && !generator.admitsOrdinaryMaterialization(
         new SphereWorldModel.SphereChunkAddress(nextProjection.targetFace().index(),
            nextProjection.targetChunkX(), nextProjection.targetChunkZ()))) {
         this.holdAtBoundary(player);
         return true;
      }
      if (nextCanonical != null && !generator.admitsOrdinaryMaterialization(nextCanonical)) {
         this.holdAtBoundary(player);
         return true;
      }
      if (currentProjection != null) {
         if ((nextProjection != null && nextProjection.sourceFace() == currentProjection.sourceFace()
               && nextProjection.sourceSide() == currentProjection.sourceSide())
            || (nextCanonical != null && nextCanonical.face() == currentProjection.sourceFace().index())) {
            this.boundaryHintShown.remove(player.getUUID());
            return false;
         }
          this.holdAtBoundary(player);
         return true;
      }
      CubeNetLayout.FacePoint source = owningPoint(layout, player.getX(), player.getZ(), movement);
      if (source == null) return false;
      if (!generator.admitsOrdinaryFacePoint(source)) {
         this.holdAtBoundary(player);
         return true;
      }
      double[] local = layout.localVector(source.face(), movement.x, movement.z);
      try {
         var transfer = layout.crossSeam(source, local[0], local[1]);
         if (!transfer.crossed() || layout.arePlanarNeighbors(source.face(), transfer.seam().sourceSide())) return false;
         if (nextProjection != null && nextProjection.sourceFace() == source.face()
            && nextProjection.sourceSide() == transfer.seam().sourceSide()) return false;
      } catch (IllegalArgumentException cornerCrossing) { }
       this.holdAtBoundary(player);
      return true;
   }

   private void holdAtBoundary(ServerPlayer player) {
      notifyBoundaryBlocked0(player);
      this.hold(player);
   }

   private void discover(MinecraftServer server) {
      this.clear();
      List<ServerLevel> levels = new ArrayList<>();
      for (ServerLevel candidate : server.getAllLevels()) {
         if (candidate.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator) levels.add(candidate);
      }
      if (levels.size() == 1) {
         this.level = levels.get(0);
         TellusCommon.LOGGER.info("Enabled single-Level cube-net seam routing for {}", this.level.dimension().location());
      } else if (!levels.isEmpty()) {
         TellusCommon.LOGGER.error("Cube-net seam routing requires exactly one cube-net Level; found {}", levels.size());
      }
   }

   /**
    * Creates a server-owned mounted-player fixture in one tick. This keeps the
    * compatibility probe focused on transfer semantics instead of command
    * execution context and cross-dimension selector behavior.
    */
   private int mountProbe(ServerPlayer player) {
      if (this.level == null || !(this.level.getChunkSource().getGenerator()
         instanceof CubeNetChunkGenerator generator) || !generator.nativeApronEnabled()) {
         player.sendSystemMessage(Component.literal("Cube-net runtime is not ready"));
         return 0;
      }
      if (player.isPassenger() || player.isVehicle()) {
         player.sendSystemMessage(Component.literal("Player must be unmounted before the probe"));
         return 0;
      }
      List<Entity> probeEntities = new ArrayList<>();
      for (Entity entity : this.level.getAllEntities()) probeEntities.add(entity);
      for (Entity entity : probeEntities) {
         if (entity.getTags().contains("tellus_player_mount_probe")) {
            entity.discard();
         }
      }
      // Start on the face interior so the client must ride across the cut and
      // through the native apron before the mounted rebase can occur.
      double x = 15360.5;
      double y = 101.0;
      double z = -128.5;
      player.teleportTo(this.level, x, y, z, 0.0F, 0.0F);
      Boat boat = EntityType.BOAT.create(this.level);
      if (boat == null) {
         player.sendSystemMessage(Component.literal("Could not create mounted probe vehicle"));
         return 0;
      }
      boat.moveTo(x, y, z, 0.0F, 0.0F);
      boat.setNoGravity(true);
      boat.setInvulnerable(true);
      boat.addTag("tellus_player_mount_probe");
      if (!this.level.addFreshEntity(boat) || !player.startRiding(boat, true)) {
         boat.discard();
         player.sendSystemMessage(Component.literal("Could not establish mounted probe relation"));
         return 0;
      }
      player.sendSystemMessage(Component.literal(
         "Mounted-player route armed on the face interior; ride south across the cut and apron to test the rebase"));
      return 1;
   }

   private void tick(MinecraftServer server) {
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (player.serverLevel() == this.level
            && server.getTickCount() >= this.nextBoundaryRibbonTick.getOrDefault(player.getUUID(), 0)
            && player.serverLevel().getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator) {
            this.nextBoundaryRibbonTick.put(player.getUUID(), server.getTickCount() + 20);
            if (generator.nearOrdinaryBoundary(player.getX(), player.getZ(), BOUNDARY_HINT_SEARCH_RADIUS)) {
               this.sendBoundaryHint(player);
            }
         }
         this.prepareApproach(player);
         this.sampleMotion(player, server.getTickCount());
         DeferredTransfer deferredTransfer = this.deferred.remove(player.getUUID());
         if (deferredTransfer != null) {
            this.transitionStates.put(player.getUUID(), TransitionState.PREPARE);
            TellusCommon.LOGGER.info("Cube-net seam starting deferred transfer for {}",
               player.getGameProfile().getName());
            this.begin(player, deferredTransfer.x(), deferredTransfer.y(), deferredTransfer.z(),
               deferredTransfer.velocity(), deferredTransfer.yaw(), deferredTransfer.shadingQuarterTurns());
         }
         this.route(player);
         if (!this.pending.containsKey(player.getUUID())) this.maybeRebaseApron(player);
         // Cut-edge terrain is now materialized as native LevelChunks by
         // CubeNetChunkGenerator. Do not send the old render-only preview:
         // it bypassed vanilla light, fluid, block-entity, and model paths.
      }
      this.tickEntities();
      if (this.smoke != null) {
         this.smoke.tick(this::route, this.pending::containsKey);
         if (this.smoke.finished()) {
            this.cancel(this.smoke.player());
            this.smoke.finish();
            this.smoke = null;
         }
      }
      this.retryAfter.entrySet().removeIf(entry -> entry.getValue() < server.getTickCount());
   }

   private void prepareApproach(ServerPlayer player) {
      UUID id = player.getUUID();
      if (this.pending.containsKey(id) || this.entityTransfers.containsKey(id)) return;
      if (player.serverLevel() != this.level || !(player.serverLevel().getChunkSource().getGenerator()
         instanceof CubeNetChunkGenerator generator) || !generator.nativeApronEnabled()
         || !ServerPlayNetworking.canSend(player, CubeNetTransitionPreparePayload.TYPE)
         || this.retryAfter.getOrDefault(id, 0) > player.server.getTickCount()) {
         this.cancelPreparation(id, player);
         return;
      }
      Entity observer = player.getRootVehicle();
      var approach = CubeNetSeamApproach.find(generator.adapter().layout(), generator.apronResolver(),
         observer.getX(), observer.getZ(), observer.getYRot(), 192, APRON_REBASE_DEPTH_BLOCKS).orElse(null);
      Preparation old = this.preparations.get(id);
      if (approach == null) {
         this.cancelPreparation(id, player);
         return;
      }
      ChunkPos center = new ChunkPos(approach.chunkX(), approach.chunkZ());
      int radius = this.transferRadius(player, generator);
      if (radius < 0) {
         this.cancelPreparation(id, player);
         this.retryAfter.put(id, player.server.getTickCount() + 200);
         return;
      }
      if (old != null && (!old.center().equals(center) || old.radius() != radius
         || !old.seam().equals(approach.seam()) || !old.rootId().equals(observer.getUUID())
         || preparationTimedOut(old.clientReady(), player.server.getTickCount() - old.startedTick()))) {
         this.cancelPreparation(id, player);
         old = null;
      }
      if (old == null) {
         if (!admitsTransferArea(generator, center, radius)) return;
         UUID transitionId = UUID.randomUUID();
         this.level.getChunkSource().addRegionTicket(CUBE_NET_TRANSFER, center, radius, transitionId);
         old = new Preparation(this.level, center, radius, approach.seam(), observer.getUUID(), transitionId,
            loadTransferArea(this.level, center, radius), new CompletableFuture<>(), new ClientChunkDelivery(),
            player.server.getTickCount(), System.nanoTime());
         this.preparations.put(id, old);
         ServerPlayNetworking.send(player, new CubeNetTransitionPreparePayload(
            transitionId, center.x, center.z, radius, false,
            shadingQuarterTurns(generator.adapter().layout(), approach.seam())));
         TellusCommon.LOGGER.info("Cube-net approach preparing {} source={} distance={} center={} radius={}",
            transitionId, observer.position(), approach.distance(), center, radius);
      }
      if (old.ready().isCompletedExceptionally()) {
         this.cancelPreparation(id, player);
         this.retryAfter.put(id, player.server.getTickCount() + 200);
         return;
      }
      if (old.ready().isDone() && !old.clientReady().isDone()) {
         if (old.clientChunks().attempts() == 0) {
            TellusCommon.LOGGER.info("Cube-net approach loaded {} elapsedMs={}", old.transitionId(),
               (System.nanoTime() - old.startedNanos()) / 1_000_000L);
         }
         this.stageClientChunks(old.level(), center, old.clientChunks(), player, radius,
            player.server.getTickCount(), supportsChunkReceipt(player));
      }
   }

   static boolean preparationTimedOut(CompletableFuture<Void> clientReady, int elapsedTicks) {
      // This budget bounds unfinished loading/compilation. A successfully
      // staged chart is still owned by the same approach and its region
      // ticket; cancelling it just because the player paused can force a
      // full rebuild after they have already entered the apron.
      return elapsedTicks > LOAD_TIMEOUT_TICKS
         && (!clientReady.isDone() || clientReady.isCompletedExceptionally());
   }

   private Preparation takePreparation(ServerPlayer player, ChunkPos center, int radius) {
      Preparation preparation = this.preparations.get(player.getUUID());
      if (preparation == null) return null;
      if (preparation.level() != player.serverLevel() || !preparation.center().equals(center)
         || preparation.radius() != radius || !preparation.rootId().equals(player.getRootVehicle().getUUID())) {
         this.cancelPreparation(player.getUUID(), player);
         return null;
      }
      this.preparations.remove(player.getUUID());
      preparation.level().getChunkSource().removeRegionTicket(CUBE_NET_TRANSFER,
         preparation.center(), preparation.radius(), preparation.transitionId());
      TellusCommon.LOGGER.info("Cube-net approach consumed {} ready={} source={}", preparation.transitionId(),
         preparation.clientReady().isDone(), player.getRootVehicle().position());
      return preparation;
   }

   private void cancelPreparation(UUID id, ServerPlayer player) {
      Preparation preparation = this.preparations.remove(id);
      if (preparation == null) return;
      preparation.level().getChunkSource().removeRegionTicket(CUBE_NET_TRANSFER,
         preparation.center(), preparation.radius(), preparation.transitionId());
      if (player != null && ServerPlayNetworking.canSend(player, CubeNetTransitionCancelPayload.TYPE)) {
         ServerPlayNetworking.send(player, new CubeNetTransitionCancelPayload(preparation.transitionId()));
      }
      TellusCommon.LOGGER.info("Cube-net approach cancelled {}", preparation.transitionId());
   }

   private void maybeRebaseApron(ServerPlayer player) {
      if (this.level != player.serverLevel()
         || !(player.serverLevel().getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)
         || !generator.nativeApronEnabled() || player.isPassenger() || player.isVehicle()
         || this.retryAfter.getOrDefault(player.getUUID(), 0) > player.server.getTickCount()
         || generator.apronProjection(new ChunkPos(player.blockPosition())).isEmpty()) return;
      var apron = generator.apronResolver().resolve(player.getX(), player.getZ()).orElse(null);
      int rebaseDepth = Math.min(APRON_REBASE_DEPTH_BLOCKS, generator.apronWidthChunks() * 16 - 8);
      if (apron == null || apron.penetration() < rebaseDepth) return;
      CubeNetLayout layout = generator.adapter().layout();
      CubeNetLayout.FacePoint target = new CubeNetLayout.FacePoint(apron.targetFace(), apron.targetU(), apron.targetV());
      double[] destination = planar(layout, target);
      double[] planarVelocity = rebaseVelocity(layout, apron, player.getDeltaMovement());
      float yaw = rebaseYaw(layout, apron, player.getYRot());
      DeferredTransfer deferredTransfer = new DeferredTransfer(destination[0], player.getY(), destination[1],
         new Vec3(planarVelocity[0], player.getDeltaMovement().y, planarVelocity[1]), yaw,
         shadingQuarterTurns(layout, layout.seam(apron.sourceFace(), apron.sourceSide())));
      if (this.deferred.putIfAbsent(player.getUUID(), deferredTransfer) == null) {
         this.transitionStates.put(player.getUUID(), TransitionState.PREPARE);
         TellusCommon.LOGGER.info("Cube-net apron rebase deferred for {} source=({}, {}) target=({}, {})",
            player.getGameProfile().getName(), player.getX(), player.getZ(), destination[0], destination[1]);
         this.hold(player);
      }
   }

   /**
    * Non-player entities share the native apron chart with players, but must
    * preload their canonical destination before crossing into its ownership.
    * A non-player vehicle and its complete passenger tree are staged as one
    * unit. A bounded local leash component can join that unit; player mounts
    * and external leash endpoints remain unsupported.
    */
   private void tickEntities() {
      ServerLevel currentLevel = this.level;
      if (currentLevel == null
         || !(currentLevel.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)
         || !generator.nativeApronEnabled()) return;

      List<Entity> entities = new ArrayList<>();
      for (Entity entity : currentLevel.getAllEntities()) entities.add(entity);
      Set<UUID> handled = new HashSet<>();
      for (Entity entity : entities) {
         if (entity instanceof ServerPlayer || entity.isRemoved() || entity.level() != currentLevel
            || entity.isPassenger() || handled.contains(entity.getUUID())) continue;
         EntityTransfer transfer = this.entityTransfers.get(entity.getUUID());
         if (transfer != null) {
            handled.addAll(transfer.members().stream().map(member -> member.entity().getUUID()).toList());
            this.routeEntity(transfer.entity(), transfer);
         } else {
            this.maybeRebaseEntity(entity, generator);
         }
      }
      for (EntityTransfer transfer : new HashSet<>(this.entityTransfers.values())) {
         if (transfer.entity().isRemoved() || transfer.entity().level() != transfer.level()) {
            this.failEntity(transfer, "vehicle was removed or changed level");
         }
      }
   }

   private void maybeRebaseEntity(Entity entity, CubeNetChunkGenerator generator) {
      if (entity.isPassenger()
         || this.retryAfter.getOrDefault(entity.getUUID(), 0) > entity.level().getServer().getTickCount()
         ) return;
      if (generator.apronProjection(new ChunkPos(entity.blockPosition())).isEmpty()) {
         this.entityRebaseHysteresis.remove(entity.getUUID());
         return;
      }
      var apron = generator.apronResolver().resolve(entity.getX(), entity.getZ()).orElse(null);
      if (apron == null) {
         this.entityRebaseHysteresis.remove(entity.getUUID());
         return;
      }
      if (this.entityRebaseHysteresis.contains(entity.getUUID())) {
         if (apron.penetration() > 0.0) return;
         this.entityRebaseHysteresis.remove(entity.getUUID());
      }
      int rebaseDepth = Math.min(APRON_REBASE_DEPTH_BLOCKS, generator.apronWidthChunks() * 16 - 8);
      if (apron.penetration() < rebaseDepth) return;

      ServerLevel target = (ServerLevel)entity.level();
      List<Entity> group = transferGroup(entity, target);
      if (group == null) return;
      ServerPlayer mountedPlayer = group.stream().filter(ServerPlayer.class::isInstance)
         .map(ServerPlayer.class::cast).findFirst().orElse(null);
      int radius = this.transferRadius(mountedPlayer, generator);
      if (radius < 0) {
         this.deferEntities(group, target.getServer().getTickCount() + 200);
         return;
      }
      List<LeashLink> leashes = leashLinks(group);
      if (leashes == null) return;
      CubeNetLayout layout = generator.adapter().layout();
      CubeNetLayout.FacePoint targetPoint = new CubeNetLayout.FacePoint(
         apron.targetFace(), apron.targetU(), apron.targetV());
      double[] destination = planar(layout, targetPoint);
      List<EntityPose> members = new ArrayList<>();
      for (Entity member : group) {
         double[] offset = rebaseVelocity(layout, apron, new Vec3(
            member.getX() - entity.getX(), 0.0, member.getZ() - entity.getZ()));
         double[] velocity = rebaseVelocity(layout, apron, member.getDeltaMovement());
         members.add(new EntityPose(member, member.getVehicle(),
            destination[0] + offset[0], member.getY(), destination[1] + offset[1],
            new Vec3(velocity[0], member.getDeltaMovement().y, velocity[1]),
            rebaseYaw(layout, apron, member.getYRot()), member.getX(), member.getY(), member.getZ(),
            member.getYRot(), member.getXRot(), member.getDeltaMovement(), member.fallDistance));
      }
      List<ChunkPos> chunks = members.stream().map(member -> new ChunkPos(
         (int)Math.floor(member.x() / 16.0), (int)Math.floor(member.z() / 16.0))).distinct().toList();
      if (chunks.stream().anyMatch(chunk -> !admitsTransferArea(generator, chunk, radius))) {
         this.deferEntities(group, target.getServer().getTickCount() + 200);
         return;
      }

      UUID id = entity.getUUID();
      chunks.forEach(chunk -> target.getChunkSource().addRegionTicket(CUBE_NET_TRANSFER, chunk, radius, id));
      CompletableFuture<Void> ready = CompletableFuture.allOf(chunks.stream()
         .map(chunk -> loadTransferArea(target, chunk, radius)).toArray(CompletableFuture[]::new));
      if (mountedPlayer != null && group.stream().filter(ServerPlayer.class::isInstance).count() > 1) {
         this.deferEntities(group, target.getServer().getTickCount() + 200);
         chunks.forEach(chunk -> target.getChunkSource().removeRegionTicket(CUBE_NET_TRANSFER, chunk, radius, id));
         return;
      }
      ChunkPos clientCenter = new ChunkPos((int)Math.floor(destination[0] / 16.0),
         (int)Math.floor(destination[1] / 16.0));
      if (mountedPlayer != null && chunks.stream().anyMatch(chunk ->
         Math.abs(chunk.x - clientCenter.x) > radius - 1
            || Math.abs(chunk.z - clientCenter.z) > radius - 1)) {
         this.deferEntities(group, target.getServer().getTickCount() + 200);
         chunks.forEach(chunk -> target.getChunkSource().removeRegionTicket(CUBE_NET_TRANSFER, chunk, radius, id));
         return;
      }
      Preparation preparation = mountedPlayer == null ? null : this.takePreparation(mountedPlayer, clientCenter, radius);
      UUID transitionId = preparation == null ? UUID.randomUUID() : preparation.transitionId();
      boolean clientStaging = mountedPlayer != null
         && ServerPlayNetworking.canSend(mountedPlayer, CubeNetTransitionPreparePayload.TYPE);
      CompletableFuture<Void> clientReady = preparation != null ? preparation.clientReady() : clientStaging
         ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
      EntityTransfer transfer = new EntityTransfer(target, entity, chunks,
         List.copyOf(members), List.copyOf(leashes), target.getServer().getTickCount(), ready,
         mountedPlayer == null ? null : mountedPlayer.getUUID(), transitionId, clientReady,
         preparation == null ? new ClientChunkDelivery() : preparation.clientChunks(), radius);
      List<UUID> registered = new ArrayList<>();
      for (Entity member : group) {
         EntityTransfer existing = this.entityTransfers.putIfAbsent(member.getUUID(), transfer);
         if (existing != null) {
            registered.forEach(memberId -> this.entityTransfers.remove(memberId, transfer));
            this.deferEntities(group, target.getServer().getTickCount() + 200);
            chunks.forEach(chunk -> target.getChunkSource().removeRegionTicket(CUBE_NET_TRANSFER, chunk, radius, id));
            return;
         }
         registered.add(member.getUUID());
      }
      if (registered.isEmpty()) {
         chunks.forEach(chunk -> target.getChunkSource().removeRegionTicket(CUBE_NET_TRANSFER, chunk, radius, id));
         return;
      }
      if (mountedPlayer != null) {
         this.transitionStates.put(mountedPlayer.getUUID(), TransitionState.STAGED);
         if (clientStaging) {
            ServerPlayNetworking.send(mountedPlayer, new CubeNetTransitionPreparePayload(
               transitionId, clientCenter.x, clientCenter.z, radius, true,
               shadingQuarterTurns(layout, layout.seam(apron.sourceFace(), apron.sourceSide()))));
         }
      }
      TellusCommon.LOGGER.info("Cube-net entity rebase staged for {} type={} members={} source=({}, {}) target=({}, {})",
         id, entity.getType(), members.size(), entity.getX(), entity.getZ(), destination[0], destination[1]);
      this.holdEntity(transfer);
   }

   private void routeEntity(Entity entity, EntityTransfer transfer) {
      List<Entity> currentGroup = transferGroup(entity, transfer.level());
      List<LeashLink> currentLeashes = currentGroup == null ? null : leashLinks(currentGroup);
      if (currentGroup == null || currentLeashes == null
         || currentGroup.size() != transfer.members().size() || transfer.members().stream().anyMatch(member ->
         member.entity().isRemoved() || member.entity().level() != transfer.level()
            || member.entity().getVehicle() != member.vehicle() || !currentGroup.contains(member.entity()))) {
         this.failEntity(transfer, "passenger relationships changed during chunk load");
         return;
      }
      if (!currentLeashes.equals(transfer.leashes())) {
         this.failEntity(transfer, "leash relationships changed during chunk load");
         return;
      }
      if (transfer.ready().isDone() && !transfer.clientReady().isDone()) {
         ServerPlayer mountedPlayer = transfer.members().stream()
            .map(EntityPose::entity).filter(ServerPlayer.class::isInstance)
            .map(ServerPlayer.class::cast).findFirst().orElse(null);
         this.stageClientChunks(transfer.level(), transfer.chunks().get(0),
            transfer.clientChunks(), mountedPlayer, transfer.radius(), entity.level().getServer().getTickCount(),
            supportsChunkReceipt(mountedPlayer));
      }
      if (transfer.ready().isDone() && transfer.clientReady().isDone()) {
         try {
            transfer.ready().join();
            // Check every member before moving any of them. A blocked passenger
            // must roll the entire tree back, rather than leave a split mount.
            for (EntityPose member : transfer.members()) {
               AABB targetBox = member.entity().getBoundingBox().move(
                  member.x() - member.entity().getX(), member.y() - member.entity().getY(),
                  member.z() - member.entity().getZ());
               if (!transfer.level().noCollision(member.entity(), targetBox)) {
                  this.failEntity(transfer, "destination is blocked");
                  return;
               }
            }
            for (EntityPose member : transfer.members()) {
               Entity moved = member.entity();
               moved.absMoveTo(member.x(), member.y(), member.z(), member.yaw(), member.sourcePitch());
               moved.setOldPosAndRot();
               if (moved instanceof Mob mob) mob.setYHeadRot(member.yaw());
               moved.setDeltaMovement(member.velocity());
               moved.hasImpulse = true;
               moved.fallDistance = member.fallDistance();
            }
            ServerPlayer mountedPlayer = transfer.playerId() == null ? null
               : transfer.members().stream().map(EntityPose::entity)
                  .filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast)
                  .findFirst().orElse(null);
            if (mountedPlayer != null) {
               // A locally controlled vehicle ignores TeleportEntity packets.
               // Move the retained root before correcting its player passenger.
               ((CubeNetVehicleMovementBaseline)mountedPlayer.connection).tellus$resetVehiclePosition(entity);
               mountedPlayer.connection.send(new ClientboundMoveVehiclePacket(entity));
               TellusCommon.LOGGER.info("Cube-net server vehicle correction root={} pos={} rider={}",
                  entity.getId(), entity.position(), mountedPlayer.getGameProfile().getName());
            }
            for (EntityPose member : transfer.members()) {
               if (member.entity() instanceof ServerPlayer player) {
                  player.connection.teleport(member.x(), member.y(), member.z(),
                     member.yaw(), member.sourcePitch());
                  player.connection.resetPosition();
                  this.transitionStates.put(player.getUUID(), TransitionState.COMMIT);
                  this.armCorrection(player, member.x(), member.y(), member.z(),
                     member.yaw(), member.sourcePitch());
               }
            }
            if (mountedPlayer != null
               && !ServerPlayNetworking.canSend(mountedPlayer, CubeNetTransitionPreparePayload.TYPE)) {
               for (EntityPose member : transfer.members()) {
                  if (!(member.entity() instanceof ServerPlayer)) {
                     // Moving the vanilla cache center can unload the source
                     // entity before the same-Level teleport arrives. A
                     // teleport packet cannot recreate an entity that is no
                     // longer in the client entity map; add it at the target
                     // pose before restoring the passenger relation.
                     mountedPlayer.connection.send(new ClientboundAddEntityPacket(member.entity()));
                     mountedPlayer.connection.send(new ClientboundTeleportEntityPacket(member.entity()));
                  }
               }
               for (EntityPose member : transfer.members()) {
                  if (!member.entity().getPassengers().isEmpty()) {
                     mountedPlayer.connection.send(new ClientboundSetPassengersPacket(member.entity()));
                  }
               }
            }
            restoreLeashes(transfer.leashes());
            if (transfer.playerId() != null) {
               this.transitionStates.put(transfer.playerId(), TransitionState.RELEASE);
            }
            this.entityRebaseHysteresis.add(entity.getUUID());
            EntityPose root = transfer.members().get(0);
            TellusCommon.LOGGER.info("Cube-net entity rebase complete for {} members={} target=({}, {}, {})",
               entity.getUUID(), transfer.members().size(), root.x(), root.y(), root.z());
            this.cancelEntity(transfer);
         } catch (RuntimeException error) {
            this.failEntity(transfer, "destination chunks could not be loaded");
            TellusCommon.LOGGER.error("Cube-net entity rebase failed for {}", entity.getUUID(), error);
         }
      } else if (entity.level().getServer().getTickCount() - transfer.startedTick() >= LOAD_TIMEOUT_TICKS) {
         this.failEntity(transfer, transfer.ready().isDone()
            ? "client destination chunk staging timed out"
            : "destination chunk load timed out");
      } else {
         this.holdEntity(transfer);
      }
   }

   private void holdEntity(EntityTransfer transfer) {
      for (EntityPose member : transfer.members()) {
         member.entity().absMoveTo(member.sourceX(), member.sourceY(), member.sourceZ(),
            member.sourceYaw(), member.sourcePitch());
         member.entity().setDeltaMovement(Vec3.ZERO);
         if (member.entity() instanceof ServerPlayer player) {
            player.connection.teleport(member.sourceX(), member.sourceY(), member.sourceZ(),
               member.sourceYaw(), member.sourcePitch());
         }
      }
   }

   private void failEntity(EntityTransfer transfer, String reason) {
      Entity entity = transfer.entity();
      for (EntityPose member : transfer.members()) {
         Entity restored = member.entity();
         if (restored.isRemoved() || restored.level() != transfer.level()) continue;
         restored.absMoveTo(member.sourceX(), member.sourceY(), member.sourceZ(),
            member.sourceYaw(), member.sourcePitch());
         restored.setOldPosAndRot();
         restored.setDeltaMovement(member.sourceVelocity());
         restored.hasImpulse = true;
         restored.fallDistance = member.fallDistance();
          if (restored instanceof ServerPlayer player) {
             player.connection.teleport(member.sourceX(), member.sourceY(), member.sourceZ(),
                member.sourceYaw(), member.sourcePitch());
             player.connection.resetPosition();
          }
      }
      restoreLeashes(transfer.leashes());
      this.deferEntities(transfer.members().stream().map(EntityPose::entity).toList(),
         transfer.level().getServer().getTickCount() + 200);
      TellusCommon.LOGGER.warn("Cube-net entity rebase cancelled for {}: {}", entity.getUUID(), reason);
      this.cancelEntity(transfer);
   }

   private void cancelEntity(EntityTransfer transfer) {
      if (transfer.playerId() != null) {
         ServerPlayer player = transfer.level().getServer().getPlayerList().getPlayer(transfer.playerId());
         if (player != null && ServerPlayNetworking.canSend(player, CubeNetTransitionCancelPayload.TYPE)) {
            ServerPlayNetworking.send(player, new CubeNetTransitionCancelPayload(transfer.transitionId()));
         }
      }
      this.entityTransfers.entrySet().removeIf(entry -> entry.getValue() == transfer);
      transfer.chunks().forEach(chunk -> transfer.level().getChunkSource().removeRegionTicket(
         CUBE_NET_TRANSFER, chunk, transfer.radius(), transfer.entity().getUUID()));
      if (transfer.playerId() != null && !this.correctionGates.containsKey(transfer.playerId())) {
         this.transitionStates.remove(transfer.playerId());
      }
   }

   private void deferEntities(List<Entity> entities, int tick) {
      for (Entity member : entities) this.retryAfter.put(member.getUUID(), tick);
   }

   /**
    * Builds a closed local component for one crossing. A leash endpoint that
    * cannot move with the root invalidates the whole transfer rather than
    * leaving a dangling leash on the source chart.
    */
   private static List<Entity> transferGroup(Entity root, ServerLevel level) {
      if (root instanceof ServerPlayer || root.isRemoved() || root.level() != level
         || root.isPassenger()) return null;
      LinkedHashSet<Entity> group = new LinkedHashSet<>(passengerTree(root));
      List<Entity> all = new ArrayList<>();
      for (Entity entity : level.getAllEntities()) all.add(entity);
      boolean changed;
      do {
         changed = false;
         for (Entity member : List.copyOf(group)) {
            if (!validBundleMember(member, root, level)) return null;
            if (member instanceof Mob mob && mob.isLeashed()) {
               Entity holder = mob.getLeashHolder();
               if (!validLeashEndpoint(holder, root, level)) return null;
               changed |= addBundleTree(holder, root, group);
            }
         }
         for (Entity candidate : all) {
            if (!(candidate instanceof Mob mob) || !mob.isLeashed()) continue;
            Entity holder = mob.getLeashHolder();
            if (holder != null && group.contains(holder)) {
               if (!group.contains(candidate) && (candidate.isPassenger()
                  || !validLeashEndpoint(candidate, root, level))) return null;
               changed |= addBundleTree(candidate, root, group);
            }
         }
      } while (changed);
      for (Entity member : group) {
         if (!validBundleMember(member, root, level)) return null;
         if (member.isPassenger() && !group.contains(member.getVehicle())) return null;
      }
      return List.copyOf(group);
   }

   private static boolean addBundleTree(Entity candidate, Entity root, Set<Entity> group) {
      if (candidate == null || candidate.isPassenger()
         || root.position().distanceToSqr(candidate.position()) > MAX_LEASH_BUNDLE_DISTANCE_SQUARED) return false;
      boolean changed = false;
      for (Entity member : passengerTree(candidate)) {
         if (group.add(member)) changed = true;
      }
      return changed;
   }

   private static boolean validBundleMember(Entity entity, Entity root, ServerLevel level) {
      if (entity == null || entity.isRemoved() || entity.level() != level
         || root.position().distanceToSqr(entity.position()) > MAX_LEASH_BUNDLE_DISTANCE_SQUARED) return false;
      if (entity instanceof ServerPlayer) return entity != root && isPassengerOf(entity, root);
      return true;
   }

   private static boolean validLeashEndpoint(Entity entity, Entity root, ServerLevel level) {
      return validBundleMember(entity, root, level) && !(entity instanceof ServerPlayer);
   }

   private static List<LeashLink> leashLinks(List<Entity> group) {
      Set<Entity> members = Set.copyOf(group);
      List<LeashLink> result = new ArrayList<>();
      for (Entity entity : group) {
         if (!(entity instanceof Mob mob) || !mob.isLeashed()) continue;
         Entity holder = mob.getLeashHolder();
         if (holder == null || !members.contains(holder)) return null;
         result.add(new LeashLink(mob, holder));
      }
      return List.copyOf(result);
   }

   private static void restoreLeashes(List<LeashLink> leashes) {
      for (LeashLink leash : leashes) {
         if (!leash.mob().isRemoved() && !leash.holder().isRemoved()
            && leash.mob().level() == leash.holder().level()) {
            leash.mob().setLeashedTo(leash.holder(), true);
         }
      }
   }

   private static List<Entity> passengerTree(Entity root) {
      List<Entity> result = new ArrayList<>();
      addPassengers(root, result);
      return result;
   }

   private static boolean isPassengerOf(Entity entity, Entity root) {
      Entity vehicle = entity.getVehicle();
      while (vehicle != null) {
         if (vehicle == root) return true;
         vehicle = vehicle.getVehicle();
      }
      return false;
   }

   private static void addPassengers(Entity entity, List<Entity> result) {
      result.add(entity);
      for (Entity passenger : entity.getPassengers()) addPassengers(passenger, result);
   }

   private void route(ServerPlayer player) {
      Transfer transfer = this.pending.get(player.getUUID());
      if (transfer == null) return;
      if (player.isRemoved() || player.serverLevel() != transfer.level()) {
         this.cancel(player);
         return;
      }
      if (player.isPassenger() || player.isVehicle()) {
         this.fail(player, "Cube-net seam transfer does not support mounts");
         return;
      }
      if (transfer.ready().isDone() && !transfer.clientReady().isDone()) {
         this.stageClientChunks(transfer.level(), transfer.chunk(), transfer.clientChunks(), player,
            transfer.radius(), player.server.getTickCount(), supportsChunkReceipt(player));
      }
      if (transfer.ready().isDone() && transfer.clientReady().isDone()) {
         try {
            transfer.ready().join();
            this.transitionStates.put(player.getUUID(), TransitionState.COMMIT);
            AABB targetBox = SphereFaceRuntime.destinationBox(player.getBoundingBox(),
               transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(), transfer.x(), transfer.y(), transfer.z());
            if (!transfer.level().noCollision(player, targetBox)) {
               this.fail(player, "Cube-net seam destination is blocked");
               return;
            }
            transfer.level().getChunkSource().addRegionTicket(TicketType.POST_TELEPORT,
               transfer.chunk(), 2, player.getId());
            boolean atomicCommit = ServerPlayNetworking.canSend(player, CubeNetTransitionCommitPayload.TYPE)
               && ServerPlayNetworking.canSend(player, CubeNetTransitionPreparePayload.TYPE);
            if (atomicCommit) {
               // The client already has the destination chart staged. Move the
               // server entity and send one authoritative commit record; the
               // client installs the chart and pose in one main-thread turn.
               // This removes the vanilla teleport-confirm round trip from the
               // normal path while retaining the old packet path below for
               // clients without the RFC extension.
               player.absMoveTo(transfer.x(), transfer.y(), transfer.z(),
                  transfer.yaw(), transfer.sourcePitch());
            } else {
               player.teleportTo(transfer.level(), transfer.x(), transfer.y(), transfer.z(),
                  transfer.yaw(), transfer.sourcePitch());
               // Same-level teleports do not necessarily produce a client
               // position confirmation through Entity#teleportTo alone. Send
               // the authoritative destination through the connection before
               // releasing the transfer gate, otherwise stale source movement
               // packets can be validated against the new face and repeatedly
               // trigger vanilla "moved too quickly" corrections.
               player.connection.teleport(transfer.x(), transfer.y(), transfer.z(),
                  transfer.yaw(), transfer.sourcePitch());
            }
            // The vanilla movement validator tracks a separate last-tick
            // position in the connection handler. A chart rebase changes the
            // player position without ordinary movement, so reset that
            // baseline before the next packet is checked.
            player.connection.resetPosition();
            player.setDeltaMovement(transfer.velocity());
            player.fallDistance = transfer.fallDistance();
            player.hurtMarked = true;
            this.markMotionCommit(player, transfer);
            if (atomicCommit) {
               ServerPlayNetworking.send(player, new CubeNetTransitionCommitPayload(
                  transfer.transitionId(), transfer.x(), transfer.y(), transfer.z(),
                  transfer.yaw(), transfer.sourcePitch(), transfer.velocity(), transfer.fallDistance(),
                  transfer.shadingQuarterTurns()));
            }
            this.armCorrection(player, transfer.x(), transfer.y(), transfer.z(),
               transfer.yaw(), transfer.sourcePitch(), !atomicCommit,
               atomicCommit ? transfer.transitionId() : null);
            this.transitionStates.put(player.getUUID(), TransitionState.RELEASE);
            TellusCommon.LOGGER.info("Cube-net seam transfer complete for {} target=({}, {}, {})",
               player.getGameProfile().getName(), transfer.x(), transfer.y(), transfer.z());
            this.cancel(player);
         } catch (RuntimeException error) {
            this.fail(player, "Cube-net seam destination could not be loaded");
            TellusCommon.LOGGER.error("Cube-net seam transfer failed", error);
         }
      } else if (player.server.getTickCount() - transfer.startedTick() >= LOAD_TIMEOUT_TICKS) {
         this.fail(player, transfer.ready().isDone()
            ? "Cube-net client destination chunk staging timed out"
            : "Cube-net seam destination load timed out");
      } else {
         this.hold(player);
      }
   }

   private void begin(ServerPlayer player, double x, double y, double z, Vec3 velocity, float yaw,
      int shadingQuarterTurns) {
      ServerLevel target = player.serverLevel();
      ChunkPos chunk = new ChunkPos((int)Math.floor(x / 16.0), (int)Math.floor(z / 16.0));
      int radius = target.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator cubeNet
         ? this.transferRadius(player, cubeNet) : TRANSFER_RADIUS_CHUNKS;
      if (radius < 0) {
         this.retryAfter.put(player.getUUID(), player.server.getTickCount() + 200);
         return;
      }
      if (target.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator
         && !admitsTransferArea(generator, chunk, radius)) {
         this.retryAfter.put(player.getUUID(), player.server.getTickCount() + 200);
         this.hold(player);
         player.sendSystemMessage(Component.literal("Cube-net seam destination is outside ordinary terrain"));
         return;
      }
      UUID id = player.getUUID();
      target.getChunkSource().addRegionTicket(CUBE_NET_TRANSFER, chunk, radius, id);
      CompletableFuture<Void> ready = loadTransferArea(target, chunk, radius);
      Preparation preparation = this.takePreparation(player, chunk, radius);
      UUID transitionId = preparation == null ? UUID.randomUUID() : preparation.transitionId();
      boolean clientStaging = ServerPlayNetworking.canSend(player, CubeNetTransitionPreparePayload.TYPE);
      CompletableFuture<Void> clientReady = preparation != null ? preparation.clientReady() : clientStaging
         ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
      var transfer = new Transfer(target, x, y, z, chunk, velocity, yaw, shadingQuarterTurns,
         player.server.getTickCount(),
         player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
         player.getDeltaMovement(), player.fallDistance, ready, transitionId, clientReady,
         preparation == null ? new ClientChunkDelivery() : preparation.clientChunks(), radius);
      this.pending.put(id, transfer);
      this.transitionStates.put(id, TransitionState.STAGED);
      if (clientStaging) {
         ServerPlayNetworking.send(player, new CubeNetTransitionPreparePayload(transitionId,
            chunk.x, chunk.z, radius, true, shadingQuarterTurns));
      }
      this.hold(player, true);
   }

   /**
    * The async load includes the configured view and render halo. Canonical chunks in a
    * player transfer area must be ordinary material; empty positions are accepted because
    * the generator may resolve them as native apron chunks. Transition-buffer terrain is
    * generated normally but is never an admissible player destination.
    */
   private static boolean admitsTransferArea(CubeNetChunkGenerator generator, ChunkPos center, int radius) {
      SphereSingularityPolicy policy = new SphereSingularityPolicy(generator.model(), generator.singularityConfig());
      for (int dz = -radius; dz <= radius; dz++)
         for (int dx = -radius; dx <= radius; dx++) {
         var address = generator.address(new ChunkPos(center.x + dx, center.z + dz));
         if (address.isPresent() && !policy.admitsOrdinaryMaterialization(address.get())) return false;
      }
      return true;
   }

   private static CompletableFuture<Void> loadTransferArea(ServerLevel level, ChunkPos center, int radius) {
      List<CompletableFuture<?>> loads = new ArrayList<>();
      for (int dz = -radius; dz <= radius; dz++)
         for (int dx = -radius; dx <= radius; dx++) {
         int cx = center.x + dx, cz = center.z + dz;
         loads.add(CompletableFuture.supplyAsync(
            () -> {
               try {
                  CHUNK_LOAD_BUDGET.acquire();
               } catch (InterruptedException error) {
                  Thread.currentThread().interrupt();
                  throw new java.util.concurrent.CompletionException(error);
               }
               return level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true);
            },
            Util.backgroundExecutor()).thenCompose(future -> future).thenAccept(result -> {
               if (result.left().isEmpty()) {
                  throw new IllegalStateException("destination chunk unavailable: " + result.right());
               }
            }).whenComplete((ignored, error) -> releaseChunkLoadBudget()));
      }
      return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
   }

   private static void releaseChunkLoadBudget() {
      CHUNK_LOAD_BUDGET.release();
   }

   /**
    * The player is still held at the source position while the target chunks
    * are staged. Vanilla's client cache rejects packets outside its current
    * cache center, so move the center first and then send the already-loaded
    * target chunks through the normal chunk packet path.
    */
   private void stageClientChunks(ServerLevel level, ChunkPos center,
      ClientChunkDelivery delivery, ServerPlayer player, int radius, int tick, boolean retrySupported) {
      if (player == null || !delivery.isDue(tick, retrySupported)
         || !CubeNetApronChunkSync.readyForStaging(level, center, radius)) return;
      int attempt = delivery.beginAttempt(tick, retrySupported, center, radius);
      if (attempt < 0) return;
      if (delivery.consumeCenterPacket()) {
         player.connection.send(new ClientboundSetChunkCacheCenterPacket(center.x, center.z));
      }
      int packetsSent = 0;
      while (packetsSent < MAX_CHUNK_PACKETS_PER_TICK) {
         ChunkPos next = delivery.nextChunk();
         if (next == null) break;
         LevelChunk chunk = (LevelChunk) level.getChunkSource().getChunk(next.x, next.z, true);
         player.connection.send(new ClientboundLevelChunkWithLightPacket(
            chunk, level.getChunkSource().getLightEngine(), null, null));
         delivery.advance();
         packetsSent++;
      }
      if (delivery.finishedAttempt()) {
         TellusCommon.LOGGER.info("Cube-net client destination chunks staged for {} center=({}, {}) radius={} "
               + "attempt={} packetsThisTick={} packetBudget={} loadTaskBudget={} estimatedLoadBytes={}",
            player.getGameProfile().getName(), center.x, center.z, radius, attempt, packetsSent,
            MAX_CHUNK_PACKETS_PER_TICK, MAX_CONCURRENT_CHUNK_LOADS, MAX_CHUNK_LOAD_BYTES);
      }
   }

   private static boolean supportsChunkReceipt(ServerPlayer player) {
      return player != null && ServerPlayNetworking.canSend(player, CubeNetTransitionChunksReceivedPayload.TYPE);
   }

   private int transferRadius(ServerPlayer player, CubeNetChunkGenerator generator) {
      if (player == null || !ServerPlayNetworking.canSend(player, CubeNetTransitionPreparePayload.TYPE)) {
         return TRANSFER_RADIUS_CHUNKS;
      }
      var players = player.server.getPlayerList();
      CubeNetViewCoverage coverage = CubeNetViewCoverage.forDistances(
         ((CubeNetClientViewDistance)player).tellus$requestedViewDistance(),
         players.getViewDistance(), players.getSimulationDistance(), APRON_REBASE_DEPTH_BLOCKS);
      if (generator.apronWidthChunks() < coverage.requiredApronChunks()) {
         player.sendSystemMessage(Component.literal("Cube-net crossing requires an apron of at least "
            + coverage.requiredApronChunks() + " chunks for this view/simulation distance; this world has "
            + generator.apronWidthChunks()));
         return -1;
      }
      return coverage.dataRadius();
   }

   private void fail(ServerPlayer player, String message) {
      Transfer transfer = this.pending.get(player.getUUID());
      if (transfer != null && player.serverLevel() == transfer.level()) {
         player.teleportTo(transfer.level(), transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(),
            transfer.sourceYaw(), transfer.sourcePitch());
         player.connection.teleport(transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(),
            transfer.sourceYaw(), transfer.sourcePitch());
         player.setDeltaMovement(transfer.sourceVelocity());
         player.fallDistance = transfer.fallDistance();
         player.hurtMarked = true;
      }
      this.cancel(player);
      this.transitionStates.remove(player.getUUID());
      this.retryAfter.put(player.getUUID(), player.server.getTickCount() + 200);
      player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
   }

   private void hold(ServerPlayer player) {
      this.hold(player, false);
   }

   private void hold(ServerPlayer player, boolean syncClient) {
      Transfer transfer = this.pending.get(player.getUUID());
      if (transfer == null) {
         player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
      } else {
         // Keep the authoritative entity at the snapshot too; a connection
         // teleport alone only corrects the client and leaves packet updates
         // able to move the server-side entity during the async load.
         player.absMoveTo(transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(),
            transfer.sourceYaw(), transfer.sourcePitch());
         if (syncClient) {
            player.connection.teleport(transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(),
               transfer.sourceYaw(), transfer.sourcePitch());
         }
         player.fallDistance = transfer.fallDistance();
      }
      player.setDeltaMovement(Vec3.ZERO);
   }

   private void armCorrection(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
      this.armCorrection(player, x, y, z, yaw, pitch, true, null);
   }

   private void armCorrection(ServerPlayer player, double x, double y, double z, float yaw, float pitch,
      boolean vanillaTeleportIssued, UUID transitionId) {
      CubeNetTeleportTracker tracker = (CubeNetTeleportTracker)player.connection;
      int teleportId = vanillaTeleportIssued ? tracker.tellus$awaitingTeleportId() : -1;
      // The seam gate below validates destination-chart movement itself. Do
      // not make the client wait for the extra vanilla teleport-ack round trip
      // before it can send ordinary movement for the already-installed chart.
      tracker.tellus$clearAwaitingTeleport();
      this.correctionGates.put(player.getUUID(), new Correction(x, y, z,
         player.server.getTickCount() + CORRECTION_GATE_TICKS, yaw, pitch, teleportId, transitionId, false,
         player.getDeltaMovement(), player.onGround(), player.fallDistance, player.server.getTickCount()));
      this.counters(player.getUUID()).sent++;
      TellusCommon.LOGGER.info(
         "Cube-net seam correction armed player={} tick={} mechanism={} teleportId={} vanillaWaitReleased=true "
            + "awaitingPositionAfterRelease={} target=({}, {}, {})",
         player.getGameProfile().getName(), player.server.getTickCount(),
         vanillaTeleportIssued ? "vanilla-teleport" : "atomic-custom", teleportId,
         tracker.tellus$awaitingPositionFromClient(), x, y, z);
   }

   private void holdCorrection(ServerPlayer player, Correction correction) {
      player.absMoveTo(correction.x(), correction.y(), correction.z(), correction.yaw(), correction.pitch());
      player.connection.teleport(correction.x(), correction.y(), correction.z(), correction.yaw(), correction.pitch());
      ((CubeNetTeleportTracker)player.connection).tellus$clearAwaitingTeleport();
      player.connection.resetPosition();
      player.setDeltaMovement(Vec3.ZERO);
      this.counters(player.getUUID()).resent++;
   }

   private void cancel(ServerPlayer player) {
      Transfer transfer = this.pending.remove(player.getUUID());
      if (transfer != null && ServerPlayNetworking.canSend(player, CubeNetTransitionCancelPayload.TYPE)) {
         ServerPlayNetworking.send(player, new CubeNetTransitionCancelPayload(transfer.transitionId()));
      }
      if (transfer != null) transfer.level().getChunkSource().removeRegionTicket(
         CUBE_NET_TRANSFER, transfer.chunk(), transfer.radius(), player.getUUID());
      this.retryAfter.remove(player.getUUID());
      if (!this.correctionGates.containsKey(player.getUUID())) {
         this.transitionStates.remove(player.getUUID());
      }
   }

   private void clear() {
      List.copyOf(this.preparations.keySet()).forEach(id -> this.cancelPreparation(id, null));
      if (this.smoke != null) { this.smoke.cleanup(); this.smoke = null; }
      this.pending.forEach((id, transfer) -> transfer.level().getChunkSource().removeRegionTicket(
         CUBE_NET_TRANSFER, transfer.chunk(), transfer.radius(), id));
      List.copyOf(this.entityTransfers.values()).forEach(this::cancelEntity);
      this.pending.clear();
      this.entityTransfers.clear();
      this.deferred.clear();
      this.retryAfter.clear();
      this.entityRebaseHysteresis.clear();
      this.correctionGates.clear();
      this.transitionStates.clear();
      this.correctionCounters.clear();
      this.boundaryHintShown.clear();
      this.nextBoundaryRibbonTick.clear();
      this.level = null;
   }

   private void disconnect(ServerPlayer player) {
      this.cancelPreparation(player.getUUID(), player);
      this.cancel(player);
      EntityTransfer entityTransfer = this.entityTransfers.get(player.getUUID());
      if (entityTransfer != null) this.cancelEntity(entityTransfer);
      this.correctionGates.remove(player.getUUID());
      this.transitionStates.remove(player.getUUID());
      this.boundaryHintShown.remove(player.getUUID());
      this.nextBoundaryRibbonTick.remove(player.getUUID());
   }

   private CorrectionCounters counters(UUID id) {
      return this.correctionCounters.computeIfAbsent(id, ignored -> new CorrectionCounters());
   }

   private String correctionSummary() {
      long sent = 0, resent = 0, acknowledged = 0, rejected = 0, expired = 0;
      for (CorrectionCounters counters : this.correctionCounters.values()) {
         sent += counters.sent;
         resent += counters.resent;
         acknowledged += counters.acknowledged;
         rejected += counters.rejected;
         expired += counters.expired;
      }
      return "sent=" + sent + ", resent=" + resent + ", acknowledged=" + acknowledged
         + ", rejected=" + rejected + ", expired=" + expired;
   }

   private int armMotionTrace(ServerPlayer player) {
      UUID id = player.getUUID();
      MotionCapture capture = new MotionCapture(player.server.getTickCount());
      this.motionCaptures.put(id, capture);
      player.sendSystemMessage(Component.literal(
         "Motion trace armed: crossing samples will be written to the server log"));
      TellusCommon.LOGGER.info("Cube-net motion trace armed player={} tick={} position={} onGround={}",
         player.getGameProfile().getName(), capture.armedTick, player.position(), player.onGround());
      return 1;
   }

   private void markMotionCommit(ServerPlayer player, Transfer transfer) {
      MotionCapture capture = this.motionCaptures.get(player.getUUID());
      if (capture == null || capture.committed) return;
      capture.committed = true;
      capture.commitTick = player.server.getTickCount();
      capture.sourceAnchor = new Vec3(transfer.sourceX(), transfer.sourceY(), transfer.sourceZ());
      capture.targetAnchor = new Vec3(transfer.x(), transfer.y(), transfer.z());
      capture.rotationDegrees = wrapDegrees(transfer.yaw() - transfer.sourceYaw());
      capture.commitSample = new MotionSample(capture.commitTick, player.position(),
         player.getDeltaMovement(), player.onGround());
      MotionSample previous = null;
      for (MotionSample sample : capture.before) {
         logMotionSample(player, "before", sample, previous, capture);
         previous = sample;
      }
      logMotionSample(player, "commit", capture.commitSample, null, capture);
   }

   private void sampleMotion(ServerPlayer player, int tick) {
      MotionCapture capture = this.motionCaptures.get(player.getUUID());
      if (capture == null) return;
      if (tick - capture.armedTick > MOTION_TRACE_TIMEOUT_TICKS) {
         if (this.motionCaptures.remove(player.getUUID(), capture)) {
            TellusCommon.LOGGER.warn("Cube-net motion trace expired player={} tick={}",
               player.getGameProfile().getName(), tick);
         }
         return;
      }
      MotionSample sample = new MotionSample(tick, player.position(), player.getDeltaMovement(), player.onGround());
      if (!capture.committed) {
         capture.before.add(sample);
         while (capture.before.size() > MOTION_TRACE_HISTORY_TICKS) capture.before.remove(0);
         return;
      }
      if (tick <= capture.commitTick) return;
      MotionSample previous = capture.after.isEmpty()
         ? capture.commitSample : capture.after.get(capture.after.size() - 1);
      capture.after.add(sample);
      logMotionSample(player, "after", sample, previous, capture);
      if (capture.after.size() >= MOTION_TRACE_FUTURE_TICKS
         && this.motionCaptures.remove(player.getUUID(), capture)) {
         TellusCommon.LOGGER.info("Cube-net motion trace complete player={} preSamples={} postSamples={}",
            player.getGameProfile().getName(), capture.before.size(), capture.after.size());
      }
   }

   private static void logMotionSample(ServerPlayer player, String phase, MotionSample sample,
      MotionSample previous, MotionCapture capture) {
      int tickDelta = previous == null ? 0 : sample.tick() - previous.tick();
      boolean targetChart = !"before".equals(phase);
      Vec3 position = continuityPosition(sample.position(), targetChart, capture);
      Vec3 velocity = continuityVector(sample.velocity(), targetChart, capture);
      Vec3 observedVelocity = tickDelta <= 0 ? Vec3.ZERO
         : position.subtract(continuityPosition(previous.position(), targetChart, capture))
            .scale(20.0 / tickDelta);
      TellusCommon.LOGGER.info(
         "Cube-net motion sample player={} phase={} tick={} dtTicks={} rawPosition=({}, {}, {}) "
            + "continuityPosition=({}, {}, {}) rawVelocity=({}, {}, {}) continuityVelocity=({}, {}, {}) "
            + "observedVelocity=({}, {}, {}) observedSpeed={} rotationDegrees={} onGround={}",
         player.getGameProfile().getName(), phase, sample.tick(), tickDelta,
         sample.position().x, sample.position().y, sample.position().z,
         position.x, position.y, position.z,
         sample.velocity().x, sample.velocity().y, sample.velocity().z,
         velocity.x, velocity.y, velocity.z,
         observedVelocity.x, observedVelocity.y, observedVelocity.z,
         observedVelocity.length(), capture.rotationDegrees, sample.onGround());
   }

   private static Vec3 continuityPosition(Vec3 position, boolean targetChart, MotionCapture capture) {
      if (!targetChart || capture.sourceAnchor == null || capture.targetAnchor == null) return position;
      Vec3 offset = position.subtract(capture.targetAnchor);
      double radians = Math.toRadians(-capture.rotationDegrees);
      double cos = Math.cos(radians), sin = Math.sin(radians);
      return new Vec3(capture.sourceAnchor.x + offset.x * cos - offset.z * sin,
         position.y, capture.sourceAnchor.z + offset.x * sin + offset.z * cos);
   }

   private static Vec3 continuityVector(Vec3 vector, boolean targetChart, MotionCapture capture) {
      if (!targetChart) return vector;
      double radians = Math.toRadians(-capture.rotationDegrees);
      double cos = Math.cos(radians), sin = Math.sin(radians);
      return new Vec3(vector.x * cos - vector.z * sin, vector.y,
         vector.x * sin + vector.z * cos);
   }

   private static float wrapDegrees(float degrees) {
      float wrapped = degrees % 360.0F;
      if (wrapped >= 180.0F) wrapped -= 360.0F;
      if (wrapped < -180.0F) wrapped += 360.0F;
      return wrapped;
   }

   private static CubeNetLayout.FacePoint insetDestination(CubeNetLayout layout,
      CubeNetLayout.FacePoint point, CubeNetLayout.Side targetSide, double inset) {
      double u = point.u();
      double v = point.v();
      switch (targetSide) {
         case WEST -> u += inset;
         case EAST -> u -= inset;
         case NORTH -> v += inset;
         case SOUTH -> v -= inset;
      }
      double size = layout.faceSize();
      return new CubeNetLayout.FacePoint(point.face(),
         Math.max(0.0, Math.min(size, u)), Math.max(0.0, Math.min(size, v)));
   }

   private static CubeNetLayout.FacePoint owningPoint(CubeNetLayout layout, double x, double z, Vec3 movement) {
      try {
         return layout.fromPlanar(new CubeNetLayout.PlanarPoint(x, z));
      } catch (IllegalArgumentException ignored) {
         for (double direction : new double[]{-1.0, 1.0}) {
            try {
               var owner = layout.fromPlanar(new CubeNetLayout.PlanarPoint(
                  x + direction * movement.x * 1.0E-7, z + direction * movement.z * 1.0E-7));
               var origin = layout.toPlanar(new CubeNetLayout.FacePoint(owner.face(), 0, 0));
               var local = layout.localVector(owner.face(), x - origin.x(), z - origin.z());
               return new CubeNetLayout.FacePoint(owner.face(), local[0], local[1]);
            } catch (IllegalArgumentException stillOutside) { }
         }
         return null;
      }
   }

   private static float rebaseYaw(CubeNetLayout layout, CubeNetApronResolver.ApronAddress apron, float yaw) {
      CubeNetLayout.Seam seam = layout.seam(apron.sourceFace(), apron.sourceSide());
      CubeNetSeamTransform.GridTransform inverse = new CubeNetSeamTransform(layout, seam)
         .targetToSourceGridTransform().inverse();
      double radians = Math.toRadians(yaw);
      double[] targetEngine = inverse.apply(-Math.sin(radians), Math.cos(radians));
      double[] targetLocal = layout.localVector(apron.targetFace(), targetEngine[0], targetEngine[1]);
      double[] targetPlanar = layout.planarVector(apron.targetFace(), targetLocal[0], targetLocal[1]);
      return (float)Math.toDegrees(Math.atan2(-targetPlanar[0], targetPlanar[1]));
   }

   private static double[] rebaseVelocity(CubeNetLayout layout,
      CubeNetApronResolver.ApronAddress apron, Vec3 velocity) {
      CubeNetLayout.Seam seam = layout.seam(apron.sourceFace(), apron.sourceSide());
      double[] targetEngine = new CubeNetSeamTransform(layout, seam)
         .targetToSourceGridTransform().inverse().apply(velocity.x, velocity.z);
      double[] targetLocal = layout.localVector(apron.targetFace(), targetEngine[0], targetEngine[1]);
      return layout.planarVector(apron.targetFace(), targetLocal[0], targetLocal[1]);
   }

   private static double[] planar(CubeNetLayout layout, CubeNetLayout.FacePoint point) {
      CubeNetLayout.PlanarPoint planar = layout.toPlanar(point);
      return new double[]{planar.x(), planar.z()};
   }

   /** Number of clockwise turns that maps target render directions to source chart directions. */
   private static int shadingQuarterTurns(CubeNetLayout layout, CubeNetLayout.Seam seam) {
      return new CubeNetSeamTransform(layout, seam).targetToSourceClockwiseQuarterTurns().orElseThrow();
   }

   static float transportYaw(CubeNetLayout layout, CubeNetLayout.Face source, CubeNetLayout.Side side,
      CubeNetLayout.Face target, float yawDegrees) {
      double yaw = Math.toRadians(yawDegrees);
      double[] local = layout.localVector(source, -Math.sin(yaw), Math.cos(yaw));
      double[] transported = layout.transportVector(source, side, local[0], local[1]);
      double[] planar = layout.planarVector(target, transported[0], transported[1]);
      return (float)Math.toDegrees(Math.atan2(-planar[0], planar[1]));
   }

   private record Transfer(ServerLevel level, double x, double y, double z, ChunkPos chunk, Vec3 velocity, float yaw,
      int shadingQuarterTurns, int startedTick, double sourceX, double sourceY, double sourceZ, float sourceYaw, float sourcePitch,
      Vec3 sourceVelocity, float fallDistance, CompletableFuture<Void> ready,
      UUID transitionId, CompletableFuture<Void> clientReady, ClientChunkDelivery clientChunks, int radius) { }

   private record Preparation(ServerLevel level, ChunkPos center, int radius, CubeNetLayout.Seam seam,
      UUID rootId, UUID transitionId, CompletableFuture<Void> ready, CompletableFuture<Void> clientReady,
      ClientChunkDelivery clientChunks, int startedTick, long startedNanos) { }

   private record EntityPose(Entity entity, Entity vehicle,
      double x, double y, double z, Vec3 velocity, float yaw,
      double sourceX, double sourceY, double sourceZ, float sourceYaw, float sourcePitch,
      Vec3 sourceVelocity, float fallDistance) { }

   private record EntityTransfer(ServerLevel level, Entity entity, List<ChunkPos> chunks,
      List<EntityPose> members, List<LeashLink> leashes, int startedTick,
      CompletableFuture<Void> ready, UUID playerId, UUID transitionId,
      CompletableFuture<Void> clientReady, ClientChunkDelivery clientChunks, int radius) { }

   static final class ClientChunkDelivery {
      private int attempts;
      private int lastSentTick = Integer.MIN_VALUE;
      private boolean clientReceived;
      private List<ChunkPos> chunks = List.of();
      private int nextChunk;
      private boolean centerPacketPending;

      boolean isDue(int tick, boolean retrySupported) {
         int maxAttempts = retrySupported ? MAX_CLIENT_CHUNK_SENDS : 1;
         if (this.clientReceived) return false;
         if (this.nextChunk < this.chunks.size()) return true;
         return this.attempts < maxAttempts
            && (this.attempts == 0 || tick - this.lastSentTick >= CLIENT_CHUNK_RETRY_TICKS);
      }

      int beginAttempt(int tick, boolean retrySupported, ChunkPos center, int radius) {
         if (!this.isDue(tick, retrySupported)) return -1;
         if (this.chunks.isEmpty()) {
            List<ChunkPos> configured = new ArrayList<>();
            for (int dz = -radius; dz <= radius; dz++) {
               for (int dx = -radius; dx <= radius; dx++) {
                  configured.add(new ChunkPos(center.x + dx, center.z + dz));
               }
            }
            this.chunks = List.copyOf(configured);
         }
         if (this.attempts == 0 || this.nextChunk >= this.chunks.size()) {
            this.lastSentTick = tick;
            this.nextChunk = 0;
            this.centerPacketPending = true;
            this.attempts++;
         }
         return this.attempts;
      }

      /** Compatibility helper for focused tests that model a completed batch. */
      int markSent(int tick, boolean retrySupported) {
         if (!this.isDue(tick, retrySupported)) {
            throw new IllegalStateException("Client chunk send is not due");
         }
         this.lastSentTick = tick;
         this.attempts++;
         this.chunks = List.of();
         this.nextChunk = 0;
         return this.attempts;
      }

      boolean consumeCenterPacket() {
         boolean pending = this.centerPacketPending;
         this.centerPacketPending = false;
         return pending;
      }

      ChunkPos nextChunk() {
         return this.nextChunk < this.chunks.size() ? this.chunks.get(this.nextChunk) : null;
      }

      void advance() {
         if (this.nextChunk < this.chunks.size()) this.nextChunk++;
      }

      boolean finishedAttempt() {
         return this.nextChunk >= this.chunks.size();
      }

      boolean markClientReceived() {
         if (this.attempts == 0) return false;
         this.clientReceived = true;
         return true;
      }

      int attempts() {
         return this.attempts;
      }
   }

   private record LeashLink(Mob mob, Entity holder) { }

   private record DeferredTransfer(double x, double y, double z, Vec3 velocity, float yaw,
      int shadingQuarterTurns) { }

   record BoundaryHint(double x, double z, double tangentX, double tangentZ) { }

   private record Correction(double x, double y, double z, int expiresAtTick, float yaw, float pitch,
      int teleportId, UUID transitionId, boolean acknowledged, Vec3 velocity, boolean onGround,
      float fallDistance, int lastVehicleResendTick) {
      private Correction withAcknowledged() {
         return new Correction(x, y, z, expiresAtTick, yaw, pitch, teleportId, transitionId, true,
            velocity, onGround, fallDistance, lastVehicleResendTick);
      }

      private Correction withVehicleResendTick(int tick) {
         return new Correction(x, y, z, expiresAtTick, yaw, pitch, teleportId, transitionId, acknowledged,
            velocity, onGround, fallDistance, tick);
      }
   }

   private record MotionSample(int tick, Vec3 position, Vec3 velocity, boolean onGround) { }

   private static final class MotionCapture {
      private final int armedTick;
      private final List<MotionSample> before = new ArrayList<>();
      private final List<MotionSample> after = new ArrayList<>();
      private boolean committed;
      private int commitTick = -1;
      private MotionSample commitSample;
      private Vec3 sourceAnchor;
      private Vec3 targetAnchor;
      private float rotationDegrees;

      private MotionCapture(int armedTick) { this.armedTick = armedTick; }
   }

   private static final class CorrectionCounters {
      private long sent;
      private long resent;
      private long acknowledged;
      private long rejected;
      private long expired;
   }

}
