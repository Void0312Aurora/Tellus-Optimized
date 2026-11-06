package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Authoritative same-tick cube-net commit pose. */
public record CubeNetTransitionCommitPayload(UUID transitionId,
   double x, double y, double z, float yaw, float pitch,
   double velocityX, double velocityY, double velocityZ, float fallDistance,
   int shadingQuarterTurns) implements CustomPacketPayload {
   public static final Type<CubeNetTransitionCommitPayload> TYPE =
      new Type<>(Tellus.id("cube_net_transition_commit_v2"));
   public static final StreamCodec<FriendlyByteBuf, CubeNetTransitionCommitPayload> CODEC = new StreamCodec<>() {
      @Override public CubeNetTransitionCommitPayload decode(FriendlyByteBuf buffer) {
         return new CubeNetTransitionCommitPayload(buffer.readUUID(), buffer.readDouble(), buffer.readDouble(),
            buffer.readDouble(), buffer.readFloat(), buffer.readFloat(), buffer.readDouble(), buffer.readDouble(),
            buffer.readDouble(), buffer.readFloat(), buffer.readVarInt());
      }
      @Override public void encode(FriendlyByteBuf buffer, CubeNetTransitionCommitPayload value) {
         buffer.writeUUID(value.transitionId());
         buffer.writeDouble(value.x());
         buffer.writeDouble(value.y());
         buffer.writeDouble(value.z());
         buffer.writeFloat(value.yaw());
         buffer.writeFloat(value.pitch());
         buffer.writeDouble(value.velocityX());
         buffer.writeDouble(value.velocityY());
         buffer.writeDouble(value.velocityZ());
         buffer.writeFloat(value.fallDistance());
         buffer.writeVarInt(value.shadingQuarterTurns());
      }
   };

   public CubeNetTransitionCommitPayload(UUID transitionId, double x, double y, double z,
      float yaw, float pitch, Vec3 velocity, float fallDistance) {
      this(transitionId, x, y, z, yaw, pitch, velocity.x, velocity.y, velocity.z, fallDistance, 0);
   }

   public CubeNetTransitionCommitPayload(UUID transitionId, double x, double y, double z,
      float yaw, float pitch, Vec3 velocity, float fallDistance, int shadingQuarterTurns) {
      this(transitionId, x, y, z, yaw, pitch, velocity.x, velocity.y, velocity.z, fallDistance,
         shadingQuarterTurns);
   }

   public CubeNetTransitionCommitPayload {
      Objects.requireNonNull(transitionId, "transitionId");
      if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
         || !Float.isFinite(yaw) || !Float.isFinite(pitch)
         || !Double.isFinite(velocityX) || !Double.isFinite(velocityY)
         || !Double.isFinite(velocityZ) || !Float.isFinite(fallDistance)
         || fallDistance < 0.0f || shadingQuarterTurns < 0 || shadingQuarterTurns > 3) {
         throw new IllegalArgumentException("non-finite cube-net commit pose");
      }
   }

   public Vec3 velocity() { return new Vec3(velocityX, velocityY, velocityZ); }
   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
