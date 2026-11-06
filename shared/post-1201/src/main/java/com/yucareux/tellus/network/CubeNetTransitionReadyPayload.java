package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client acknowledgement that a prepared cube-net destination is visible locally. */
public record CubeNetTransitionReadyPayload(UUID transitionId) implements CustomPacketPayload {
   public static final Type<CubeNetTransitionReadyPayload> TYPE =
      new Type<>(Tellus.id("cube_net_transition_ready"));
   public static final StreamCodec<FriendlyByteBuf, CubeNetTransitionReadyPayload> CODEC = new StreamCodec<>() {
      @Override public CubeNetTransitionReadyPayload decode(FriendlyByteBuf buffer) {
         return new CubeNetTransitionReadyPayload(buffer.readUUID());
      }
      @Override public void encode(FriendlyByteBuf buffer, CubeNetTransitionReadyPayload value) {
         buffer.writeUUID(value.transitionId());
      }
   };

   public CubeNetTransitionReadyPayload {
      Objects.requireNonNull(transitionId, "transitionId");
   }

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
