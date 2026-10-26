package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yucareux.tellus.network.SphereFramePayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class SphereFramePayloadTest {
   @Test
   void roundTripsAuthoritativeSphericalFrame() {
      var original = new SphereFramePayload(true, "tellus:earth", "rancic-ccam30-double-v1", 2,
         1.25, 2000.0, -3.5, 1.0, 27.0F, 7L);
      var buffer = new FriendlyByteBuf(Unpooled.buffer());

      original.write(buffer);

      assertEquals(original, new SphereFramePayload(buffer));
   }

   @Test
   void roundTripsNonSphericalClearFrame() {
      var original = new SphereFramePayload(false, "", "", -1,
         Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0F, 8L);
      var buffer = new FriendlyByteBuf(Unpooled.buffer());

      original.write(buffer);

      var decoded = new SphereFramePayload(buffer);
      assertEquals(original.spherical(), decoded.spherical());
      assertEquals(original.face(), decoded.face());
      assertEquals(original.revision(), decoded.revision());
   }

   @Test
   void rejectsMalformedSphericalFrame() {
      assertThrows(IllegalArgumentException.class, () -> new SphereFramePayload(
         true, "tellus:earth", "rancic-ccam30-double-v1", 6,
         1.0, 2.0, 3.0, 1.0, 0.0F, 1L));
      assertThrows(IllegalArgumentException.class, () -> new SphereFramePayload(
         true, "tellus:earth", "rancic-ccam30-double-v1", 0,
         Double.NaN, 2.0, 3.0, 1.0, 0.0F, 1L));
   }
}
