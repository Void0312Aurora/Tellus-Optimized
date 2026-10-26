package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
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

   private final Map<UUID, Transfer> pending = new HashMap<>();
   private final Map<UUID, Integer> retryAfter = new HashMap<>();
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
      ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> runtime.cancel(handler.getPlayer()));
      CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
         var root = Commands.literal("cube_net").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> {
               context.getSource().sendSuccess(() -> Component.literal("Cube-net Level: "
                  + (runtime.level == null ? "disabled" : runtime.level.dimension().location())
                  + "; pending transfers: " + runtime.pending.size()), false);
               return runtime.level == null ? 0 : 1;
            }));
         if (Boolean.getBoolean("tellus.benchmark.enabled")) {
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

   private boolean interceptBoundaryMove0(ServerPlayer player, Vec3 movement) {
      if (this.pending.containsKey(player.getUUID())) return true;
      if ((movement.x == 0.0 && movement.z == 0.0)
         || !(player.serverLevel().getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)) {
         return false;
      }
      if (this.level != player.serverLevel()) return false;
      CubeNetLayout layout = generator.adapter().layout();
      CubeNetLayout.FacePoint source = this.owningPoint(layout, player.getX(), player.getZ(), movement);
      if (source == null) return false;
      double[] localMovement = layout.localVector(source.face(), movement.x, movement.z);
      double targetU = source.u() + localMovement[0], targetV = source.v() + localMovement[1];
      if (targetU >= 0.0 && targetU <= layout.faceSize() && targetV >= 0.0 && targetV <= layout.faceSize()) {
         return false;
      }
      if (this.retryAfter.getOrDefault(player.getUUID(), 0) > player.server.getTickCount()) {
         this.hold(player);
         return true;
      }
      try {
         CubeNetLayout.SeamTransfer transfer = layout.crossSeam(source, localMovement[0], localMovement[1]);
         if (!transfer.crossed()) return false;
         if (layout.arePlanarNeighbors(source.face(), transfer.seam().sourceSide())) {
            // The five direct net edges are exactly what vanilla planar
            // movement should see; no seam transfer is needed there.
            return false;
         }
         if (player.isPassenger() || player.isVehicle()) {
            this.hold(player);
            return true;
         }
         double[] destination = planar(layout, transfer.destination());
         double[] localVelocity = layout.localVector(source.face(),
            player.getDeltaMovement().x, player.getDeltaMovement().z);
         double[] targetVelocity = layout.transportVector(source.face(), transfer.seam().sourceSide(),
            localVelocity[0], localVelocity[1]);
         double[] planarVelocity = layout.planarVector(transfer.destination().face(),
            targetVelocity[0], targetVelocity[1]);
         float yaw = transportYaw(layout, source.face(), transfer.seam().sourceSide(),
            transfer.destination().face(), player.getYRot());
         this.begin(player, destination[0], player.getY() + movement.y, destination[1],
            new Vec3(planarVelocity[0], player.getDeltaMovement().y, planarVelocity[1]), yaw);
      } catch (IllegalArgumentException error) {
         // A corner crossing has no unique face continuation under the
         // current corner-removal policy. Hold the entity at the last point.
         this.hold(player);
      }
      return true;
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

   private void tick(MinecraftServer server) {
      for (ServerPlayer player : server.getPlayerList().getPlayers()) this.route(player);
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
      if (transfer.ready().isDone()) {
         try {
            transfer.ready().join();
            AABB targetBox = SphereFaceRuntime.destinationBox(player.getBoundingBox(),
               transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(), transfer.x(), transfer.y(), transfer.z());
            if (!transfer.level().noCollision(player, targetBox)) {
               this.fail(player, "Cube-net seam destination is blocked");
               return;
            }
            transfer.level().getChunkSource().addRegionTicket(TicketType.POST_TELEPORT,
               transfer.chunk(), 2, player.getId());
            player.teleportTo(transfer.level(), transfer.x(), transfer.y(), transfer.z(),
               transfer.yaw(), transfer.sourcePitch());
            player.setDeltaMovement(transfer.velocity());
            player.fallDistance = transfer.fallDistance();
            player.hurtMarked = true;
            this.cancel(player);
         } catch (RuntimeException error) {
            this.fail(player, "Cube-net seam destination could not be loaded");
            TellusCommon.LOGGER.error("Cube-net seam transfer failed", error);
         }
      } else if (player.server.getTickCount() - transfer.startedTick() >= LOAD_TIMEOUT_TICKS) {
         this.fail(player, "Cube-net seam destination load timed out");
      } else {
         this.hold(player);
      }
   }

   private void begin(ServerPlayer player, double x, double y, double z, Vec3 velocity, float yaw) {
      ServerLevel target = player.serverLevel();
      ChunkPos chunk = new ChunkPos((int)Math.floor(x / 16.0), (int)Math.floor(z / 16.0));
      UUID id = player.getUUID();
      target.getChunkSource().addRegionTicket(CUBE_NET_TRANSFER, chunk, 2, id);
      List<CompletableFuture<?>> loads = new ArrayList<>();
      for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
         int cx = chunk.x + dx, cz = chunk.z + dz;
         loads.add(CompletableFuture.supplyAsync(
            () -> target.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true),
            Util.backgroundExecutor()).thenCompose(future -> future).thenAccept(result -> {
               if (result.left().isEmpty()) throw new IllegalStateException("destination chunk unavailable: " + result.right());
            }));
      }
      var transfer = new Transfer(target, x, y, z, chunk, velocity, yaw, player.server.getTickCount(),
         player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
         player.getDeltaMovement(), player.fallDistance, CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)));
      this.pending.put(id, transfer);
      this.hold(player);
   }

   private void fail(ServerPlayer player, String message) {
      Transfer transfer = this.pending.get(player.getUUID());
      if (transfer != null && player.serverLevel() == transfer.level()) {
         player.teleportTo(transfer.level(), transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(),
            transfer.sourceYaw(), transfer.sourcePitch());
         player.setDeltaMovement(transfer.sourceVelocity());
         player.fallDistance = transfer.fallDistance();
         player.hurtMarked = true;
      }
      this.cancel(player);
      this.retryAfter.put(player.getUUID(), player.server.getTickCount() + 200);
      player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
   }

   private void hold(ServerPlayer player) {
      Transfer transfer = this.pending.get(player.getUUID());
      if (transfer == null) {
         player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
      } else {
         // Keep the authoritative entity at the snapshot too; a connection
         // teleport alone only corrects the client and leaves packet updates
         // able to move the server-side entity during the async load.
         player.absMoveTo(transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(),
            transfer.sourceYaw(), transfer.sourcePitch());
         player.connection.teleport(transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(),
            transfer.sourceYaw(), transfer.sourcePitch());
         player.fallDistance = transfer.fallDistance();
      }
      player.setDeltaMovement(Vec3.ZERO);
   }

   private void cancel(ServerPlayer player) {
      Transfer transfer = this.pending.remove(player.getUUID());
      if (transfer != null) transfer.level().getChunkSource().removeRegionTicket(
         CUBE_NET_TRANSFER, transfer.chunk(), 2, player.getUUID());
      this.retryAfter.remove(player.getUUID());
   }

   private void clear() {
      if (this.smoke != null) { this.smoke.cleanup(); this.smoke = null; }
      this.pending.forEach((id, transfer) -> transfer.level().getChunkSource().removeRegionTicket(
         CUBE_NET_TRANSFER, transfer.chunk(), 2, id));
      this.pending.clear();
      this.retryAfter.clear();
      this.level = null;
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

   private static double[] planar(CubeNetLayout layout, CubeNetLayout.FacePoint point) {
      CubeNetLayout.PlanarPoint planar = layout.toPlanar(point);
      return new double[]{planar.x(), planar.z()};
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
      int startedTick, double sourceX, double sourceY, double sourceZ, float sourceYaw, float sourcePitch,
      Vec3 sourceVelocity, float fallDistance, CompletableFuture<Void> ready) { }
}
