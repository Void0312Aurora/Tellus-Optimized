package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client acknowledgement that every target chunk packet entered its staging cache. */
public record CubeNetTransitionChunksReceivedPayload(UUID transitionId)
   implements CustomPacketPayload {
   public static final Type<CubeNetTransitionChunksReceivedPayload> TYPE =
      new Type<>(Tellus.id("cube_net_transition_chunks_received_v1"));
   public static final StreamCodec<FriendlyByteBuf, CubeNetTransitionChunksReceivedPayload> CODEC = new StreamCodec<>() {
      @Override public CubeNetTransitionChunksReceivedPayload decode(FriendlyByteBuf buffer) {
         return new CubeNetTransitionChunksReceivedPayload(buffer.readUUID());
      }
      @Override public void encode(FriendlyByteBuf buffer, CubeNetTransitionChunksReceivedPayload value) {
         buffer.writeUUID(value.transitionId());
      }
   };

   public CubeNetTransitionChunksReceivedPayload {
      Objects.requireNonNull(transitionId, "transitionId");
   }

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
