package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * Authoritative same-tick cube-net commit.  The client installs the staged
 * chart and this pose in one client-main-thread operation, avoiding the
 * vanilla teleport-confirm round trip used by the compatibility path.
 */
public record CubeNetTransitionCommitPayload(UUID transitionId,
   double x, double y, double z, float yaw, float pitch,
   double velocityX, double velocityY, double velocityZ, float fallDistance,
   int shadingQuarterTurns)
   implements FabricPacket {
   public static final PacketType<CubeNetTransitionCommitPayload> TYPE = PacketType.create(
      Tellus.id("cube_net_transition_commit_v2"), CubeNetTransitionCommitPayload::new);

   public CubeNetTransitionCommitPayload(UUID transitionId, double x, double y, double z,
      float yaw, float pitch, Vec3 velocity, float fallDistance) {
      this(transitionId, x, y, z, yaw, pitch, velocity.x, velocity.y, velocity.z, fallDistance, 0);
   }

   public CubeNetTransitionCommitPayload(UUID transitionId, double x, double y, double z,
      float yaw, float pitch, Vec3 velocity, float fallDistance, int shadingQuarterTurns) {
      this(transitionId, x, y, z, yaw, pitch, velocity.x, velocity.y, velocity.z, fallDistance,
         shadingQuarterTurns);
   }

   public CubeNetTransitionCommitPayload(FriendlyByteBuf buffer) {
      this(buffer.readUUID(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
         buffer.readFloat(), buffer.readFloat(), buffer.readDouble(), buffer.readDouble(),
         buffer.readDouble(), buffer.readFloat(), buffer.readVarInt());
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
      buffer.writeFloat(fallDistance);
      buffer.writeVarInt(shadingQuarterTurns);
   }

   @Override public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
