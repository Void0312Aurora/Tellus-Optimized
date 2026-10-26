/*
 * Runtime adaptation of the Rančić/Purser series documented in CSIRO CCAM.
 * Reference: csiro/ccam-ccam main/grid/jimcc.f90 at
 * 02295ffb6decf764b7bf2ce518c412975ae68565.
 * Copyright 2015-2026 Commonwealth Scientific Industrial Research Organisation.
 * Tellus modifications: double precision, direct six-face basis, inverse,
 * enclosing caps, local metric API, and explicit mapping metadata (2026).
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE. This runtime adaptation is kept with the project license boundary.
 */
package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/** Thirty-term reference approximation used by the RFC runtime prototype. */
public final class ConformalCubedSphere implements SphereSurfaceMapping {
   public static final String MAP_ID = "rancic-ccam30-double-v1";
   private static final double ROOT2 = Math.sqrt(2), ROOT3 = Math.sqrt(3), ROOT6 = Math.sqrt(6);
   private static final double R = SphereWorldModel.EARTH_RADIUS_METERS;
   private static final double[] COEFFICIENTS = {
      1.47713062600964, -0.38183510510174, -0.05573058001191, -0.00895883606818,
      -0.00791315785221, -0.00486625437708, -0.00329251751279, -0.00235481488325,
      -0.00175870527475, -0.00135681133278, -0.00107459847699, -0.00086944475948,
      -0.00071607115121, -0.00059867100093, -0.00050699063239, -0.00043415191279,
      -0.00037541003286, -0.00032741060100, -0.00028773091482, -0.00025458777519,
      -0.00022664642371, -0.00020289261022, -0.00018254510830, -0.00016499474460,
      -0.00014976117167, -0.00013646173947, -0.00012478875822, -0.00011449267279,
      -0.00010536946150, -0.00009725109376
   };

   @Override public String id() { return MAP_ID; }

   @Override public SphereWorldModel.GeoPoint point(int face, double u, double v) {
      validate(face, u, v);
      return global(face, local(u, v)).geo();
   }

   /** Local differential scale relative to the equiangular face-center reference. */
   public double localScale(double u, double v) {
      validate(0, u, v);
      double x = (1 - Math.abs(u)) * 0.5, y = (1 - Math.abs(v)) * 0.5;
      if (y > x) { double t = x; x = y; y = t; }
      Complex zeta = new Complex(x, y);
      if (zeta.abs() == 0) return 0; // Explicit singular endpoint, not ordinary geometry.
      Complex z = zeta.square().square();
      Complex p = polynomial(z);
      Complex w = stereo(p);
      Complex derivative = w.multiply(zeta.square().multiply(zeta))
         .multiply(polynomialDerivative(z)).divide(p).scale(4.0 / 3.0);
      return 4.0 / Math.PI * derivative.abs() / (1 + w.absSquared());
   }

   /**
    * Uniform derivative bound for each analytic octant in zeta coordinates.
    * P(z)=z Q(z), |z|<=1/4; bound |Q| away from zero by a coefficient sum.
    * |w'| <= 4/(3 sqrt(2)) |zeta|^(1/3) |P'| / |Q|^(2/3).
    */
   public double stereoDerivativeBound() {
      return stereoDerivativeBound(1 / ROOT2);
   }

   /** Conservative derivative bound restricted to an octant radius. */
   private double stereoDerivativeBound(double maxRadius) {
      if (!(maxRadius >= 0.0) || maxRadius > 1 / ROOT2 + 1.0E-15
         || !Double.isFinite(maxRadius)) throw new IllegalArgumentException("octant radius");
      double zRadius = Math.pow(maxRadius, 4.0);
      double qLower = COEFFICIENTS[0], derivativeUpper = COEFFICIENTS[0];
      double power = 1;
      for (int i = 1; i < COEFFICIENTS.length; i++) {
         power *= zRadius;
         qLower -= Math.abs(COEFFICIENTS[i]) * power;
         derivativeUpper += (i + 1) * Math.abs(COEFFICIENTS[i]) * power;
      }
      if (!(qLower > 0)) throw new IllegalStateException("series lost its nonzero Q bound");
      return 4 / (3 * ROOT2) * Math.pow(1 / ROOT2, 1.0 / 3.0)
         * Math.pow(maxRadius / (1 / ROOT2), 1.0 / 3.0)
         * derivativeUpper / Math.pow(qLower, 2.0 / 3.0);
   }

   /** Lowest face number owns a boundary tie within floating-point roundoff. */
   public SphereSurfaceMapping.FaceCoordinate inverse(SphereWorldModel.GeoPoint point) {
      Vector vector = Vector.from(Objects.requireNonNull(point, "point"));
      double maximum = Math.max(Math.abs(vector.x), Math.max(Math.abs(vector.y), Math.abs(vector.z)));
      for (int face = 0; face < 6; face++) {
         if (toLocal(face, vector).x >= maximum - 2.0E-14) return inverseOnFace(face, point);
      }
      throw new IllegalStateException("sphere has no face owner");
   }

   /** Safeguarded Newton inverse; endpoints and failed convergence are explicit. */
   @Override public SphereSurfaceMapping.FaceCoordinate inverseOnFace(int face, SphereWorldModel.GeoPoint point) {
      validate(face, 0, 0);
      Vector target = toLocal(face, Vector.from(Objects.requireNonNull(point, "point")));
      if (target.x <= 0 || Math.abs(target.y) > target.x + 2.0E-13
         || Math.abs(target.z) > target.x + 2.0E-13) throw new IllegalArgumentException("point is outside face");
      double tu = target.y / target.x, tv = target.z / target.x;
      double u = clamp(tu, -1, 1), v = clamp(tv, -1, 1);
      for (int iteration = 0; iteration < 60; iteration++) {
         double[] residual = residual(u, v, tu, tv);
         double error = Math.hypot(residual[0], residual[1]);
         if (error < 4.0E-15) return new SphereSurfaceMapping.FaceCoordinate(face, u, v);
         // The derivative vanishes at a corner. A fixed differencing stencil
         // overwhelms nearby coordinates and stalls the inverse there.
         double h = Math.min(1.0E-5, Math.max(1.0E-10,
            0.2 * Math.hypot(1 - Math.abs(u), 1 - Math.abs(v))));
         double ul = Math.max(-1, u - h), uh = Math.min(1, u + h);
         double vl = Math.max(-1, v - h), vh = Math.min(1, v + h);
         double[] lowU = residual(ul, v, tu, tv), highU = residual(uh, v, tu, tv);
         double[] lowV = residual(u, vl, tu, tv), highV = residual(u, vh, tu, tv);
         double a = (highU[0] - lowU[0]) / (uh - ul), c = (highU[1] - lowU[1]) / (uh - ul);
         double b = (highV[0] - lowV[0]) / (vh - vl), d = (highV[1] - lowV[1]) / (vh - vl);
         double determinant = a * d - b * c;
         if (!Double.isFinite(determinant) || determinant <= 1.0E-18) break;
         double du = (d * residual[0] - b * residual[1]) / determinant;
         double dv = (a * residual[1] - c * residual[0]) / determinant;
         boolean advanced = false;
         for (double step = 1; step >= Math.scalb(1.0, -24); step *= 0.5) {
            double nu = clamp(u - step * du, -1, 1), nv = clamp(v - step * dv, -1, 1);
            double[] next = residual(nu, nv, tu, tv);
            if (Math.hypot(next[0], next[1]) < error) { u = nu; v = nv; advanced = true; break; }
         }
         if (!advanced) break;
      }
      throw new IllegalStateException("conformal inverse did not converge on face " + face + " for " + point);
   }

   private static double[] residual(double u, double v, double targetU, double targetV) {
      Vector vector = local(u, v);
      return new double[]{vector.y / vector.x - targetU, vector.z / vector.x - targetV};
   }

   private static Vector local(double u, double v) {
      double x = (1 - Math.abs(u)) * 0.5, y = (1 - Math.abs(v)) * 0.5;
      boolean swap = y > x;
      return octant(swap ? y : x, swap ? x : y, u < 0 ? -1 : 1, v < 0 ? -1 : 1, swap);
   }

   private static Vector octant(double x, double y, int su, int sv, boolean swap) {
      Complex z = new Complex(x, y).square().square();
      Complex w = stereo(polynomial(z));
      double h = 2 / (1 + w.absSquared());
      double a = h * w.real, b = h * w.imaginary, c = h - 1;
      double normal = a / ROOT6 + b / ROOT2 + c / ROOT3;
      double first = 2 * a / ROOT6 - c / ROOT3;
      double second = -a / ROOT6 + b / ROOT2 - c / ROOT3;
      if (x == y) { double equal = (first + second) * 0.5; first = equal; second = equal; }
      if (x == 0.5) first = 0;
      if (y == 0.5) second = 0;
      return new Vector(normal, su * Math.abs(swap ? second : first), sv * Math.abs(swap ? first : second)).normalized();
   }

   private static Complex stereo(Complex polynomial) {
      if (polynomial.absSquared() == 0) return new Complex(0, 0);
      double phase = Math.atan2(-polynomial.real, polynomial.imaginary) / 3 + Math.PI / 6;
      double magnitude = Math.cbrt(polynomial.abs()) / ROOT2;
      return new Complex(magnitude * Math.cos(phase), magnitude * Math.sin(phase));
   }

   private static Complex polynomial(Complex z) {
      Complex q = new Complex(0, 0);
      for (int i = COEFFICIENTS.length - 1; i >= 0; i--) q = q.multiply(z).add(COEFFICIENTS[i]);
      return q.multiply(z);
   }

   private static Complex polynomialDerivative(Complex z) {
      Complex derivative = new Complex(0, 0);
      for (int i = COEFFICIENTS.length - 1; i >= 0; i--) {
         derivative = derivative.multiply(z).add((i + 1) * COEFFICIENTS[i]);
      }
      return derivative;
   }

   private static Vector global(int face, Vector p) {
      return switch (face) {
         case 0 -> new Vector(p.x, p.z, -p.y);
         case 1 -> new Vector(-p.x, p.z, p.y);
         case 2 -> new Vector(p.y, p.x, -p.z);
         case 3 -> new Vector(p.y, -p.x, p.z);
         case 4 -> new Vector(p.y, p.z, p.x);
         case 5 -> new Vector(-p.y, p.z, -p.x);
         default -> throw new IllegalArgumentException("face");
      };
   }

   private static Vector toLocal(int face, Vector p) {
      return switch (face) {
         case 0 -> new Vector(p.x, -p.z, p.y);
         case 1 -> new Vector(-p.x, p.z, p.y);
         case 2 -> new Vector(p.y, p.x, -p.z);
         case 3 -> new Vector(-p.y, p.x, p.z);
         case 4 -> new Vector(p.z, p.x, p.y);
         case 5 -> new Vector(-p.z, -p.x, p.y);
         default -> throw new IllegalArgumentException("face");
      };
   }

   private static void validate(int face, double u, double v) {
      if (face < 0 || face > 5 || !Double.isFinite(u) || !Double.isFinite(v)
         || Math.abs(u) > 1 || Math.abs(v) > 1) throw new IllegalArgumentException("face coordinates");
   }
   private static double clamp(double x, double low, double high) { return Math.max(low, Math.min(high, x)); }
   private record Complex(double real, double imaginary) {
      private Complex add(double value) { return new Complex(real + value, imaginary); }
      private Complex scale(double value) { return new Complex(real * value, imaginary * value); }
      private Complex square() { return multiply(this); }
      private Complex multiply(Complex p) {
         return new Complex(real * p.real - imaginary * p.imaginary, real * p.imaginary + imaginary * p.real);
      }
      private Complex divide(Complex p) {
         double denominator = p.absSquared();
         return new Complex((real * p.real + imaginary * p.imaginary) / denominator,
            (imaginary * p.real - real * p.imaginary) / denominator);
      }
      private double absSquared() { return real * real + imaginary * imaginary; }
      private double abs() { return Math.hypot(real, imaginary); }
   }

   private record Vector(double x, double y, double z) {
      private Vector normalized() {
         double length = Math.sqrt(x * x + y * y + z * z);
         return new Vector(x / length, y / length, z / length);
      }
      private SphereWorldModel.GeoPoint geo() {
         // atan2 preserves metre-scale derivatives at a geographic pole;
         // asin loses precision when y rounds toward +/-1.
         return new SphereWorldModel.GeoPoint(Math.toDegrees(Math.atan2(y, Math.hypot(x, z))), Math.toDegrees(Math.atan2(z, x)), 0);
      }
      private static Vector from(SphereWorldModel.GeoPoint point) {
         double latitude = Math.toRadians(point.latitude()), longitude = Math.toRadians(point.longitude());
         return new Vector(Math.cos(latitude) * Math.cos(longitude), Math.sin(latitude), Math.cos(latitude) * Math.sin(longitude));
      }
      private double distance(Vector p) {
         double cx = y * p.z - z * p.y, cy = z * p.x - x * p.z, cz = x * p.y - y * p.x;
         return R * Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), x * p.x + y * p.y + z * p.z);
      }
   }
}
