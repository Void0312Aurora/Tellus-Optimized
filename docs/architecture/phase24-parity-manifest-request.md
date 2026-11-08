# Required source manifests for final parity gate

The deferred implementation is ready for the final replay, but the checkout does not contain the original comparison inputs. Supply two source manifests without regenerating or normalizing their values.

## Cube/Sphere columns

Two JSONL or CSV files accepted by `tools/tellus_parity_compare.py`:

```text
key,material,height,status
```

Requirements:

- exactly 13,824 rows on each side;
- identical canonical key domain;
- material, height and status copied from the baseline and candidate runs;
- no apron-only rows unless they are part of the original manifest;
- preserve the original seed, settings fingerprint, geometry revision and comparator version alongside the file.

Run:

```powershell
python tools/tellus_parity_compare.py `
  --columns-left CUBE_SPHERE_BASELINE.jsonl `
  --columns-right CUBE_SPHERE_CANDIDATE.jsonl
```

Expected gate: `13,824` rows and `0` mismatches.

## Earth/Cube biome cases

One JSONL or CSV file:

```text
case,earth,cube
```

Requirements:

- exactly seven original cases;
- preserve the original case names and values;
- the expected known mismatch is `bare/BWh`;
- no case renaming or climate normalization.

Run:

```powershell
python tools/tellus_parity_compare.py `
  --biome EARTH_CUBE_BIOME.jsonl
```

Expected gate: `7` cases and exactly `1` mismatch named `bare/BWh`.

## Acceptance output

Attach the comparator stdout, stderr, exit code, manifest SHA-256 values, generator/settings fingerprints and the source revision. A missing, empty, malformed or incomplete manifest must remain `EVIDENCE_GAP`; it is not a parity pass.
