package com.yucareux.tellus.worldgen;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.worldgen.global.SphereFaceTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Fabric 1.20.1 player routing between explicitly grouped spherical faces. */
public final class SphereFaceRuntime {
   private static final TicketType<UUID> FACE_TRANSFER = TicketType.create("tellus_sphere_transfer", Comparator.<UUID>naturalOrder());
   private static final int LOAD_TIMEOUT_TICKS = 1200;
   private final Map<String, ServerLevel[]> planets = new HashMap<>();
   private final Map<UUID, Transfer> pending = new HashMap<>();
   private final Map<UUID, Integer> retryAfter = new HashMap<>();
   private SphereRuntimeSmokeCheck smoke;

   public static void register() {
      var runtime = new SphereFaceRuntime();
      ServerLifecycleEvents.SERVER_STARTED.register(runtime::discover);
      ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime.clear());
      ServerTickEvents.END_SERVER_TICK.register(runtime::tick);
      ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> runtime.cancel(handler.getPlayer()));
      CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
         var root = Commands.literal("sphere").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> {
               context.getSource().sendSuccess(() -> Component.literal("Spherical planets: " + runtime.planets.keySet()
                  + "; pending transfers: " + runtime.pending.size()), false);
               return runtime.planets.size();
            }))
            .then(Commands.literal("enter").then(Commands.argument("planet", ResourceLocationArgument.id())
               .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-90, 90))
                  .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180)).executes(context -> {
                     var player = context.getSource().getPlayerOrException();
                     String planet = ResourceLocationArgument.getId(context, "planet").toString();
                     var faces = runtime.planets.get(planet);
                     if (faces == null || runtime.pending.containsKey(player.getUUID()) || player.isPassenger() || player.isVehicle()) {
                        context.getSource().sendFailure(Component.literal("Planet is incomplete or a transfer is already pending"));
                        return 0;
                     }
                     var generator = generator(faces[0]);
                     var geo = new SphereWorldModel.GeoPoint(DoubleArgumentType.getDouble(context, "latitude"),
                        DoubleArgumentType.getDouble(context, "longitude"), 0);
                     var target = generator.topology().enginePoint(generator.model().continuousForGeo(geo));
                     runtime.begin(player, faces[target.face()], target, null, true);
                     return 1;
                  })))));
         if (Boolean.getBoolean("tellus.benchmark.enabled")) {
            root.then(Commands.literal("verify").then(Commands.argument("planet", ResourceLocationArgument.id()).executes(context -> {
               var faces = runtime.planets.get(ResourceLocationArgument.getId(context, "planet").toString());
               if (faces == null || runtime.smoke != null) {
                  context.getSource().sendFailure(Component.literal("Planet is incomplete or a smoke check is already active"));
                  return 0;
               }
               runtime.smoke = new SphereRuntimeSmokeCheck(context.getSource().getServer(), faces[0]);
               return 1;
            })));
         }
         dispatcher.register(Commands.literal("tellus").then(root));
      });
   }

   private void discover(MinecraftServer server) {
      this.clear();
      Map<String, List<ServerLevel>> candidates = new HashMap<>();
      for (ServerLevel level : server.getAllLevels()) {
         if (level.getChunkSource().getGenerator() instanceof SphereFaceChunkGenerator generator && !generator.planet().isEmpty()) {
            candidates.computeIfAbsent(generator.planet(), ignored -> new ArrayList<>()).add(level);
         }
      }
      candidates.forEach((name, levels) -> {
         ServerLevel[] faces = new ServerLevel[6];
         boolean valid = levels.size() == 6;
         var first = generator(levels.get(0));
         for (ServerLevel level : levels) {
            var current = generator(level);
            if (faces[current.face()] != null || !sameGeometry(first, current)
               || level.getMinBuildHeight() != current.getMinY() || level.getHeight() != current.getGenDepth()) valid = false;
            faces[current.face()] = level;
         }
         if (valid) {
            this.planets.put(name, faces);
            TellusCommon.LOGGER.info("Connected six spherical faces for planet {}", name);
         } else TellusCommon.LOGGER.error("Spherical planet {} needs exactly six distinct faces with matching scale and vertical geometry; routing disabled", name);
      });
   }

   static boolean sameGeometry(SphereFaceChunkGenerator a, SphereFaceChunkGenerator b) {
      return a.model().config().equals(b.model().config()) && a.getMinY() == b.getMinY()
         && a.getGenDepth() == b.getGenDepth() && a.getSeaLevel() == b.getSeaLevel();
   }

   private static SphereFaceChunkGenerator generator(ServerLevel level) {
      return (SphereFaceChunkGenerator)level.getChunkSource().getGenerator();
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
      var transfer = this.pending.get(player.getUUID());
      if (transfer != null) {
         if (player.isRemoved() || player.serverLevel() != transfer.source()) { this.cancel(player); return; }
         if (transfer.ready().isDone()) {
            try {
               transfer.ready().join(); // Already completed; never wait for chunk generation on this thread.
               double y = transfer.y();
               if (transfer.ground()) y = transfer.target().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                  (int)Math.floor(transfer.point().x()), (int)Math.floor(transfer.point().z())) + 1.0;
               var horizontal = transfer.frame() == null ? null
                  : transfer.frame().transport(transfer.velocity().x, transfer.velocity().z);
               Vec3 velocity = horizontal == null ? Vec3.ZERO
                  : new Vec3(horizontal.x(), transfer.velocity().y, horizontal.z());
               float yaw = transfer.frame() == null ? player.getYRot() : transfer.frame().transportYaw(player.getYRot());
               transfer.target().getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, transfer.chunk(), 2, player.getId());
               player.teleportTo(transfer.target(), transfer.point().x(), y, transfer.point().z(), yaw, player.getXRot());
               player.setDeltaMovement(velocity);
               player.hurtMarked = true; // Send the transported velocity to the client.
               player.fallDistance = transfer.ground() ? 0 : transfer.fallDistance();
               this.cancel(player);
            } catch (RuntimeException error) {
               this.fail(player, "Spherical destination could not be loaded", error);
            }
         } else if (player.server.getTickCount() - transfer.startedTick() >= LOAD_TIMEOUT_TICKS) {
            this.fail(player, "Spherical destination load timed out", null);
         } else this.hold(player, transfer.holdX(), transfer.y(), transfer.holdZ());
         return;
      }
      if (!(player.serverLevel().getChunkSource().getGenerator() instanceof SphereFaceChunkGenerator source)) return;
      SphereFaceTopology topology = source.topology();
      if (topology.containsEnginePoint(player.getX(), player.getZ())) return;
      if (this.retryAfter.getOrDefault(player.getUUID(), 0) > player.server.getTickCount()) {
         this.holdAtEdge(player, topology);
         return;
      }
      var faces = this.planets.get(source.planet());
      if (faces == null || player.isPassenger() || player.isVehicle()) {
         this.holdAtEdge(player, topology);
         this.fail(player, faces == null ? "This spherical planet has no complete matching face set" : "Dismount before crossing a spherical face", null);
         return;
      }
      try {
         var seam = topology.transfer(source.face(), player.getX(), player.getZ(), player.getYRot(),
            player.getDeltaMovement().x, player.getDeltaMovement().z);
         if (seam.destination().face() == source.face()) {
            // The inverse face rule assigns an exact edge tie to the source
            // face. Keep the entity in the valid half-open engine domain until
            // motion crosses the edge by a representable amount.
            this.holdAtEdge(player, topology);
            return;
         }
         this.begin(player, faces[seam.destination().face()], seam.destination(), seam.frame(), false);
      } catch (IllegalArgumentException error) {
         this.holdAtEdge(player, topology);
         this.fail(player, "Position exceeds the continued face chart", error);
      }
   }

   private void begin(ServerPlayer player, ServerLevel target, SphereFaceTopology.EnginePoint point,
      SphereWorldModel.FaceTransition frame, boolean ground) {
      var topology = generator(target).topology();
      // An exactly-on-edge corner may belong to a closed positive edge. Select
      // its last interior floating-point coordinate, rather than an outside chunk.
      double x = Math.min(point.x(), Math.nextDown(topology.maxEngineBlock()));
      double z = Math.min(point.z(), Math.nextDown(topology.maxEngineBlock()));
      var destination = new SphereFaceTopology.EnginePoint(point.face(), x, z);
      ChunkPos chunk = new ChunkPos((int)Math.floor(x / 16.0), (int)Math.floor(z / 16.0));
      UUID id = player.getUUID();
      target.getChunkSource().addRegionTicket(FACE_TRANSFER, chunk, 2, id);
      List<CompletableFuture<?>> loads = new ArrayList<>();
      for (int dz = -1; dz <= 1; dz++) {
         for (int dx = -1; dx <= 1; dx++) {
            int cx = chunk.x + dx, cz = chunk.z + dz;
            if (!topology.containsEngineChunk(cx, cz)) continue;
            // In 1.20.1 getChunkFuture uses managedBlock when called on the
            // server thread. Its off-thread branch marshals scheduling to the
            // chunk executor without that wait. Keep all nine requests there.
            loads.add(CompletableFuture.supplyAsync(() -> target.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true),
               Util.backgroundExecutor()).thenCompose(future -> future).thenAccept(result -> {
                  if (result.left().isEmpty()) throw new IllegalStateException("Destination chunk unavailable: " + result.right());
               }));
         }
      }
      double holdX = player.getX(), holdZ = player.getZ();
      double sourceX = player.getX(), sourceY = player.getY(), sourceZ = player.getZ();
      float sourceYaw = player.getYRot(), sourcePitch = player.getXRot();
      Vec3 sourceVelocity = player.getDeltaMovement();
      float sourceFallDistance = player.fallDistance;
      if (frame != null) {
         var sourceTopology = generator(player.serverLevel()).topology();
         holdX = clampInside(holdX, sourceTopology);
         holdZ = clampInside(holdZ, sourceTopology);
      }
      var transfer = new Transfer(player.serverLevel(), target, destination, chunk, frame,
         player.getY(), holdX, holdZ, player.getDeltaMovement(), player.fallDistance, ground,
         sourceX, sourceY, sourceZ, sourceYaw, sourcePitch, sourceVelocity, sourceFallDistance,
         player.server.getTickCount(), CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)));
      this.pending.put(id, transfer);
      this.hold(player, holdX, transfer.y(), holdZ);
   }

   private static double clampInside(double coordinate, SphereFaceTopology topology) {
      return Math.max(topology.minEngineBlock() + 0.5, Math.min(topology.maxEngineBlock() - 0.5, coordinate));
   }

   private void holdAtEdge(ServerPlayer player, SphereFaceTopology topology) {
      this.hold(player, clampInside(player.getX(), topology), player.getY(), clampInside(player.getZ(), topology));
   }

   private void hold(ServerPlayer player, double x, double y, double z) {
      if (player.position().distanceToSqr(new Vec3(x, y, z)) > 1.0E-8) player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());
      player.setDeltaMovement(Vec3.ZERO);
   }

   private void fail(ServerPlayer player, String message, RuntimeException error) {
      Transfer transfer = this.pending.get(player.getUUID());
      if (transfer != null) this.rollback(player, transfer);
      this.cancel(player);
      this.retryAfter.put(player.getUUID(), player.server.getTickCount() + 200);
      player.sendSystemMessage(Component.literal(message));
      if (error != null) TellusCommon.LOGGER.error(message, error);
   }

   /** Restore the complete source state after a failed destination load. */
   private void rollback(ServerPlayer player, Transfer transfer) {
      if (player.isRemoved() || player.serverLevel() != transfer.source()) return;
      player.teleportTo(transfer.source(), transfer.sourceX(), transfer.sourceY(), transfer.sourceZ(),
         transfer.sourceYaw(), transfer.sourcePitch());
      player.setDeltaMovement(transfer.sourceVelocity());
      player.hurtMarked = true;
      player.fallDistance = transfer.sourceFallDistance();
   }

   private void cancel(ServerPlayer player) {
      var transfer = this.pending.remove(player.getUUID());
      if (transfer != null) transfer.target().getChunkSource().removeRegionTicket(FACE_TRANSFER, transfer.chunk(), 2, player.getUUID());
      this.retryAfter.remove(player.getUUID());
   }

   private void clear() {
      this.pending.forEach((id, transfer) -> transfer.target().getChunkSource().removeRegionTicket(FACE_TRANSFER, transfer.chunk(), 2, id));
      this.pending.clear();
      this.retryAfter.clear();
      this.planets.clear();
      if (this.smoke != null) { this.smoke.cleanup(); this.smoke = null; }
   }

   private record Transfer(ServerLevel source, ServerLevel target, SphereFaceTopology.EnginePoint point, ChunkPos chunk,
      SphereWorldModel.FaceTransition frame, double y, double holdX, double holdZ, Vec3 velocity, float fallDistance,
      boolean ground, double sourceX, double sourceY, double sourceZ, float sourceYaw, float sourcePitch,
      Vec3 sourceVelocity, float sourceFallDistance, int startedTick, CompletableFuture<Void> ready) { }
}
