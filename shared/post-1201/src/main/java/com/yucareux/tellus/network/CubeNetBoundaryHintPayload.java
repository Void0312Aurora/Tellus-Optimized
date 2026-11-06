package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server-authoritative visual marker for the nearby cube-net boundary. */
public record CubeNetBoundaryHintPayload(double x, double z, double tangentX, double tangentZ,
   int durationTicks) implements CustomPacketPayload {
   public static final Type<CubeNetBoundaryHintPayload> TYPE =
      new Type<>(Tellus.id("cube_net_boundary_hint"));
   public static final StreamCodec<FriendlyByteBuf, CubeNetBoundaryHintPayload> CODEC = new StreamCodec<>() {
      @Override public CubeNetBoundaryHintPayload decode(FriendlyByteBuf buffer) {
         return new CubeNetBoundaryHintPayload(buffer.readDouble(), buffer.readDouble(),
            buffer.readDouble(), buffer.readDouble(), buffer.readVarInt());
      }
      @Override public void encode(FriendlyByteBuf buffer, CubeNetBoundaryHintPayload value) {
         buffer.writeDouble(value.x());
         buffer.writeDouble(value.z());
         buffer.writeDouble(value.tangentX());
         buffer.writeDouble(value.tangentZ());
         buffer.writeVarInt(value.durationTicks());
      }
   };

   public CubeNetBoundaryHintPayload {
      if (!Double.isFinite(x) || !Double.isFinite(z)
         || !Double.isFinite(tangentX) || !Double.isFinite(tangentZ)
         || Math.abs(tangentX) + Math.abs(tangentZ) < 0.5
         || durationTicks < 1 || durationTicks > 200) {
         throw new IllegalArgumentException("invalid cube-net boundary hint");
      }
   }

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
