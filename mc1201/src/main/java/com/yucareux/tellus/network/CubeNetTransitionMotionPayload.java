package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * The first client movement sample after an atomic cube-net commit.  The
 * server accepts it only while the matching custom correction gate is active
 * and only for a bounded ordinary movement step.
 */
public record CubeNetTransitionMotionPayload(UUID transitionId,
   double x, double y, double z, float yaw, float pitch,
   double velocityX, double velocityY, double velocityZ, boolean onGround)
   implements FabricPacket {
   public static final PacketType<CubeNetTransitionMotionPayload> TYPE = PacketType.create(
      Tellus.id("cube_net_transition_motion_v1"), CubeNetTransitionMotionPayload::new);

   public CubeNetTransitionMotionPayload(UUID transitionId, Vec3 position, float yaw, float pitch,
      Vec3 velocity, boolean onGround) {
      this(transitionId, position.x, position.y, position.z, yaw, pitch,
         velocity.x, velocity.y, velocity.z, onGround);
   }

   public CubeNetTransitionMotionPayload(FriendlyByteBuf buffer) {
      this(buffer.readUUID(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
         buffer.readFloat(), buffer.readFloat(), buffer.readDouble(), buffer.readDouble(),
         buffer.readDouble(), buffer.readBoolean());
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

   public Vec3 position() {
      return new Vec3(x, y, z);
   }

   public Vec3 velocity() {
      return new Vec3(velocityX, velocityY, velocityZ);
   }

   @Override public void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(transitionId);
      buffer.writeDouble(x);
      buffer.writeDouble(y);
      buffer.writeDouble(z);
      buffer.writeFloat(yaw);
      buffer.writeFloat(pitch);
      buffer.writeDouble(velocityX);
      buffer.writeDouble(velocityY);
      buffer.writeDouble(velocityZ);
      buffer.writeBoolean(onGround);
   }

   @Override public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
