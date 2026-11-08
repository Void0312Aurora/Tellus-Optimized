# Round 36: Earth/Cube cover decisions and complete native palette parity

## Production migration

The native Cube-Net Earth-equivalent surface callback previously duplicated the complete Earth cover threshold switch inside CubeNetSurfacePolicy.coverDrivenPalette. It now calls TellusEarthSurfaceDecisionKernel.cover and retains a Cube-local material/depth mapping. The native Earth ocean-floor entry point now calls TellusEarthSurfaceDecisionKernel.oceanFloor while retaining its original clustered random roll and native SurfacePalette.ocean construction.

No roll generation was unified: Cube retains percent(earthX, earthZ, 23L); Earth retains its original SURFACE_VARIANT_SALT mix and OCEAN_FLOOR_SALT. Likewise each path retains its previous depth convention and every native material mapping. In particular, Cube snowy filler remains snow, Cube wetland remains mud/packed_mud, and Cube mangrove remains mud/dirt. Native Earth snowy filler remains dirt, and Earth total thickness still includes its top cell.

Cube's mountain, shoreline, biome-specific, Overture, deepslate and fallback priority remains unchanged. This migration reaches the actual source-aware native Cube callback through resolveEarthEquivalent, but it does not by itself attach that richer context to TellusSphericalSampleProvider or complete semantic biome migration.

Earth's cover result mapping is now a separate private static surfacePaletteForCoverDecision seam, consumed by the existing cover-driven entry point. Source-backed roll/zone calculations stay in the native generator.

## Regression discovered against the pre-migration source

The authoritative old native source was read from checkpoint 49a8f88c194f2a0b58c8c8a7a87bab446cc2944a, before the Round33 native callsite extraction. It distinguishes SurfacePalette.stonyPeaks() from SurfacePalette.strata(12): both visibly use stone at depth 12, but only strata sets the geological-strata flag. The Round33 cover mapper had incorrectly grouped STONY_PEAKS and STRATA into strata(12). That can affect subsequent geology handling despite equal visible top states.

The defect was reproduced before repair: the complete native matrix reported 6,600 mismatches, the 3,160 actual native-driver cases reported 100 mismatches, and the targeted STONY_PEAKS/STRATA assertion failed. After restoring the factory distinction, mc1201 reports zero mismatches in both matrices. The initial standalone fixture also needed explicit platform game/config directories before native static source initialization; these are now installed under cache-only mode and restored after the class. The archived red stage contains actual palette AssertionFailedError results rather than that earlier fixture error.

The correction keeps STONY_PEAKS mapped to stonyPeaks() and STRATA mapped to strata(12). The base-biome STONY_PEAKS branch still intentionally resolves to STRATA as it did before migration; it must not be changed to the built-up cover's ordinary stone palette. The original climate-based built-up helper was also checked against the frozen source and matches it.

## Independent validation

The Cube migration oracle freezes the original numeric cover rules, thresholds, material and depth table. It never calls the new shared kernel for expected values. Its bounded direct matrix covers every real percent roll, twelve ESA codes plus an unknown code, all climate groups plus an unknown byte, alpine boundary heights and representative slope/form metadata, comparing all Decision fields. Additional Earth-equivalent fallback checks use missing slope and a null geodetic-point tripwire so an unintended Overture query fails before source access.

The Earth native oracle freezes the old threshold and palette definitions and compares all six native fields: top, underwaterTop, filler, total depth, landformSurface and strata. Expected values are literal native block states and flags. Badlands plateau top is treated as an unchanged native noise/source boundary rather than reimplemented. This specifically covers the distinction omitted by the older visible-state probes.

Final serial selections passed mc1201 184/184 and mc1211 177/177, with zero failures, errors or skips. Each version reports Earth full-palette 204,000/0, actual Earth cover/ocean drivers 3,160/0, Cube cover 81,900/0, spherical full columns 1,176/0 and materializer combinations 128/0. Fresh original probes retain 13,824/0 block consistency, 256/0 height parity and seven biome cases with only the known bare desert/stony_peaks difference. The evidence validator checks every original biome case separately. Exact suite names, archived assertion-only red stage, counters and current source hashes are stored in docs/validation/round36-earth-cube-surface/test-evidence.json.

## Remaining gates

- The explicit Earth column kernel's generic height calculation and conservative stone strata are still not evidence of complete native Earth column equivalence. They remain opt-in pending resolved source/height/geology integration.
- Spherical semantic providers still need the richer source-aware Cube callback and semantic biome decision path where applicable.
- Native deterministic fixture parity is distinct from full GIS coverage and real in-world acceptance.
- Disk save/reload, metadata transactionality and default lifecycle/registration remain deferred until the production decision gates are met.

No generator Codec, existing save encoding, ownership mapping or default semantic lifecycle was changed in this slice. Rollback parent is recovery/tellus-round35-composite-column-feature at 317113b2dad877f819ba4edacb548e9923ffffc0. Revert the Cube cover delegation, native Earth mapper/ocean delegation and corresponding oracle tests as a coherent slice.
