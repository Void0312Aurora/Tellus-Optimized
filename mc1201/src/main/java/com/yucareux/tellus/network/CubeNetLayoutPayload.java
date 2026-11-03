package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server-authoritative cube-net geometry the client needs to unfold the far
 * view: the face size of the planar net and the native apron width used to
 * resolve apron positions to their physical face. A face size of zero means
 * the player's level is not a cube net.
 */
public record CubeNetLayoutPayload(int faceSize, int apronBlocks) implements FabricPacket {
   public static final PacketType<CubeNetLayoutPayload> TYPE = PacketType.create(
      Tellus.id("cube_net_layout_v1"), CubeNetLayoutPayload::new);

   public CubeNetLayoutPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarInt(), buffer.readVarInt());
   }

   public CubeNetLayoutPayload {
      if (faceSize < 0 || (faceSize > 0 && faceSize % 16 != 0)) {
         throw new IllegalArgumentException("cube-net face size must be zero or a positive multiple of 16");
      }
      if (apronBlocks < 0 || (faceSize > 0 && apronBlocks > faceSize)) {
         throw new IllegalArgumentException("invalid cube-net apron width");
      }
   }

   public static CubeNetLayoutPayload none() { return new CubeNetLayoutPayload(0, 0); }

   public boolean cubeNet() { return this.faceSize > 0; }

   @Override public void write(FriendlyByteBuf buffer) {
      buffer.writeVarInt(this.faceSize);
      buffer.writeVarInt(this.apronBlocks);
   }

   @Override public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
