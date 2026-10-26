# Conformal cube reference tools

This directory is a standalone research experiment, outside the Minecraft
source sets. It adapts the 30-term complex series in CSIRO CCAM
[`main/grid/jimcc.f90`](https://github.com/csiro/ccam-ccam/blob/02295ffb6decf764b7bf2ce518c412975ae68565/main/grid/jimcc.f90)
at commit `02295ffb6decf764b7bf2ce518c412975ae68565`.

The upstream copyright is 2015–2026 Commonwealth Scientific Industrial Research
Organisation; the adaptation and these research tools are GPL-3.0-or-later.
See [COPYING](COPYING). The main-source change is a generic `SurfaceMapping`
interface; it contains no CCAM coefficients or implementation. The GPL
candidate is neither compiled by the Fabric source sets nor selected by the
game runtime. Integrating a candidate into the packaged mod is a separate task.

From the repository root, with JDK 21 and a local JUnit Console Standalone 1.10.2:

```powershell
./tools/conformal/run-conformal-study.ps1
```

The runner compiles into a new GUID directory, runs 48 checks covering the
existing lattice, conformal candidate, rotated map and perimeter extraction,
then writes two CSVs and grid metadata under
`build/rfc-conformal/results`. Use `-JunitJar <path>` for a different jar path,
or `-OutputDirectory docs/research` to regenerate the recorded experiment.
It downloads no dependencies and aborts on a failed numerical gate.

`reference-fixtures.csv` contains independent 70-digit direct-power evaluations
of the pinned source's coefficients, reduced to 35 printed digits. The Java
implementation uses binary64 Horner evaluation. To regenerate the fixtures,
download the exact pinned `jimcc.f90` separately and run:

```powershell
python tools/conformal/generate-reference-fixtures.py <path-to-jimcc.f90>
```

The generator requires Python and mpmath and verifies the source SHA-256 before
reading its coefficients. The normal runner reads the committed fixtures and
does not need Python, mpmath, the Fortran source, or a Fortran compiler. This
checks the source formula at high precision; it is not a run of the full CCAM
Fortran model.

See [the experiment record](../../docs/research/conformal-cube-validation.md)
for equations, numerical thresholds, measured results, and remaining gates.

The rotated candidate and its geographic coverage study are documented in
[tools/geography](../geography/README.md). That study uses its own offline runner
and records actual land overlap rather than inferring it from spherical area.
