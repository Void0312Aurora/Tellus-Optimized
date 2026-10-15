package com.yucareux.tellus.worldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.worldgen.global.SphereFaceTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Opt-in engine smoke check. Uses an actual ServerPlayer with a packet sink,
 * real face dimensions and real FULL chunk loading. The optional collision
 * stage probes only the target landing AABB and rollback path; this is not a
 * client, walking physics, visual, dataset-quality or performance benchmark.
 */
final class SphereRuntimeSmokeCheck {
   private final MinecraftServer server;
   private final ServerLevel faceZero;
   private final ServerPlayer player;
   private final JsonObject result = new JsonObject();
   private final JsonArray crossings = new JsonArray();
   private final SphereWorldModel model;
   private final SphereFaceTopology topology;
   private SphereWorldModel.FaceTransition frame;
   private SphereFaceTopology.EnginePoint expected;
   private SphereWorldModel.GeoPoint originalPoint;
   private int sourceFace;
   private int stage;
   private int startedTick;
   private boolean requested;
   private boolean expectCollisionRollback;
   private boolean finished;
   private double requestMillis;
   private final float yaw = 27.0F;
   private final float pitch = 13.0F;
   private final Vec3 velocity = new Vec3(0.07, -0.04, 0.2);

   SphereRuntimeSmokeCheck(MinecraftServer server, ServerLevel faceZero) {
      this.server = server;
      this.faceZero = faceZero;
      var generator = (SphereFaceChunkGenerator)faceZero.getChunkSource().getGenerator();
      this.model = generator.model();
      this.topology = generator.topology();
      // Constructor spawn search belongs to the ordinary, already-loaded
      // overworld, rather than an unrelated spherical face-center DEM request.
      this.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "SphereProbe"));
      this.player.setServerLevel(faceZero);
      var sink = new Connection(PacketFlow.SERVERBOUND) {
         @Override public void send(Packet<?> packet) { }
         @Override public void send(Packet<?> packet, PacketSendListener listener) { }
      };
      new ServerGamePacketListenerImpl(server, sink, this.player);
      this.player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
      this.result.addProperty("fixture", "engine ServerPlayer with packet sink; no connected client");
      this.result.addProperty("planet", generator.planet());
      this.result.addProperty("worldScale", generator.worldScale());
      this.result.add("crossings", this.crossings);
      // Face 0's north edge at longitude zero: approximately 45 N, 0 E.
      this.prepare(0, 0.0, this.topology.maxEngineBlock() + 0.375);
   }

   ServerPlayer player() { return this.player; }
   boolean finished() { return this.finished; }

   private void prepare(int face, double x, double z) {
      this.prepare(face, x, z,
         ((SphereFaceChunkGenerator)this.player.serverLevel().getChunkSource().getGenerator()).getSeaLevel() + 512.0);
   }

   private void prepare(int face, double x, double z, double y) {
      this.player.moveTo(x, y, z, this.yaw, this.pitch);
      this.player.setDeltaMovement(this.velocity);
      this.player.fallDistance = 7.5F;
      this.sourceFace = face;
      this.frame = this.topology.transition(face, x, z);
      this.expected = this.topology.enginePoint(this.frame.destination());
      this.originalPoint = this.model.continuedSurfacePoint(face, x - this.topology.minEngineBlock(), z - this.topology.minEngineBlock());
      this.startedTick = this.server.getTickCount();
      this.requested = false;
   }

   private void installCollisionObstacle() {
      var reverse = this.topology.transition(2, this.topology.maxEngineBlock() + 0.375, 0.0);
      var target = this.topology.enginePoint(reverse.destination());
      BlockPos obstacle = BlockPos.containing(target.x(), 2000.0, target.z());
      this.faceZero.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3);
      this.result.addProperty("collisionObstacleFace", 0);
      this.result.addProperty("collisionObstacleX", obstacle.getX());
      this.result.addProperty("collisionObstacleY", obstacle.getY());
      this.result.addProperty("collisionObstacleZ", obstacle.getZ());
   }

   void tick(Consumer<ServerPlayer> route, Predicate<UUID> pending) {
      try {
         long start = System.nanoTime();
         route.accept(this.player);
         if (!this.requested) {
            this.requestMillis = (System.nanoTime() - start) / 1_000_000.0;
            this.requested = true;
         }
         if (pending.test(this.player.getUUID())) return;
         var targetGenerator = (SphereFaceChunkGenerator)this.player.serverLevel().getChunkSource().getGenerator();
         if (this.expectCollisionRollback) {
            require(targetGenerator.face() == this.sourceFace, "blocked transfer did not roll back to the source face");
            require(Math.abs(this.player.getY() - 2000.0) < 1.0E-6, "collision rollback changed radial height");
            require(this.player.getDeltaMovement().distanceTo(this.velocity) < 1.0E-10,
               "collision rollback changed velocity");
            require(this.player.fallDistance == 7.5F, "collision rollback changed fall distance");
            this.result.addProperty("collisionRollbackPassed", true);
            this.result.addProperty("collisionProbe", "target FULL chunk AABB occupied by a solid block");
            this.result.addProperty("passed", true);
            this.finished = true;
            return;
         }
         require(targetGenerator.face() == this.expected.face(), "player did not arrive on the destination face");
         require(Math.abs(this.player.getX() - this.expected.x()) < 1.0E-6
            && Math.abs(this.player.getZ() - this.expected.z()) < 1.0E-6, "fractional position changed");
         require(Math.abs(this.player.getY() - (targetGenerator.getSeaLevel() + 512.0)) < 1.0E-6, "radial height changed");
         require(Math.abs(this.player.getYRot() - this.frame.transportYaw(this.yaw)) < 1.0E-4, "yaw was not transported");
         require(this.player.getXRot() == this.pitch, "pitch changed");
         var horizontal = this.frame.transport(this.velocity.x, this.velocity.z);
         require(this.player.getDeltaMovement().distanceTo(new Vec3(horizontal.x(), this.velocity.y, horizontal.z())) < 1.0E-10,
            "velocity was not transported");
         require(this.player.fallDistance == 7.5F, "fall distance changed");
         var chunk = this.player.serverLevel().getChunkSource().getChunkNow(
            (int)Math.floor(this.player.getX() / 16.0), (int)Math.floor(this.player.getZ() / 16.0));
         require(chunk != null, "target FULL chunk is not loaded");
         var actualGeo = this.model.exactSurfacePoint(targetGenerator.face(), this.player.getX() - this.topology.minEngineBlock(),
            this.player.getZ() - this.topology.minEngineBlock());
         double residual = SphereWorldModel.greatCircleDistanceMeters(this.originalPoint, actualGeo);
         require(residual < 1.0E-5, "spherical position was not preserved");
         var crossing = new JsonObject();
         crossing.addProperty("destinationFace", targetGenerator.face());
         crossing.addProperty("destinationDimension", this.player.serverLevel().dimension().location().toString());
         crossing.addProperty("x", this.player.getX());
         crossing.addProperty("y", this.player.getY());
         crossing.addProperty("z", this.player.getZ());
         crossing.addProperty("latitude", actualGeo.latitude());
         crossing.addProperty("longitude", actualGeo.longitude());
         crossing.addProperty("geographicResidualMeters", residual);
         crossing.addProperty("ticksDuringTransfer", this.server.getTickCount() - this.startedTick);
         crossing.addProperty("initialRouteCallMillis", this.requestMillis);
         crossing.addProperty("fullChunkLoaded", true);
         this.crossings.add(crossing);
         if (this.stage++ == 0) {
            require(targetGenerator.face() == 2, "unexpected north-edge neighbor");
            // Step across the reverse seam at a high radial level. Install a
            // solid target block first so the live AABB probe must reject the
            // transfer and restore the complete source state.
            this.installCollisionObstacle();
            this.expectCollisionRollback = true;
            this.prepare(2, this.topology.maxEngineBlock() + 0.375, 0.0, 2000.0);
         } else {
            this.result.addProperty("passed", true);
            this.finished = true;
         }
      } catch (RuntimeException error) {
         this.result.addProperty("passed", false);
         this.result.addProperty("error", error.toString());
         TellusCommon.LOGGER.error("Spherical runtime smoke check failed", error);
         this.finished = true;
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) throw new IllegalStateException(message);
   }

   void cleanup() {
      this.player.serverLevel().removePlayerImmediately(this.player, Entity.RemovalReason.DISCARDED);
   }

   void finish() {
      this.cleanup();
      Path directory = TellusPlatform.gameDir().resolve("tellus/benchmarks");
      try {
         Files.createDirectories(directory);
         Path output = directory.resolve("sphere-runtime-check-" + System.currentTimeMillis() + ".json");
         Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(this.result) + "\n");
         TellusCommon.LOGGER.info("Spherical runtime smoke result: {}", output);
      } catch (IOException error) {
         TellusCommon.LOGGER.error("Could not save spherical runtime smoke result", error);
      }
      if (Boolean.getBoolean("tellus.benchmark.exit")) this.server.halt(false);
   }
}
