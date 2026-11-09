package com.yucareux.tellus.worldgen.sample;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.world.data.geology.Lithology;
import com.yucareux.tellus.world.data.geology.MapUnit;
import com.yucareux.tellus.world.data.geology.Rock;
import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy;
import com.yucareux.tellus.worldgen.StrataPolicy;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereColumnContext;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphericalOwnerView;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.decision.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Pure contracts for the exact native Sphere-Face column route; no Minecraft runtime or source I/O. */
class TellusNativeSphericalColumnSnapshotTest {
   // The native policy intentionally suppresses layered formations at coarse1000m resolution.
   // Use a fine-scale fixture so retaining source coordinates is observably necessary.
   private static final double SCALE = 10.0;
   private static final int SEA = 64;
   private static final int MIN_Y = -64;
   private static final int MAX_Y = 320;
   private static final long SEED = 42L;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   private static final SphereWorldModel.SphereBlockAddress ADDRESS =
      new SphereWorldModel.SphereBlockAddress(0, 17, 29);
   private static final TellusCoordinateContext COORDINATE =
      TellusCoordinateContextFactory.fromSphericalBlock(MODEL, ADDRESS);
   private static final TellusGenerationContext GENERATION =
      new TellusGenerationContext(SEED, SCALE, SEA, MIN_Y, MAX_Y);

   @Test void legacyRawResolvedAndTwoArgumentSnapshotsRemainNativeColumnEmpty() {
      var data = surface();
      var raw = TellusSphericalSurfaceSnapshot.raw(data);
      var resolved = TellusSphericalSurfaceSnapshot.resolved(data, CubeNetSurfacePolicy.Decision.DEFAULT);
      var legacy = new TellusSphericalSurfaceSnapshot(data,
         Optional.of(CubeNetSurfacePolicy.Decision.DEFAULT));
      assertTrue(raw.nativeColumn().isEmpty());
      assertTrue(resolved.nativeColumn().isEmpty());
      assertTrue(legacy.nativeColumn().isEmpty());
      assertEquals(data, raw.surface());
      assertEquals(data, resolved.surface());
   }

   @Test void nativeSnapshotRetainsTheWholeImmutableColumnAndRejectsMismatchedData() {
      var data = surface();
      var point = new SphereWorldModel.GeoPoint(17.25, 31.5, 0.0);
      var column = new SphereChunkSampler.Column(ADDRESS, point, data);
      var snapshot = TellusSphericalSurfaceSnapshot.resolvedNativeColumn(
         column, CubeNetSurfacePolicy.Decision.DEFAULT);
      assertSame(column, snapshot.nativeColumn().orElseThrow());
      assertSame(data, snapshot.surface());
      assertEquals(point, snapshot.nativeColumn().orElseThrow().point());
      assertThrows(IllegalArgumentException.class, () ->
         new TellusSphericalSurfaceSnapshot(data, Optional.empty(), Optional.of(
            new SphereChunkSampler.Column(ADDRESS, point, new SphereChunkSampler.SurfaceData(21.1, 30)))));
      assertThrows(NullPointerException.class, () ->
         TellusSphericalSurfaceSnapshot.resolvedNativeColumn(null, CubeNetSurfacePolicy.Decision.DEFAULT));
      assertThrows(NullPointerException.class, () ->
         TellusSphericalSurfaceSnapshot.resolvedNativeColumn(column, null));
   }

   @Test void nativeProviderPreservesColumnPointAndDoesNotInvokeResolverForQueuedOrCoarseRequests() {
      var column = new SphereChunkSampler.Column(ADDRESS,
         new SphereWorldModel.GeoPoint(17.25, 31.5, 0.0), surface());
      AtomicInteger calls = new AtomicInteger();
      var provider = TellusSphericalSampleProvider.forSphereFaceNativeColumns(request -> {
         calls.incrementAndGet();
         assertSame(COORDINATE, request.coordinate());
         return column;
      });
      var exact = request(COORDINATE, SamplingResolution.exact(SCALE));
      var result = provider.sample(exact);
      assertEquals(SampleStatus.AVAILABLE, result.status());
      assertEquals(1, calls.get());
      assertSame(column, result.sphericalSurface().orElseThrow().nativeColumn().orElseThrow());
      assertEquals(column.point(), result.sphericalSurface().orElseThrow().nativeColumn().orElseThrow().point());

      var queued = provider.sample(request(COORDINATE, SamplingResolution.nonBlocking(SCALE)));
      assertEquals(SampleStatus.QUEUED, queued.status());
      assertEquals(1, calls.get());
      var preview = provider.sample(request(COORDINATE, SamplingResolution.preview(SCALE)));
      assertEquals(SampleStatus.UNKNOWN, preview.status());
      assertEquals(1, calls.get());
      var lod = provider.sample(request(COORDINATE, SamplingResolution.lod(SCALE)));
      assertEquals(SampleStatus.UNKNOWN, lod.status());
      assertEquals(1, calls.get());
   }

   @Test void nativeProviderRejectsUnsupportedOwnerWrongAddressNullColumnAndCallbackFailure() {
      var data = surface();
      AtomicInteger calls = new AtomicInteger();
      var provider = TellusSphericalSampleProvider.forSphereFaceNativeColumns(request -> {
         calls.incrementAndGet();
         return new SphereChunkSampler.Column(
            new SphereWorldModel.SphereBlockAddress(0, 18, 29),
            new SphereWorldModel.GeoPoint(17.25, 31.5, 0.0), data);
      });
      var unsupported = TellusCoordinateContextFactory.fromCubeNetCanonical(MODEL,
         CubeNetLayout.standard(MODEL.config().faceBlocks()),
         new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 17.5, 29.5));
      assertEquals(SampleStatus.UNKNOWN, provider.sample(request(unsupported, SamplingResolution.exact(SCALE))).status());
      assertEquals(0, calls.get());
      assertThrows(IllegalArgumentException.class,
         () -> provider.sample(request(COORDINATE, SamplingResolution.exact(SCALE))));
      assertEquals(1, calls.get());

      var nullProvider = TellusSphericalSampleProvider.forSphereFaceNativeColumns(request -> null);
      assertThrows(NullPointerException.class,
         () -> nullProvider.sample(request(COORDINATE, SamplingResolution.exact(SCALE))));
      var failure = new IllegalStateException("native fixture failure");
      var failingProvider = TellusSphericalSampleProvider.forSphereFaceNativeColumns(request -> { throw failure; });
      assertSame(failure, assertThrows(IllegalStateException.class,
         () -> failingProvider.sample(request(COORDINATE, SamplingResolution.exact(SCALE)))));
   }

   @Test void kernelRejectsNativeColumnWhoseAddressDiffersFromCanonicalOwner() {
      var wrong = new SphereChunkSampler.Column(
         new SphereWorldModel.SphereBlockAddress(0, 18, 29),
         new SphereWorldModel.GeoPoint(17.25, 31.5, 0.0), surface());
      var sample = nativeSample(wrong, COORDINATE);
      var kernel = new TellusSphericalDecisionKernel(TellusSphericalDecisionKernel.Settings.DEFAULT,
         (generation, coordinate, supplied, terrainY) -> BiomeDecision.unknown());
      assertThrows(IllegalArgumentException.class, () -> kernel.decide(GENERATION, COORDINATE, sample));
   }

   @Test void kernelUsesNativeSourcePointForStrataInsteadOfRequestCoordinatePoint() {
      var data = surface();
      var requestContext = new SphereColumnContext(ADDRESS, COORDINATE.geo(), data);
      int terrainY = TellusSphericalColumnRules.height(data.elevationMeters(), SCALE, SEA, 1.0, 1.0);
      var requestLayers = expectedLayers(requestContext, terrainY);
      var candidates = List.of(
         new SphereWorldModel.GeoPoint(17.25, 31.5, 0.0),
         new SphereWorldModel.GeoPoint(52.5, -110.25, 0.0),
         new SphereWorldModel.GeoPoint(-33.75, 140.5, 0.0),
         new SphereWorldModel.GeoPoint(71.25, 12.75, 0.0),
         new SphereWorldModel.GeoPoint(-61.5, -47.25, 0.0),
         new SphereWorldModel.GeoPoint(4.25, 179.5, 0.0));
      var nativePoint = candidates.stream().filter(point -> !expectedLayers(
         new SphereColumnContext(ADDRESS, point, data), terrainY).equals(requestLayers)).findFirst().orElseThrow(
            () -> new AssertionError("geology fixture did not distinguish native and request points"));
      var column = new SphereChunkSampler.Column(ADDRESS, nativePoint, data);
      TellusSample sample = nativeSample(column, COORDINATE);
      var kernel = new TellusSphericalDecisionKernel(TellusSphericalDecisionKernel.Settings.DEFAULT,
         (generation, coordinate, supplied, y) -> BiomeDecision.unknown());
      ColumnDecision decision = kernel.decide(GENERATION, COORDINATE, sample);
      assertEquals(expectedLayers(column.context(), decision.terrainY().orElseThrow()), decision.strata().layers());
      assertNotEquals(requestLayers, decision.strata().layers(),
         "The native source point must be retained for spherical geology/strata");
   }

   @Test void nativeFactoryNullAndRouteDependenciesFailFast() {
      assertThrows(NullPointerException.class,
         () -> TellusSphericalSampleProvider.forSphereFaceNativeColumns(null));
      var provider = TellusSphericalSampleProvider.forSphereFaceNativeColumns(request ->
         new SphereChunkSampler.Column(ADDRESS, COORDINATE.geo(), surface()));
      assertEquals("tellus:sphere-face-native", provider.providerId());
      assertEquals(TellusSphericalSampleProvider.Route.SPHERE_FACE, provider.route());
   }

   private static TellusSampleRequest request(TellusCoordinateContext coordinate, SamplingResolution resolution) {
      return TellusSampleRequest.fromContext(coordinate, SamplingPurpose.TERRAIN_COLUMN,
         resolution, GENERATION.geometry(), SEED);
   }

   private static TellusSample nativeSample(SphereChunkSampler.Column column, TellusCoordinateContext coordinate) {
      var data = column.data();
      var water = data.waterAvailable()
         ? new TellusWaterSample(SampleStatus.AVAILABLE, true, data.oceanWater(), data.waterSurfaceMeters())
         : TellusWaterSample.dry();
      var snapshot = TellusSphericalSurfaceSnapshot.resolvedNativeColumn(column,
         CubeNetSurfacePolicy.resolve(column.point(), data, SCALE, SEED));
      return new TellusSample(request(coordinate, SamplingResolution.exact(SCALE)), SampleStatus.AVAILABLE,
         new TellusElevationSample(SampleStatus.AVAILABLE, data.elevationMeters()),
         new TellusCoverSample(SampleStatus.AVAILABLE, data.coverClass(), data.coverClass()), water,
         TellusSampleProvenance.snapshot("fixture:native-column", "v1", false, "pure native-column contract"),
         Optional.empty(), Optional.of(snapshot));
   }

   private static SphereChunkSampler.SurfaceData surface() {
      return new SphereChunkSampler.SurfaceData(20.1, 30, Double.NaN,
         SphereChunkSampler.SurfaceData.WaterKind.NONE,
         new SphereChunkSampler.SurfaceData.GeologyData(
            Lithology.BASIC_VOLCANIC, new MapUnit(Rock.SANDSTONE, Rock.SHALE, 35)));
   }

   private static List<StrataDecision.Layer> expectedLayers(SphereColumnContext context, int terrainY) {
      StrataPolicy.Column nativeStrata = TellusSphericalColumnRules.strata(context, SCALE, SEA, SEED);
      List<StrataDecision.Layer> expected = new ArrayList<>();
      expected.add(new StrataDecision.Layer(MIN_Y, MIN_Y, SemanticMaterial.BEDROCK));
      int start = MIN_Y + 1;
      SemanticMaterial previous = nativeStrata == null ? SemanticMaterial.STONE
         : TellusSphericalColumnRules.material(nativeStrata.material(start));
      for (long y = (long)start + 1L; y <= terrainY; y++) {
         SemanticMaterial next = nativeStrata == null ? SemanticMaterial.STONE
            : TellusSphericalColumnRules.material(nativeStrata.material((int)y));
         if (next != previous) {
            expected.add(new StrataDecision.Layer(start, (int)y - 1, previous));
            start = (int)y;
            previous = next;
         }
      }
      expected.add(new StrataDecision.Layer(start, terrainY, previous));
      return List.copyOf(expected);
   }
}
