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
import java.util.Optional;

/**
 * Stable semantic persistence identity for one column.
 *
 * <p>The canonical owner and address are always the durable identity. An apron
 * contributes only a derived view key; it can never become the persistence
 * owner or create a second canonical record.</p>
 */
public record TellusPersistenceIdentity(
   String geometryKey,
   GeneratorOwner canonicalOwner,
   int face,
   long blockX,
   long blockZ,
   long chunkX,
   long chunkZ,
   Optional<DerivedView> derivedView
) {
   public TellusPersistenceIdentity {
      geometryKey = requireText(geometryKey, "geometryKey");
      Objects.requireNonNull(canonicalOwner, "canonicalOwner");
      derivedView = Objects.requireNonNull(derivedView, "derivedView");
      if (face < -1 || face >= 6) throw new IllegalArgumentException("face");
      if (canonicalOwner == GeneratorOwner.CUBE_NET_APRON) {
         throw new IllegalArgumentException("apron cannot be canonical persistence owner");
      }
      if (derivedView.isPresent()) {
         if (canonicalOwner != GeneratorOwner.CUBE_NET_CANONICAL
            || derivedView.get().owner() != GeneratorOwner.CUBE_NET_APRON) {
            throw new IllegalArgumentException(
               "derived view must be an apron of a canonical Cube-Net owner");
         }
      }
   }

   public static TellusPersistenceIdentity from(
      GeometryIdentity geometry, TellusCoordinateContext coordinate
   ) {
      Objects.requireNonNull(geometry, "geometry");
      GeneratorOwnerView owner = Objects.requireNonNull(coordinate, "coordinate").owner();
      if (owner instanceof CubeNetApronOwnerView apron) {
         CubeNetCanonicalOwnerView canonical = apron.canonicalOwner();
         return new TellusPersistenceIdentity(
            geometry.stableKey(), GeneratorOwner.CUBE_NET_CANONICAL,
            canonical.block().face(), canonical.block().blockX(), canonical.block().blockZ(),
            canonical.chunk().chunkX(), canonical.chunk().chunkZ(),
            Optional.of(DerivedView.apron(apron))
         );
      }
      if (owner instanceof CubeNetCanonicalOwnerView canonical) {
         return new TellusPersistenceIdentity(
            geometry.stableKey(), GeneratorOwner.CUBE_NET_CANONICAL,
            canonical.block().face(), canonical.block().blockX(), canonical.block().blockZ(),
            canonical.chunk().chunkX(), canonical.chunk().chunkZ(), Optional.empty()
         );
      }
      if (owner instanceof SphericalOwnerView spherical) {
         return new TellusPersistenceIdentity(
            geometry.stableKey(), GeneratorOwner.SPHERE_FACE,
            spherical.block().face(), spherical.block().blockX(), spherical.block().blockZ(),
            spherical.chunk().chunkX(), spherical.chunk().chunkZ(), Optional.empty()
         );
      }
      if (owner instanceof EarthOwnerView) {
         long x = coordinate.earth().floorBlockX();
         long z = coordinate.earth().floorBlockZ();
         return new TellusPersistenceIdentity(
            geometry.stableKey(), GeneratorOwner.EARTH_PROJECTED,
            -1, x, z, Long.MIN_VALUE, Long.MIN_VALUE, Optional.empty()
         );
      }
      throw new IllegalArgumentException("unsupported coordinate owner: " + owner.kind());
   }

   public boolean canonical() {
      return this.derivedView.isEmpty();
   }

   public boolean apronMirror() {
      return this.derivedView.isPresent();
   }

   /** Stable key for canonical storage; derived apron views are excluded. */
   public String canonicalKey() {
      return this.geometryKey + "|owner=" + this.canonicalOwner + "|face=" + this.face
         + "|block=" + this.blockX + "," + this.blockZ
         + "|chunk=" + this.chunkX + "," + this.chunkZ;
   }

   public record DerivedView(GeneratorOwner owner, long viewXBits, long viewZBits) {
      public DerivedView {
         Objects.requireNonNull(owner, "owner");
         if (owner != GeneratorOwner.CUBE_NET_APRON) {
            throw new IllegalArgumentException("derived persistence view must be apron");
         }
      }

      private static DerivedView apron(CubeNetApronOwnerView apron) {
         return new DerivedView(
            GeneratorOwner.CUBE_NET_APRON,
            Double.doubleToLongBits(apron.proxyPoint().x()),
            Double.doubleToLongBits(apron.proxyPoint().z())
         );
      }
   }

   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }
}
