package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/** Server request for the client to stage a cube-net destination area. */
public record CubeNetTransitionPreparePayload(UUID transitionId, int centerChunkX,
   int centerChunkZ, int radius, boolean holdMovement, int shadingQuarterTurns) implements FabricPacket {
   public static final PacketType<CubeNetTransitionPreparePayload> TYPE = PacketType.create(
      Tellus.id("cube_net_transition_prepare_v3"), CubeNetTransitionPreparePayload::new);

   public CubeNetTransitionPreparePayload(UUID id, int x, int z, int radius) {
      this(id, x, z, radius, true, 0);
   }

   public CubeNetTransitionPreparePayload(UUID id, int x, int z, int radius, boolean holdMovement) {
      this(id, x, z, radius, holdMovement, 0);
   }

   public CubeNetTransitionPreparePayload(FriendlyByteBuf buffer) {
      this(buffer.readUUID(), buffer.readInt(), buffer.readInt(), buffer.readVarInt(), buffer.readBoolean(),
         buffer.readVarInt());
   }

   public CubeNetTransitionPreparePayload {
      Objects.requireNonNull(transitionId, "transitionId");
      if (radius < 0 || radius > 35) throw new IllegalArgumentException("invalid transition radius");
      if (shadingQuarterTurns < 0 || shadingQuarterTurns > 3) {
         throw new IllegalArgumentException("invalid shading quarter turns");
      }
   }

   @Override public void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(transitionId);
      buffer.writeInt(centerChunkX);
      buffer.writeInt(centerChunkZ);
      buffer.writeVarInt(radius);
      buffer.writeBoolean(holdMovement);
      buffer.writeVarInt(shadingQuarterTurns);
   }

   @Override public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
