package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/** Client acknowledgement that every target chunk packet has entered its staging cache. */
public record CubeNetTransitionChunksReceivedPayload(UUID transitionId) implements FabricPacket {
   public static final PacketType<CubeNetTransitionChunksReceivedPayload> TYPE = PacketType.create(
      Tellus.id("cube_net_transition_chunks_received_v1"), CubeNetTransitionChunksReceivedPayload::new);

   public CubeNetTransitionChunksReceivedPayload(FriendlyByteBuf buffer) {
      this(buffer.readUUID());
   }

   public CubeNetTransitionChunksReceivedPayload {
      Objects.requireNonNull(transitionId, "transitionId");
   }

   @Override public void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(transitionId);
   }

   @Override public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
