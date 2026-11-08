package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/** Ownership view for the projected Earth generator and Earth-grid readers. */
public record EarthOwnerView(EarthCompatibilityPoint point) implements GeneratorOwnerView {
   public EarthOwnerView {
      Objects.requireNonNull(point, "point");
   }

   @Override
   public GeneratorOwner kind() {
      return GeneratorOwner.EARTH_PROJECTED;
   }
}
