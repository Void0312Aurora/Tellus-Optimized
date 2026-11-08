package com.yucareux.tellus.worldgen.global;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure constructors for validated coordinate contexts.
 *
 * <p>The factory performs only deterministic coordinate conversion and
 * consistency checks. It does not sample terrain, load registries, resolve
 * generators or mutate caches. Existing generators may adopt it incrementally;
 * no existing consumer is coupled to it by this phase.</p>
 */
public final class TellusCoordinateContextFactory {
   private static final double MAPPING_TOLERANCE = 1.0E-7;

   private TellusCoordinateContextFactory() {
   }

   /**
    * Builds a context from a canonical spherical block address at its cell
    * centre. This is the owner-neutral face runtime form.
    */
   public static ImmutableTellusCoordinateContext fromSphericalBlock(
      SphereWorldModel model,
      SphereWorldModel.SphereBlockAddress block
   ) {
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(block, "block");
      model.chartForBlock(block.face(), block.blockX(), block.blockZ());
      SphereWorldModel.SphereChunkAddress chunk = new SphereWorldModel.SphereChunkAddress(
         block.face(),
         Math.floorDiv(block.blockX(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS),
         Math.floorDiv(block.blockZ(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS)
      );
      SphereWorldModel.GeoPoint geo = model.exactSurfacePoint(
         block.face(), block.blockX() + 0.5, block.blockZ() + 0.5
      );
      return fromGeo(
         model,
         geo,
         new SphericalOwnerView(block, chunk)
      );
   }

   /**
    * Builds a context from a geodetic point and a non-planar owner. The Earth
    * compatibility and spherical-noise views are derived from the same point.
    * Cube-Net owners must use the overload carrying their layout so planar
    * provenance can be checked as well.
    */
   public static ImmutableTellusCoordinateContext fromGeo(
      SphereWorldModel model,
      SphereWorldModel.GeoPoint geo,
      GeneratorOwnerView owner
   ) {
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(geo, "geo");
      Objects.requireNonNull(owner, "owner");
      if (owner instanceof CubeNetCanonicalOwnerView
         || owner instanceof CubeNetApronOwnerView) {
         throw new IllegalArgumentException("Cube-Net owner requires a layout-aware factory");
      }
      EarthCompatibilityPoint earth = EarthCompatibilityPoint.fromGeoPoint(
         geo, model.config().worldScale()
      );
      SphericalNoisePoint noise = SphericalNoisePoint.fromGeoPoint(geo);
      validateNonCubeOwner(model, geo, earth, owner);
      return ImmutableTellusCoordinateContext.withoutCubeNetView(
         geo, earth, noise, owner
      );
   }

   /**
    * Resolves one continuous point in a fixed Cube-Net view. Interior points
    * become canonical owners. A point in a single cut-edge strip becomes an
    * apron owner whose canonical target is validated from both block and chunk
    * provenance. Net edges, vertices, mixed chunks and unresolved strips return
    * {@link Optional#empty()}.
    */
   public static Optional<ImmutableTellusCoordinateContext> fromCubeNetPlanar(
      SphereWorldModel model,
      CubeNetLayout layout,
      CubeNetApronResolver apronResolver,
      double planarX,
      double planarZ
   ) {
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(layout, "layout");
      Objects.requireNonNull(apronResolver, "apronResolver");
      validateLayout(model, layout);
      if (apronResolver.layout() != layout) {
         throw new IllegalArgumentException("apron resolver must use the supplied layout instance");
      }
      if (!Double.isFinite(planarX) || !Double.isFinite(planarZ)) {
         throw new IllegalArgumentException("planar coordinates must be finite");
      }

      CubeNetLayout.PlanarPoint planar = new CubeNetLayout.PlanarPoint(planarX, planarZ);
      CubeNetLayout.FacePoint face;
      try {
         face = layout.fromPlanar(planar);
      } catch (IllegalArgumentException outsideInterior) {
         face = null;
      }
      if (face != null) {
         return Optional.of(fromCanonicalPlanar(model, layout, face, planar));
      }

      CubeNetApronResolver.ApronAddress blockProjection = apronResolver
         .resolve(planarX, planarZ)
         .orElse(null);
      if (blockProjection == null) return Optional.empty();

      int sourceChunkX = floorChunk(planarX);
      int sourceChunkZ = floorChunk(planarZ);
      CubeNetApronResolver.ChunkProjection chunkProjection = apronResolver
         .resolveChunk(sourceChunkX, sourceChunkZ)
         .orElse(null);
      if (chunkProjection == null
         || !same(blockProjection, chunkProjection, sourceChunkX, sourceChunkZ)) {
         return Optional.empty();
      }
      validateApronProvenance(layout, blockProjection, chunkProjection, planar);

      CubeNetLayout.FacePoint targetFacePoint = new CubeNetLayout.FacePoint(
         blockProjection.targetFace(), blockProjection.targetU(), blockProjection.targetV()
      );
      SphereWorldModel.SphereBlockAddress targetBlock = new SphereWorldModel.SphereBlockAddress(
         blockProjection.targetFace().index(), blockProjection.blockX(), blockProjection.blockZ()
      );
      SphereWorldModel.SphereChunkAddress targetChunk = new SphereWorldModel.SphereChunkAddress(
         targetBlock.face(),
         chunkProjection.targetChunkX(),
         chunkProjection.targetChunkZ()
      );
      CubeNetLayout.PlanarPoint canonicalPlanar = layout.toPlanar(targetFacePoint);
      CubeNetCanonicalOwnerView canonicalOwner = new CubeNetCanonicalOwnerView(
         targetBlock, targetChunk, targetFacePoint, canonicalPlanar
      );
      CubeNetApronOwnerView apronOwner = new CubeNetApronOwnerView(
         planar, blockProjection, chunkProjection, canonicalOwner
      );
      SphereWorldModel.GeoPoint geo = exactPoint(model, targetFacePoint);
      EarthCompatibilityPoint earth = EarthCompatibilityPoint.fromGeoPoint(
         geo, model.config().worldScale()
      );
      SphericalNoisePoint noise = SphericalNoisePoint.fromGeoPoint(geo);
      ImmutableTellusCoordinateContext context = ImmutableTellusCoordinateContext.cubeNetView(
         geo, earth, noise, apronOwner, targetFacePoint, planar
      );
      validateCubeNetContext(model, layout, context);
      return Optional.of(context);
   }

   /** Builds a validated canonical Cube-Net context from a face-local point. */
   public static ImmutableTellusCoordinateContext fromCubeNetCanonical(
      SphereWorldModel model,
      CubeNetLayout layout,
      CubeNetLayout.FacePoint facePoint
   ) {
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(layout, "layout");
      Objects.requireNonNull(facePoint, "facePoint");
      validateLayout(model, layout);
      validateFacePoint(model, facePoint);
      CubeNetLayout.PlanarPoint planar = layout.toPlanar(facePoint);
      return fromCanonicalPlanar(model, layout, facePoint, planar);
   }

   private static ImmutableTellusCoordinateContext fromCanonicalPlanar(
      SphereWorldModel model,
      CubeNetLayout layout,
      CubeNetLayout.FacePoint facePoint,
      CubeNetLayout.PlanarPoint planar
   ) {
      validateFacePoint(model, facePoint);
      CubeNetLayout.PlanarPoint expectedPlanar = layout.toPlanar(facePoint);
      requireSamePlanar(expectedPlanar, planar);
      long blockX = (long)Math.floor(facePoint.u());
      long blockZ = (long)Math.floor(facePoint.v());
      SphereWorldModel.SphereBlockAddress block = new SphereWorldModel.SphereBlockAddress(
         facePoint.face().index(), blockX, blockZ
      );
      model.chartForBlock(block.face(), block.blockX(), block.blockZ());
      SphereWorldModel.SphereChunkAddress chunk = new SphereWorldModel.SphereChunkAddress(
         block.face(),
         Math.floorDiv(block.blockX(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS),
         Math.floorDiv(block.blockZ(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS)
      );
      CubeNetCanonicalOwnerView owner = new CubeNetCanonicalOwnerView(
         block, chunk, facePoint, planar
      );
      SphereWorldModel.GeoPoint geo = exactPoint(model, facePoint);
      EarthCompatibilityPoint earth = EarthCompatibilityPoint.fromGeoPoint(
         geo, model.config().worldScale()
      );
      SphericalNoisePoint noise = SphericalNoisePoint.fromGeoPoint(geo);
      ImmutableTellusCoordinateContext context = ImmutableTellusCoordinateContext.cubeNetView(
         geo, earth, noise, owner, facePoint, planar
      );
      validateCubeNetContext(model, layout, context);
      return context;
   }

   private static void validateNonCubeOwner(
      SphereWorldModel model,
      SphereWorldModel.GeoPoint geo,
      EarthCompatibilityPoint earth,
      GeneratorOwnerView owner
   ) {
      EarthCompatibilityPoint expectedEarth = EarthCompatibilityPoint.fromGeoPoint(
         geo, model.config().worldScale()
      );
      if (!expectedEarth.equals(earth)) {
         throw new IllegalArgumentException("Earth view does not match GeoPoint");
      }
      if (owner instanceof EarthOwnerView earthOwner) {
         if (!earthOwner.point().equals(expectedEarth)) {
            throw new IllegalArgumentException("Earth owner does not match GeoPoint");
         }
         return;
      }
      if (owner instanceof SphericalOwnerView sphericalOwner) {
         SphereWorldModel.SphereBlockAddress expected = model.blockForGeo(geo);
         if (!expected.equals(sphericalOwner.block())) {
            throw new IllegalArgumentException("spherical owner does not match GeoPoint");
         }
         return;
      }
      throw new IllegalArgumentException("unsupported coordinate owner: " + owner.getClass().getName());
   }

   private static void validateCubeNetContext(
      SphereWorldModel model,
      CubeNetLayout layout,
      ImmutableTellusCoordinateContext context
   ) {
      EarthCompatibilityPoint expectedEarth = EarthCompatibilityPoint.fromGeoPoint(
         context.geo(), model.config().worldScale()
      );
      if (!expectedEarth.equals(context.earth())) {
         throw new IllegalArgumentException("Earth view does not match GeoPoint");
      }
      SphericalNoisePoint expectedNoise = SphericalNoisePoint.fromGeoPoint(context.geo());
      if (!expectedNoise.equals(context.sphericalNoise())) {
         throw new IllegalArgumentException("spherical noise view does not match GeoPoint");
      }
      GeneratorOwnerView owner = context.owner();
      CubeNetCanonicalOwnerView canonical = owner instanceof CubeNetApronOwnerView apron
         ? apron.canonicalOwner()
         : (CubeNetCanonicalOwnerView)owner;
      validateFacePoint(model, canonical.facePoint());
      requireSamePlanar(layout.toPlanar(canonical.facePoint()), canonical.planarPoint());
      SphereWorldModel.SphereBlockAddress expectedBlock = model.blockForGeo(context.geo());
      if (!expectedBlock.equals(canonical.block())) {
         throw new IllegalArgumentException("canonical owner does not match GeoPoint");
      }
      if (owner instanceof CubeNetApronOwnerView apron) {
         if (!context.planarPoint().isPresent()
            || !context.planarPoint().get().equals(apron.proxyPoint())) {
            throw new IllegalArgumentException("apron proxy view does not match context");
         }
         validateApronProvenance(layout, apron.blockProjection(), apron.chunkProjection(), apron.proxyPoint());
      }
   }

   private static void validateApronProvenance(
      CubeNetLayout layout,
      CubeNetApronResolver.ApronAddress blockProjection,
      CubeNetApronResolver.ChunkProjection chunkProjection,
      CubeNetLayout.PlanarPoint proxy
   ) {
      if (layout.arePlanarNeighbors(blockProjection.sourceFace(), blockProjection.sourceSide())) {
         throw new IllegalArgumentException("apron source seam is a direct planar neighbor");
      }
      CubeNetLayout.Seam seam = layout.seam(
         blockProjection.sourceFace(), blockProjection.sourceSide()
      );
      if (seam.target() != blockProjection.targetFace()) {
         throw new IllegalArgumentException("apron target face disagrees with seam provenance");
      }
      if (chunkProjection.sourceFace() != blockProjection.sourceFace()
         || chunkProjection.targetFace() != blockProjection.targetFace()
         || chunkProjection.sourceChunkX() != floorChunk(proxy.x())
         || chunkProjection.sourceChunkZ() != floorChunk(proxy.z())) {
         throw new IllegalArgumentException("apron chunk provenance disagrees with proxy");
      }
      CubeNetLayout.FacePoint source = sourceFacePoint(layout, blockProjection, proxy);
      double expectedEdge = blockProjection.sourceSide() == CubeNetLayout.Side.WEST
         || blockProjection.sourceSide() == CubeNetLayout.Side.EAST ? source.v() : source.u();
      double expectedPenetration = switch (blockProjection.sourceSide()) {
         case WEST -> -source.u();
         case EAST -> source.u() - layout.faceSize();
         case NORTH -> -source.v();
         case SOUTH -> source.v() - layout.faceSize();
      };
      if (!same(expectedEdge, blockProjection.edgeCoordinate())
         || !same(expectedPenetration, blockProjection.penetration())) {
         throw new IllegalArgumentException("apron source point disagrees with edge provenance");
      }
      CubeNetLayout.FacePoint mapped = layout.mapAcross(
         blockProjection.sourceFace(), blockProjection.sourceSide(),
         blockProjection.edgeCoordinate(), blockProjection.penetration()
      );
      if (mapped.face() != blockProjection.targetFace()
         || !same(mapped.u(), blockProjection.targetU())
         || !same(mapped.v(), blockProjection.targetV())) {
         throw new IllegalArgumentException("apron block projection is not explained by seam mapping");
      }
      if (source.face() != blockProjection.sourceFace()) {
         throw new IllegalArgumentException("apron source point disagrees with provenance");
      }
   }

   private static CubeNetLayout.FacePoint sourceFacePoint(
      CubeNetLayout layout,
      CubeNetApronResolver.ApronAddress projection,
      CubeNetLayout.PlanarPoint proxy
   ) {
      CubeNetLayout.FacePlacement placement = layout.placement(projection.sourceFace());
      double dx = proxy.x() - (double)placement.originX() * layout.faceSize();
      double dz = proxy.z() - (double)placement.originZ() * layout.faceSize();
      double u = placement.uAxisX() * dx + placement.uAxisZ() * dz;
      double v = placement.vAxisX() * dx + placement.vAxisZ() * dz;
      return new CubeNetLayout.FacePoint(projection.sourceFace(), u, v);
   }

   private static SphereWorldModel.GeoPoint exactPoint(
      SphereWorldModel model,
      CubeNetLayout.FacePoint facePoint
   ) {
      return model.exactSurfacePoint(facePoint.face().index(), facePoint.u(), facePoint.v());
   }

   private static void validateLayout(SphereWorldModel model, CubeNetLayout layout) {
      if (layout.faceSize() != model.config().faceBlocks()) {
         throw new IllegalArgumentException("Cube-Net layout face size does not match model");
      }
   }

   private static void validateFacePoint(
      SphereWorldModel model,
      CubeNetLayout.FacePoint point
   ) {
      Objects.requireNonNull(point, "facePoint");
      int size = model.config().faceBlocks();
      if (point.face().index() < 0 || point.face().index() >= SphereWorldModel.FACE_COUNT
         || !(point.u() > 0.0) || !(point.u() < size)
         || !(point.v() > 0.0) || !(point.v() < size)) {
         throw new IllegalArgumentException("canonical face point is outside the owned face interior");
      }
   }

   private static void requireSamePlanar(
      CubeNetLayout.PlanarPoint expected,
      CubeNetLayout.PlanarPoint actual
   ) {
      if (!same(expected.x(), actual.x()) || !same(expected.z(), actual.z())) {
         throw new IllegalArgumentException("planar point does not map to the canonical face point");
      }
   }

   private static boolean same(
      CubeNetApronResolver.ApronAddress block,
      CubeNetApronResolver.ChunkProjection chunk,
      int sourceChunkX,
      int sourceChunkZ
   ) {
      return block.sourceFace() == chunk.sourceFace()
         && block.targetFace() == chunk.targetFace()
         && Math.floorDiv((long)block.blockX(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS)
            == chunk.targetChunkX()
         && Math.floorDiv((long)block.blockZ(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS)
            == chunk.targetChunkZ()
         && chunk.sourceChunkX() == sourceChunkX
         && chunk.sourceChunkZ() == sourceChunkZ;
   }

   private static boolean same(double first, double second) {
      return Math.abs(first - second) <= MAPPING_TOLERANCE;
   }

   private static int floorChunk(double coordinate) {
      return Math.floorDiv((int)Math.floor(coordinate), SphereWorldModel.CHUNK_SIZE_BLOCKS);
   }
}
