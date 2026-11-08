package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.GeneratorOwnerView;
import com.yucareux.tellus.worldgen.global.SphericalOwnerView;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import java.util.Objects;

/** Canonical, durable identity; derived seam views never become owners. */
public record CanonicalPersistenceKey(
   String geometryKey,
   GeneratorOwner owner,
   int face,
   long blockX,
   long blockZ,
   long chunkX,
   long chunkZ
) {
   public CanonicalPersistenceKey {
      geometryKey = requireText(geometryKey, "geometryKey");
      Objects.requireNonNull(owner, "owner");
      if (owner == GeneratorOwner.CUBE_NET_APRON) {
         throw new IllegalArgumentException("apron cannot be canonical persistence owner");
      }
      if (face < -1 || face >= 6) throw new IllegalArgumentException("face");
   }

   public static CanonicalPersistenceKey from(GeometryIdentity geometry, TellusCoordinateContext coordinate) {
      Objects.requireNonNull(geometry, "geometry");
      GeneratorOwnerView view = Objects.requireNonNull(coordinate, "coordinate").owner();
      if (view instanceof CubeNetApronOwnerView apron) view = apron.canonicalOwner();
      if (view instanceof CubeNetCanonicalOwnerView canonical) {
         return new CanonicalPersistenceKey(geometry.stableKey(), GeneratorOwner.CUBE_NET_CANONICAL,
            canonical.block().face(), canonical.block().blockX(), canonical.block().blockZ(),
            canonical.chunk().chunkX(), canonical.chunk().chunkZ());
      }
      if (view instanceof SphericalOwnerView spherical) {
         return new CanonicalPersistenceKey(geometry.stableKey(), GeneratorOwner.SPHERE_FACE,
            spherical.block().face(), spherical.block().blockX(), spherical.block().blockZ(),
            spherical.chunk().chunkX(), spherical.chunk().chunkZ());
      }
      if (view instanceof EarthOwnerView) {
         return new CanonicalPersistenceKey(geometry.stableKey(), GeneratorOwner.EARTH_PROJECTED,
            -1, coordinate.earth().floorBlockX(), coordinate.earth().floorBlockZ(),
            Long.MIN_VALUE, Long.MIN_VALUE);
      }
      throw new IllegalArgumentException("unsupported persistence owner: " + view.kind());
   }

   public String stableKey() {
      return this.geometryKey + "|owner=" + this.owner + "|face=" + this.face
         + "|block=" + this.blockX + "," + this.blockZ
         + "|chunk=" + this.chunkX + "," + this.chunkZ;
   }

   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }
}
