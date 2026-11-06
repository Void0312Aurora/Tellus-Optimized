package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Releases only the named speculative client chart. */
public record CubeNetTransitionCancelPayload(UUID transitionId) implements CustomPacketPayload {
   public static final Type<CubeNetTransitionCancelPayload> TYPE =
      new Type<>(Tellus.id("cube_net_transition_cancel"));
   public static final StreamCodec<FriendlyByteBuf, CubeNetTransitionCancelPayload> CODEC = new StreamCodec<>() {
      @Override public CubeNetTransitionCancelPayload decode(FriendlyByteBuf buffer) {
         return new CubeNetTransitionCancelPayload(buffer.readUUID());
      }
      @Override public void encode(FriendlyByteBuf buffer, CubeNetTransitionCancelPayload value) {
         buffer.writeUUID(value.transitionId());
      }
   };

   public CubeNetTransitionCancelPayload { Objects.requireNonNull(transitionId, "transitionId"); }
   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
