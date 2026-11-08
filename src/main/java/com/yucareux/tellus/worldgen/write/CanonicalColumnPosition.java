package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphericalOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Objects;

/** Canonical ownership resolved into the actual Minecraft horizontal block frame. */
public record CanonicalColumnPosition(int x, int z) {
   /** Uses the same default world-scale model as the native generators. */
   public static CanonicalColumnPosition resolve(WriteContext context) {
      Objects.requireNonNull(context, "context");
      if (context.targetCoordinate().owner() instanceof EarthOwnerView earth) {
         return new CanonicalColumnPosition(Math.toIntExact(earth.point().floorBlockX()),
            Math.toIntExact(earth.point().floorBlockZ()));
      }
      return resolve(context, SphereWorldModel.forWorldScale(context.generation().worldScale()));
   }

   /** Explicit model overload supports non-default face sizes without guessing topology. */
   public static CanonicalColumnPosition resolve(WriteContext context, SphereWorldModel model) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(model, "model");
      if (Double.compare(model.config().worldScale(), context.generation().worldScale()) != 0) {
         throw new IllegalArgumentException("write model world scale differs from context");
      }
      var owner = context.targetCoordinate().owner();
      long x;
      long z;
      if (owner instanceof EarthOwnerView earth) {
         x = earth.point().floorBlockX();
         z = earth.point().floorBlockZ();
      } else if (owner instanceof CubeNetCanonicalOwnerView cube) {
         model.chartForBlock(cube.block().face(), cube.block().blockX(), cube.block().blockZ());
         CubeNetLayout layout = CubeNetLayout.standard(model.config().faceBlocks());
         var expected = layout.toPlanar(cube.facePoint());
         if (Math.abs(expected.x() - cube.planarPoint().x()) > 1.0e-6
            || Math.abs(expected.z() - cube.planarPoint().z()) > 1.0e-6) {
            throw new IllegalArgumentException("write model does not match Cube-Net layout");
         }
         // Resolve the canonical cell center, including reflected axes on grid lines.
         var center = layout.toPlanar(new CubeNetLayout.FacePoint(cube.facePoint().face(),
            cube.block().blockX() + 0.5, cube.block().blockZ() + 0.5));
         x = floor(center.x());
         z = floor(center.z());
      } else if (owner instanceof SphericalOwnerView sphere) {
         if (!model.blockForGeo(context.targetCoordinate().geo()).equals(sphere.block())) {
            throw new IllegalArgumentException("write model does not match spherical coordinate");
         }
         model.chartForBlock(sphere.block().face(), sphere.block().blockX(), sphere.block().blockZ());
         long offset = Math.multiplyExact(model.chunksPerFace() / 2L, 16L);
         x = Math.subtractExact(sphere.block().blockX(), offset);
         z = Math.subtractExact(sphere.block().blockZ(), offset);
      } else {
         throw new IllegalArgumentException("canonical adapter rejects derived apron ownership");
      }
      return new CanonicalColumnPosition(Math.toIntExact(x), Math.toIntExact(z));
   }

   private static long floor(double value) {
      if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value >= (double)Integer.MAX_VALUE + 1.0) {
         throw new IllegalArgumentException("column coordinate is outside Minecraft range");
      }
      return (long)Math.floor(value);
   }
}
