param(
   [string]$JunitJar = 'build/rfc-junit/junit-platform-console-standalone-1.10.2.jar',
   [string]$OutputCsv = 'build/rfc-incision/cap-radius-baseline.csv'
)

$ErrorActionPreference = 'Stop'
$incisionRepo = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $incisionRepo
try {
   if (-not (Test-Path -LiteralPath $JunitJar -PathType Leaf)) {
      throw "JUnit Console Standalone 1.10.2 jar is required at $JunitJar; provide -JunitJar if installed elsewhere."
   }
   # A new output directory prevents stale classes from being part of validation.
   $incisionClasses = Join-Path 'build/rfc-incision' ([Guid]::NewGuid().ToString('N'))
   New-Item -ItemType Directory -Force -Path $incisionClasses | Out-Null
   $incisionSources = @('SphereWorldModel', 'ClosedQuadSphereTopology',
      'ClosedQuadSphereEmbedding', 'SphericalLatticePrototype', 'SphericalIncisionPolicy') |
      ForEach-Object { "src/main/java/com/yucareux/tellus/worldgen/global/$_.java" }
   $incisionTests = @('ClosedQuadSphereTopologyTest', 'SeamGeometryRegressionTest',
      'SphericalLatticePrototypeTest', 'SphericalIncisionPolicyTest') |
      ForEach-Object { "src/test/java/com/yucareux/tellus/worldgen/$_.java" }
   & javac --release 21 -encoding UTF-8 -cp $JunitJar -d $incisionClasses @incisionSources @incisionTests tools/SphericalIncisionStudy.java
   if ($LASTEXITCODE -ne 0) { throw 'Fresh compilation failed.' }
   & java -jar $JunitJar execute --class-path $incisionClasses --scan-class-path --disable-ansi-colors --details summary
   if ($LASTEXITCODE -ne 0) { throw 'Focused validation failed.' }
   $incisionRows = & java -Xmx1g -cp $incisionClasses SphericalIncisionStudy
   if ($LASTEXITCODE -ne 0) { throw 'Cap-radius study failed.' }
   $incisionParent = Split-Path -Parent $OutputCsv
   if ($incisionParent) { New-Item -ItemType Directory -Force -Path $incisionParent | Out-Null }
   $incisionRows | Set-Content -LiteralPath $OutputCsv -Encoding utf8
   Write-Output "CSV saved to $OutputCsv"
} finally {
   Pop-Location
}
