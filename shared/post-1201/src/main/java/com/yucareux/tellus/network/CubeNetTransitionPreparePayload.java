package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server request for the client to stage a cube-net destination area. */
public record CubeNetTransitionPreparePayload(UUID transitionId, int centerChunkX,
   int centerChunkZ, int radius, boolean holdMovement, int shadingQuarterTurns)
   implements CustomPacketPayload {
   public static final Type<CubeNetTransitionPreparePayload> TYPE =
      new Type<>(Tellus.id("cube_net_transition_prepare_v3"));
   public static final StreamCodec<FriendlyByteBuf, CubeNetTransitionPreparePayload> CODEC = new StreamCodec<>() {
      @Override public CubeNetTransitionPreparePayload decode(FriendlyByteBuf buffer) {
         return new CubeNetTransitionPreparePayload(buffer.readUUID(), buffer.readInt(), buffer.readInt(),
            buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt());
      }
      @Override public void encode(FriendlyByteBuf buffer, CubeNetTransitionPreparePayload value) {
         buffer.writeUUID(value.transitionId());
         buffer.writeInt(value.centerChunkX());
         buffer.writeInt(value.centerChunkZ());
         buffer.writeVarInt(value.radius());
         buffer.writeBoolean(value.holdMovement());
         buffer.writeVarInt(value.shadingQuarterTurns());
      }
   };

   public CubeNetTransitionPreparePayload(UUID id, int x, int z, int radius) {
      this(id, x, z, radius, true, 0);
   }

   public CubeNetTransitionPreparePayload(UUID id, int x, int z, int radius, boolean holdMovement) {
      this(id, x, z, radius, holdMovement, 0);
   }

   public CubeNetTransitionPreparePayload {
      Objects.requireNonNull(transitionId, "transitionId");
      if (radius < 0 || radius > 35) throw new IllegalArgumentException("invalid transition radius");
      if (shadingQuarterTurns < 0 || shadingQuarterTurns > 3) {
         throw new IllegalArgumentException("invalid shading quarter turns");
      }
   }

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
