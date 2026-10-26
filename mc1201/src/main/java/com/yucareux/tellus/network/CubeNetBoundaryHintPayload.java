package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/** Server-authoritative visual marker for the nearby cube-net admission boundary. */
public record CubeNetBoundaryHintPayload(double x, double z, double tangentX, double tangentZ,
   int durationTicks) implements FabricPacket {
   public static final PacketType<CubeNetBoundaryHintPayload> TYPE = PacketType.create(
      Tellus.id("cube_net_boundary_hint"), CubeNetBoundaryHintPayload::new);

   public CubeNetBoundaryHintPayload(FriendlyByteBuf buffer) {
      this(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readVarInt());
   }

   public CubeNetBoundaryHintPayload {
      if (!Double.isFinite(x) || !Double.isFinite(z)
         || !Double.isFinite(tangentX) || !Double.isFinite(tangentZ)
         || Math.abs(tangentX) + Math.abs(tangentZ) < 0.5
         || durationTicks < 1 || durationTicks > 200) {
         throw new IllegalArgumentException("invalid cube-net boundary hint");
      }
   }

   @Override
   public void write(FriendlyByteBuf buffer) {
      buffer.writeDouble(x);
      buffer.writeDouble(z);
      buffer.writeDouble(tangentX);
      buffer.writeDouble(tangentZ);
      buffer.writeVarInt(durationTicks);
   }

   @Override
   public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
