package com.yucareux.tellus.worldgen.write;

import java.util.Objects;

/** Derived seam/apron identity attached to, but never replacing, a canonical key. */
public record SeamProjectionIdentity(
   String projectionId,
   long proxyXBits,
   long proxyZBits,
   int targetFace,
   long targetChunkX,
   long targetChunkZ
) {
   public SeamProjectionIdentity {
      projectionId = requireText(projectionId, "projectionId");
      if (targetFace < 0 || targetFace >= 6) throw new IllegalArgumentException("targetFace");
   }

   public static SeamProjectionIdentity of(
      String projectionId, double proxyX, double proxyZ,
      int targetFace, long targetChunkX, long targetChunkZ
   ) {
      if (!Double.isFinite(proxyX) || !Double.isFinite(proxyZ)) {
         throw new IllegalArgumentException("proxy coordinates must be finite");
      }
      return new SeamProjectionIdentity(projectionId,
         Double.doubleToLongBits(proxyX), Double.doubleToLongBits(proxyZ),
         targetFace, targetChunkX, targetChunkZ);
   }

   public double proxyX() { return Double.longBitsToDouble(this.proxyXBits); }
   public double proxyZ() { return Double.longBitsToDouble(this.proxyZBits); }

   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }
}
