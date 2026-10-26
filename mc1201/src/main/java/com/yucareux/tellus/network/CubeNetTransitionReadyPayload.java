package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/** Client acknowledgement that a prepared cube-net destination is visible locally. */
public record CubeNetTransitionReadyPayload(UUID transitionId) implements FabricPacket {
   public static final PacketType<CubeNetTransitionReadyPayload> TYPE = PacketType.create(
      Tellus.id("cube_net_transition_ready"), CubeNetTransitionReadyPayload::new);

   public CubeNetTransitionReadyPayload(FriendlyByteBuf buffer) {
      this(buffer.readUUID());
   }

   public CubeNetTransitionReadyPayload {
      Objects.requireNonNull(transitionId, "transitionId");
   }

   @Override public void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(transitionId);
   }

   @Override public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
