package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleBackend;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import java.util.Objects;

/**
 * Read-only bridge to the existing projected Earth source singletons.
 *
 * <p>It deliberately calls the existing land-cover/elevation accessors rather
 * than copying source rules. Cube-Net and Sphere-Face routes are explicit
 * UNKNOWN in this increment; their spherical sampler integration needs a
 * source-owned resolution/coverage contract before it can be exposed here.</p>
 */
public final class TellusWorldgenSourcesSampleBackend implements TellusSampleBackend {
   public static final String PROVIDER_ID = "tellus:worldgen-sources";
   private final EarthGeneratorSettings settings;

   public TellusWorldgenSourcesSampleBackend() {
      this(EarthGeneratorSettings.DEFAULT);
   }

   public TellusWorldgenSourcesSampleBackend(EarthGeneratorSettings settings) {
      this.settings = Objects.requireNonNull(settings, "settings");
   }

   public static TellusWorldgenSourcesSampleBackend forEarth(EarthGeneratorSettings settings) {
      return new TellusWorldgenSourcesSampleBackend(settings);
   }

   public EarthGeneratorSettings settings() {
      return this.settings;
   }

   @Override
   public String backendId() {
      return PROVIDER_ID;
   }

   public String providerId() {
      return PROVIDER_ID;
   }

   @Override
   public boolean supports(TellusSampleRequest request) {
      Objects.requireNonNull(request, "request");
      return request.coordinate().owner().kind() == GeneratorOwner.EARTH_PROJECTED;
   }

   @Override
   public TellusSample sample(TellusSampleRequest request) {
      Objects.requireNonNull(request, "request");
      GeneratorOwner owner = request.coordinate().owner().kind();
      if (owner != GeneratorOwner.EARTH_PROJECTED) {
         return TellusSample.unknown(request,
            "unsupported TellusWorldgenSources route for owner " + owner
               + "; spherical/Cube-Net integration remains legacy fallback");
      }
      if (Double.compare(request.geometry().worldScale(), this.settings.worldScale()) != 0) {
         return TellusSample.unknown(request,
            "Earth source settings scale does not match request geometry");
      }

      long earthX = request.cacheKey().coordinate().earthX();
      long earthZ = request.cacheKey().coordinate().earthZ();
      if (earthX < Integer.MIN_VALUE || earthX > Integer.MAX_VALUE
         || earthZ < Integer.MIN_VALUE || earthZ > Integer.MAX_VALUE) {
         return TellusSample.unknown(request, "Earth projected coordinate exceeds source integer range");
      }

      int blockX = (int)earthX;
      int blockZ = (int)earthZ;
      double scale = request.geometry().worldScale();
      double resolution = request.resolution().metres();
      boolean nonBlocking = request.nonBlocking();
      boolean visual = request.purpose() == SamplingPurpose.LOD;
      TellusElevationSample elevation;
      TellusCoverSample cover;
      try {
         int rawClass = nonBlocking
            ? TellusWorldgenSources.landCover().sampleCoverClassLocalOnly(blockX, blockZ, scale, resolution)
            : TellusWorldgenSources.landCover().sampleCoverClass(blockX, blockZ, scale, resolution);
         int visualClass = visual
            ? (nonBlocking
               ? TellusWorldgenSources.landCover().sampleVisualCoverClassLocalOnly(blockX, blockZ, scale, resolution)
               : TellusWorldgenSources.landCover().sampleVisualCoverClass(blockX, blockZ, scale, resolution))
            : rawClass;
         boolean coverKnown = rawClass != TellusLandCoverSource.NO_DATA_CLASS
            && rawClass != Integer.MIN_VALUE
            && visualClass != TellusLandCoverSource.NO_DATA_CLASS
            && visualClass != Integer.MIN_VALUE;
         cover = coverKnown
            ? new TellusCoverSample(SampleStatus.PARTIAL, rawClass, visualClass)
            : TellusCoverSample.partial();

         boolean oceanMask = rawClass == TellusLandCoverSource.NO_DATA_CLASS || rawClass == 80;
         TellusElevationSource.ResolvedElevationSample resolved = nonBlocking
            ? TellusWorldgenSources.elevation().sampleResolvedPreviewElevationMetersLocalOnly(
               blockX, blockZ, scale, oceanMask, this.settings.demSelection(), resolution)
            : TellusWorldgenSources.elevation().sampleResolvedPreviewElevationMeters(
               blockX, blockZ, scale, oceanMask, this.settings.demSelection(), resolution);
         boolean elevationKnown = Double.isFinite(resolved.elevationMeters());
         elevation = elevationKnown
            ? TellusElevationSample.partial(resolved.elevationMeters())
            : TellusElevationSample.partial();
      } catch (RuntimeException error) {
         return TellusSample.unknown(request,
            "Tellus source snapshot failed: " + error.getClass().getSimpleName());
      }

      // WaterSurfaceResolver reports projected block heights, while the sample
      // API promises metres. Keep this layer explicit until that conversion is
      // source-owned; never reinterpret a Y sentinel as a water surface.
      TellusWaterSample water = TellusWaterSample.partial();
      if (nonBlocking) {
         return TellusSample.queued(request, PROVIDER_ID, request.geometry().revisionKey(),
            "NON_BLOCKING read used local-only elevation/cover snapshot; water snapshot unavailable");
      }

      boolean anyKnown = Double.isFinite(elevation.metres())
         || cover.rawClass() != Integer.MIN_VALUE;
      String note = anyKnown
         ? "Earth projected source result is PARTIAL; water surface conversion is not exposed"
         : "Earth projected source returned sentinel values";
      if (!anyKnown) {
         return TellusSample.unknown(request, note);
      }
      return new TellusSample(
         request,
         SampleStatus.PARTIAL,
         elevation,
         cover,
         water,
         TellusSampleProvenance.snapshot(PROVIDER_ID, request.geometry().revisionKey(), false, note)
      );
   }
}
