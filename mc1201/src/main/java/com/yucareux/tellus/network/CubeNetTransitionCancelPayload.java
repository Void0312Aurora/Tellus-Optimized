package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.UUID;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/** Releases only the named speculative client chart. */
public record CubeNetTransitionCancelPayload(UUID transitionId) implements FabricPacket {
   public static final PacketType<CubeNetTransitionCancelPayload> TYPE = PacketType.create(
      Tellus.id("cube_net_transition_cancel"), CubeNetTransitionCancelPayload::new);

   public CubeNetTransitionCancelPayload { Objects.requireNonNull(transitionId, "transitionId"); }
   public CubeNetTransitionCancelPayload(FriendlyByteBuf buffer) { this(buffer.readUUID()); }
   @Override public void write(FriendlyByteBuf buffer) { buffer.writeUUID(transitionId); }
   @Override public PacketType<?> getType() { return TYPE; }
}
