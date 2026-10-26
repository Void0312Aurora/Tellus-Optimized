package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/** Server-authoritative spherical frame sent after join and face transfer. */
public record SphereFramePayload(
   boolean spherical,
   String planet,
   String mappingId,
   int face,
   double x,
   double y,
   double z,
   double worldScale,
   float yaw,
   long revision
) implements FabricPacket {
   public static final PacketType<SphereFramePayload> TYPE = PacketType.create(
      Tellus.id("sphere_frame"), SphereFramePayload::new
   );

   public SphereFramePayload(FriendlyByteBuf buffer) {
      this(buffer.readBoolean(), buffer.readUtf(128), buffer.readUtf(128), buffer.readByte(),
         buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
         buffer.readFloat(), buffer.readVarLong());
   }

   public SphereFramePayload {
      planet = Objects.requireNonNull(planet, "planet");
      mappingId = Objects.requireNonNull(mappingId, "mappingId");
      if (planet.length() > 128 || mappingId.length() > 128) {
         throw new IllegalArgumentException("spherical frame identifier is too long");
      }
      if (revision < 0L) {
         throw new IllegalArgumentException("invalid spherical frame envelope");
      }
      if (!spherical) {
         if (face != -1) throw new IllegalArgumentException("non-spherical frame must not select a face");
      } else if (face < 0 || face >= 6 || planet.isEmpty() || mappingId.isEmpty()
         || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
         || !(worldScale > 0.0) || !Double.isFinite(worldScale) || !Float.isFinite(yaw)) {
            throw new IllegalArgumentException("invalid spherical frame payload");
      }
   }

   @Override
   public void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.spherical());
      buffer.writeUtf(this.planet(), 128);
      buffer.writeUtf(this.mappingId(), 128);
      buffer.writeByte(this.face());
      buffer.writeDouble(this.x());
      buffer.writeDouble(this.y());
      buffer.writeDouble(this.z());
      buffer.writeDouble(this.worldScale());
      buffer.writeFloat(this.yaw());
      buffer.writeVarLong(this.revision());
   }

   @Override
   public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
