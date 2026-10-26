package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/** Client declaration that its renderer cannot run the current seam backend. */
public record CubeNetTransitionUnsupportedPayload(UUID transitionId, String backend)
   implements net.fabricmc.fabric.api.networking.v1.FabricPacket {
   public static final PacketType<CubeNetTransitionUnsupportedPayload> TYPE = PacketType.create(
      Tellus.id("cube_net_transition_unsupported"), CubeNetTransitionUnsupportedPayload::new);

   public CubeNetTransitionUnsupportedPayload(FriendlyByteBuf buffer) {
      this(buffer.readUUID(), buffer.readUtf(32));
   }

   public CubeNetTransitionUnsupportedPayload {
      Objects.requireNonNull(transitionId, "transitionId");
      Objects.requireNonNull(backend, "backend");
      if (backend.isBlank() || backend.length() > 32
         || backend.codePoints().anyMatch(Character::isISOControl)) {
         throw new IllegalArgumentException("backend must be 1..32 characters");
      }
   }

   @Override public void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(transitionId);
      buffer.writeUtf(backend, 32);
   }

   @Override public PacketType<?> getType() { return TYPE; }
}
