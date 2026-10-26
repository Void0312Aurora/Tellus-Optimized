package com.yucareux.tellus.worldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Real engine movement hook + FULL chunk loads; packet sink, no connected client. */
final class CubeNetRuntimeSmokeCheck {
   private static final TicketType<UUID> FIXTURE = TicketType.create("tellus_cube_net_fixture", Comparator.<UUID>naturalOrder());
   private final MinecraftServer server;
   private final ServerLevel level;
   private final ServerPlayer player;
   private final CubeNetLayout layout;
   private final boolean nativeApron;
   private final List<Case> cases = new ArrayList<>();
   private final List<ChunkPos> tickets = new ArrayList<>();
   private final JsonObject result = new JsonObject();
   private final JsonArray crossings = new JsonArray();
   private final Vec3 velocity = new Vec3(0.07, -0.04, 0.2);
   private final float yaw = 27, pitch = 13;
   private final double y;
   private int stage;
   private int stageTick;
   private boolean requested;
   private boolean interveningPositionUpdate;
   private boolean finished;
   private CompletableFuture<Void> ready;
   private CubeNetLayout.PlanarPoint source;
   private CubeNetLayout.SeamTransfer transfer;
   private CubeNetLayout.PlanarPoint destination;
   private CubeNetLayout.PlanarPoint expectedPlanar;
   private Vec3 movement;
   private BlockPos obstacle;
   private BlockState oldObstacle;

   CubeNetRuntimeSmokeCheck(MinecraftServer server, ServerLevel level) {
      this.server = server;
      this.level = level;
      var generator = (CubeNetChunkGenerator)level.getChunkSource().getGenerator();
      this.layout = generator.adapter().layout();
      this.nativeApron = generator.nativeApronEnabled();
      this.y = Math.min(generator.getSeaLevel() + 136, level.getMaxBuildHeight() - 8);
      this.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "CubeNetProbe"));
      this.player.setServerLevel(level);
      var sink = new Connection(PacketFlow.SERVERBOUND) {
         @Override public void send(Packet<?> packet) { }
         @Override public void send(Packet<?> packet, PacketSendListener listener) { }
      };
      new ServerGamePacketListenerImpl(server, sink, this.player);
      this.player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
      this.player.noPhysics = true;
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         this.cases.add(new Case(face, side, false, false));
      }
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (!this.layout.arePlanarNeighbors(face, side)) this.cases.add(new Case(face, side, true, false));
      }
      Case firstCut = this.cases.stream().filter(test -> !this.layout.arePlanarNeighbors(test.face(), test.side())).findFirst().orElseThrow();
      this.cases.add(new Case(firstCut.face(), firstCut.side(), false, true));
      this.result.addProperty("fixture", "ServerPlayer.move through installed Entity mixin; spectator packet sink; no client");
      this.result.addProperty("dimension", level.dimension().location().toString());
      this.result.addProperty("worldScale", generator.worldScale());
      this.result.addProperty("faceSize", this.layout.faceSize());
      this.result.addProperty("syntheticSampler", Boolean.getBoolean("tellus.sphere.syntheticSampler"));
      this.result.add("crossings", this.crossings);
      this.prepare();
   }

   ServerPlayer player() { return this.player; }
   boolean finished() { return this.finished; }

   private void prepare() {
      this.releaseTickets();
      Case test = this.cases.get(this.stage);
      double edge = this.layout.faceSize() * 0.5 + 0.375;
      double inset = test.exact() ? 0 : 0.25;
      double u = edge, v = edge, du = 0.125, dv = 0.125;
      switch (test.side()) {
         case WEST -> { u = inset; du = -0.75; }
         case EAST -> { u = this.layout.faceSize() - inset; du = 0.75; }
         case NORTH -> { v = inset; dv = -0.75; }
         case SOUTH -> { v = this.layout.faceSize() - inset; dv = 0.75; }
      }
      var point = new CubeNetLayout.FacePoint(test.face(), u, v);
      this.source = this.layout.toPlanar(point);
      this.transfer = this.layout.crossSeam(point, du, dv);
      var destinationPoint = this.transfer.destination();
      boolean cut = !this.layout.arePlanarNeighbors(test.face(), test.side());
      if (cut && !this.nativeApron) {
         double insetU = destinationPoint.u(), insetV = destinationPoint.v();
         switch (this.transfer.seam().targetSide()) {
            case WEST -> insetU += 0.5;
            case EAST -> insetU -= 0.5;
            case NORTH -> insetV += 0.5;
            case SOUTH -> insetV -= 0.5;
         }
         destinationPoint = new CubeNetLayout.FacePoint(destinationPoint.face(), insetU, insetV);
      }
      this.destination = this.layout.toPlanar(destinationPoint);
      double[] planarMovement = this.layout.planarVector(test.face(), du, dv);
      this.movement = new Vec3(planarMovement[0], 0.125, planarMovement[1]);
      this.expectedPlanar = (this.nativeApron || this.layout.arePlanarNeighbors(test.face(), test.side()))
         ? new CubeNetLayout.PlanarPoint(this.source.x() + this.movement.x, this.source.z() + this.movement.z)
         : this.destination;
      this.player.moveTo(this.source.x(), this.y, this.source.z(), this.yaw, this.pitch);
      this.player.setDeltaMovement(this.velocity);
      this.player.fallDistance = 7.5F;
      this.requested = false;
      this.stageTick = this.server.getTickCount();
      this.ready = CompletableFuture.allOf(this.loadArea(this.source), this.loadArea(this.destination));
   }

   private CompletableFuture<Void> loadArea(CubeNetLayout.PlanarPoint point) {
      var center = new ChunkPos(BlockPos.containing(point.x(), this.y, point.z()));
      this.level.getChunkSource().addRegionTicket(FIXTURE, center, 2, this.player.getUUID());
      this.tickets.add(center);
      var loads = new ArrayList<CompletableFuture<?>>();
      for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
         int x = center.x + dx, z = center.z + dz;
         loads.add(CompletableFuture.supplyAsync(
            () -> this.level.getChunkSource().getChunkFuture(x, z, ChunkStatus.FULL, true),
            Util.backgroundExecutor()).thenCompose(future -> future).thenAccept(chunk -> {
               require(chunk.left().isPresent(), "fixture FULL chunk unavailable: " + chunk.right());
            }));
      }
      return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
   }

   void tick(Consumer<ServerPlayer> route, Predicate<UUID> pending, Predicate<UUID> deferred) {
      try {
         require(this.server.getTickCount() - this.stageTick < 1200, "fixture stage timed out");
         if (!this.ready.isDone()) return;
         this.ready.join();
         Case test = this.cases.get(this.stage);
         boolean cut = !this.layout.arePlanarNeighbors(test.face(), test.side());
         if (!this.requested) {
            if (test.blocked()) {
               this.obstacle = BlockPos.containing(this.destination.x(), this.y + this.movement.y, this.destination.z());
               this.oldObstacle = this.level.getBlockState(this.obstacle);
               require(this.level.setBlock(this.obstacle, Blocks.STONE.defaultBlockState(), 3), "obstacle not placed");
            }
            this.player.move(MoverType.SELF, this.movement);
            require((pending.test(this.player.getUUID()) || deferred.test(this.player.getUUID()))
                  == (cut && !this.nativeApron),
               "movement hook did not select the expected direct/apron/seam path");
            if (cut && !this.nativeApron) {
               require(this.player.position().distanceTo(new Vec3(this.source.x(), this.y, this.source.z())) < 1E-6,
                  "pending transfer committed movement early");
            }
            this.requested = true;
         }
         route.accept(this.player);
         if (deferred.test(this.player.getUUID())) {
            require(this.player.position().distanceTo(new Vec3(this.source.x(), this.y, this.source.z())) < 1E-6,
               "deferred transfer changed its source anchor");
            return;
         }
         if (pending.test(this.player.getUUID())) {
            if (!this.interveningPositionUpdate) {
               // A pending async load must restore the captured source pose.
               this.player.moveTo(this.source.x() + 3, this.y + 2, this.source.z() + 3, 80, 42);
               this.player.fallDistance = 0;
               route.accept(this.player);
               this.interveningPositionUpdate = true;
            }
            if (pending.test(this.player.getUUID())) {
               require(this.player.position().distanceTo(new Vec3(this.source.x(), this.y, this.source.z())) < 1E-6,
                  "waiting transfer did not restore its source anchor");
               require(this.player.fallDistance == 7.5F, "waiting transfer lost fall distance");
               return;
            }
         }
         require(this.player.serverLevel() == this.level, "transfer changed Level");
         if (test.blocked()) {
            require(this.player.position().distanceTo(new Vec3(this.source.x(), this.y, this.source.z())) < 1E-6,
               "blocked landing did not restore source position");
            require(this.player.getDeltaMovement().distanceTo(this.velocity) < 1E-10, "rollback lost velocity");
            require(this.player.getYRot() == this.yaw && this.player.getXRot() == this.pitch, "rollback lost orientation");
            require(this.player.fallDistance == 7.5F, "rollback lost fall distance");
            this.result.addProperty("collisionRollbackPassed", true);
         } else {
            double distance = this.player.position().distanceTo(new Vec3(this.expectedPlanar.x(), this.y + this.movement.y,
               this.expectedPlanar.z()));
            if (distance >= 1E-6) {
               this.result.addProperty("actualX", this.player.getX());
               this.result.addProperty("actualY", this.player.getY());
               this.result.addProperty("actualZ", this.player.getZ());
               this.result.addProperty("expectedX", this.expectedPlanar.x());
               this.result.addProperty("expectedY", this.y + this.movement.y);
               this.result.addProperty("expectedZ", this.expectedPlanar.z());
            }
            require(distance < 1E-6, "destination fractional position or vertical displacement changed");
            Vec3 expectedVelocity = this.velocity;
            float expectedYaw = this.yaw;
            if (cut && !this.nativeApron) {
               double[] local = this.layout.localVector(test.face(), this.velocity.x, this.velocity.z);
               double[] mapped = this.layout.transportVector(test.face(), test.side(), local[0], local[1]);
               double[] planar = this.layout.planarVector(this.transfer.destination().face(), mapped[0], mapped[1]);
               expectedVelocity = new Vec3(planar[0], this.velocity.y, planar[1]);
               expectedYaw = CubeNetRuntime.transportYaw(this.layout, test.face(), test.side(), this.transfer.destination().face(), this.yaw);
            }
            require(this.player.getDeltaMovement().distanceTo(expectedVelocity) < 1E-10, "velocity transform changed");
            require(Math.abs(this.player.getYRot() - expectedYaw) < 1E-4 && this.player.getXRot() == this.pitch,
               "orientation transform changed");
            require(this.player.fallDistance == 7.5F, "fall distance changed");
            require(this.level.getChunkSource().getChunkNow((int)Math.floor(this.player.getX() / 16),
               (int)Math.floor(this.player.getZ() / 16)) != null, "destination FULL chunk unavailable");
            var crossing = new JsonObject();
            crossing.addProperty("sourceFace", test.face().toString());
            crossing.addProperty("side", test.side().toString());
            crossing.addProperty("destinationFace", this.transfer.destination().face().toString());
            crossing.addProperty("seam", cut);
            crossing.addProperty("startsExactlyOnEdge", test.exact());
            crossing.addProperty("ticks", this.server.getTickCount() - this.stageTick);
            crossing.addProperty("fullChunkLoaded", true);
            this.crossings.add(crossing);
         }
         if (++this.stage == this.cases.size()) {
            this.result.addProperty("directedEdgesPassed", 24);
            this.result.addProperty("exactCutEdgeStartsPassed", 14);
            this.result.addProperty("passed", true);
            this.finished = true;
         } else this.prepare();
      } catch (RuntimeException error) {
         this.result.addProperty("passed", false);
         this.result.addProperty("stage", this.stage);
         this.result.addProperty("error", error.toString());
         TellusCommon.LOGGER.error("Cube-net runtime check failed", error);
         this.finished = true;
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) throw new IllegalStateException(message);
   }

   void cleanup() {
      if (this.obstacle != null) this.level.setBlock(this.obstacle, this.oldObstacle, 3);
      this.releaseTickets();
      this.level.removePlayerImmediately(this.player, Entity.RemovalReason.DISCARDED);
   }

   private void releaseTickets() {
      for (var center : this.tickets) this.level.getChunkSource().removeRegionTicket(FIXTURE, center, 2, this.player.getUUID());
      this.tickets.clear();
   }

   void finish() {
      this.cleanup();
      var directory = TellusPlatform.gameDir().resolve("tellus/benchmarks");
      try {
         Files.createDirectories(directory);
         var path = directory.resolve("cube-net-runtime-check-" + System.currentTimeMillis() + ".json");
         Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(this.result) + "\n");
         TellusCommon.LOGGER.info("Cube-net runtime result: {}", path);
      } catch (IOException error) {
         TellusCommon.LOGGER.error("Could not save cube-net runtime result", error);
      }
      if (Boolean.getBoolean("tellus.benchmark.exit")) this.server.halt(false);
   }

   private record Case(CubeNetLayout.Face face, CubeNetLayout.Side side, boolean exact, boolean blocked) { }
}
