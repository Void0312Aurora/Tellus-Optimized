package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server-authoritative cube-net geometry for the far view. */
public record CubeNetLayoutPayload(int faceSize, int apronBlocks) implements CustomPacketPayload {
   public static final Type<CubeNetLayoutPayload> TYPE =
      new Type<>(Tellus.id("cube_net_layout_v1"));
   public static final StreamCodec<FriendlyByteBuf, CubeNetLayoutPayload> CODEC = new StreamCodec<>() {
      @Override public CubeNetLayoutPayload decode(FriendlyByteBuf buffer) {
         return new CubeNetLayoutPayload(buffer.readVarInt(), buffer.readVarInt());
      }
      @Override public void encode(FriendlyByteBuf buffer, CubeNetLayoutPayload value) {
         buffer.writeVarInt(value.faceSize());
         buffer.writeVarInt(value.apronBlocks());
      }
   };

   public CubeNetLayoutPayload {
      if (faceSize < 0 || (faceSize > 0 && faceSize % 16 != 0)) {
         throw new IllegalArgumentException("cube-net face size must be zero or a positive multiple of 16");
      }
      if (apronBlocks < 0 || (faceSize > 0 && apronBlocks > faceSize)) {
         throw new IllegalArgumentException("invalid cube-net apron width");
      }
   }

   public static CubeNetLayoutPayload none() { return new CubeNetLayoutPayload(0, 0); }
   public boolean cubeNet() { return faceSize > 0; }
   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
