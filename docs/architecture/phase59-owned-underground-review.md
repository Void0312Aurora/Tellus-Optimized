# Round59 — Generator-owned Cube-Net underground acquisition

## Production boundary

Both versioned Cube generators now expose explicit `prepareNativeUnderground`
and `replayNativeUnderground` entrypoints. Preparation acquires the native
`undergroundColumns` arrays and optional geological 20×20 surface halo from the
same generator-owned lazy source cache, converts actual structure exclusions,
and binds the effective sea/bounds/seed/flags/model and serialized singularity
configuration before invoking the existing detached native preparation bridge.

Disabled/no-feature/NON_BLOCKING inputs stop before source work. Native target
bounds, canonical engine-to-face-local mapping, section/palette alias admission
and configured cap policy are checked before sampling. Seed/materializer binding
is rechecked between capture phases and after detached native preparation.
The opaque `Prepared` retains its actual target, baseline, issuing bridge and
cap configuration. Generator replay checks its current profile/seed, then the
bridge checks issuer/config/actual target and stale full block baseline.

## Default-path preservation

The earlier draft's eager halo capture was removed from `undergroundColumns`.
Its four-component record and native lazy sampling loop remain identical to the
Round58 parent; default carver sampling order is not extended with opt-in work.
The original `applySphericalCarvers`, native sampler/field, Codec and settings
encoding remain unchanged. Sphere-Face's native empty later stages are not
populated with newly invented cave behavior.

## Evidence and limitations

Six new focused native tests cover six faces, negative and rotated engine frames,
dry/inland-water source inputs, exact old native sampler versus replay, frozen
array/halo/exclusion immutability, source-cold replay, custom cap configuration,
invalid target/profile/seed, seed changes during capture and after preparation,
and foreign-generator publication. Actual executed counts and observations are
recorded in the Round59 machine evidence after serial mc1201/mc1211 execution.

This is generator-owned capture from controlled native source fixtures, not a
live GIS oracle or an atomic GIS/cache manifest. Caller-provided engine/source
quiescence is still required across acquisition. The participant lease excludes
cooperating writers of one actual Java target during detached preparation/replay;
it does not coordinate external engine/mod writers or world-key aliases. Block,
marker and dirty recovery is not status/light/tick/heightmap/disk/crash atomicity.
No default lifecycle or persistent-world save/reload gate is enabled here.
