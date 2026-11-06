package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** First client movement sample after an atomic cube-net commit. */
public record CubeNetTransitionMotionPayload(UUID transitionId,
   double x, double y, double z, float yaw, float pitch,
   double velocityX, double velocityY, double velocityZ, boolean onGround)
   implements CustomPacketPayload {
   public static final Type<CubeNetTransitionMotionPayload> TYPE =
      new Type<>(Tellus.id("cube_net_transition_motion_v1"));
   public static final StreamCodec<FriendlyByteBuf, CubeNetTransitionMotionPayload> CODEC = new StreamCodec<>() {
      @Override public CubeNetTransitionMotionPayload decode(FriendlyByteBuf buffer) {
         return new CubeNetTransitionMotionPayload(buffer.readUUID(), buffer.readDouble(), buffer.readDouble(),
            buffer.readDouble(), buffer.readFloat(), buffer.readFloat(), buffer.readDouble(), buffer.readDouble(),
            buffer.readDouble(), buffer.readBoolean());
      }
      @Override public void encode(FriendlyByteBuf buffer, CubeNetTransitionMotionPayload value) {
         buffer.writeUUID(value.transitionId());
         buffer.writeDouble(value.x());
         buffer.writeDouble(value.y());
         buffer.writeDouble(value.z());
         buffer.writeFloat(value.yaw());
         buffer.writeFloat(value.pitch());
         buffer.writeDouble(value.velocityX());
         buffer.writeDouble(value.velocityY());
         buffer.writeDouble(value.velocityZ());
         buffer.writeBoolean(value.onGround());
      }
   };

   public CubeNetTransitionMotionPayload(UUID transitionId, Vec3 position, float yaw, float pitch,
      Vec3 velocity, boolean onGround) {
      this(transitionId, position.x, position.y, position.z, yaw, pitch,
         velocity.x, velocity.y, velocity.z, onGround);
   }

   public CubeNetTransitionMotionPayload {
      Objects.requireNonNull(transitionId, "transitionId");
      if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
         || !Float.isFinite(yaw) || !Float.isFinite(pitch)
         || !Double.isFinite(velocityX) || !Double.isFinite(velocityY)
         || !Double.isFinite(velocityZ)) {
         throw new IllegalArgumentException("non-finite cube-net motion sample");
      }
   }

   public Vec3 position() { return new Vec3(x, y, z); }
   public Vec3 velocity() { return new Vec3(velocityX, velocityY, velocityZ); }
   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
