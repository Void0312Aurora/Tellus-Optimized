package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client declaration that its renderer cannot run the current seam backend. */
public record CubeNetTransitionUnsupportedPayload(UUID transitionId, String backend)
   implements CustomPacketPayload {
   public static final Type<CubeNetTransitionUnsupportedPayload> TYPE =
      new Type<>(Tellus.id("cube_net_transition_unsupported"));
   public static final StreamCodec<FriendlyByteBuf, CubeNetTransitionUnsupportedPayload> CODEC = new StreamCodec<>() {
      @Override public CubeNetTransitionUnsupportedPayload decode(FriendlyByteBuf buffer) {
         return new CubeNetTransitionUnsupportedPayload(buffer.readUUID(), buffer.readUtf(32));
      }
      @Override public void encode(FriendlyByteBuf buffer, CubeNetTransitionUnsupportedPayload value) {
         buffer.writeUUID(value.transitionId());
         buffer.writeUtf(value.backend(), 32);
      }
   };

   public CubeNetTransitionUnsupportedPayload {
      Objects.requireNonNull(transitionId, "transitionId");
      Objects.requireNonNull(backend, "backend");
      if (backend.isBlank() || backend.length() > 32
         || backend.codePoints().anyMatch(Character::isISOControl)) {
         throw new IllegalArgumentException("backend must be 1..32 characters");
      }
   }

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
