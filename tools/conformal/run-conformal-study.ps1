param(
   [string]$JunitJar = 'build/rfc-junit/junit-platform-console-standalone-1.10.2.jar',
   [string]$OutputDirectory = 'build/rfc-conformal/results'
)
$ErrorActionPreference = 'Stop'
$conformalRepo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location -LiteralPath $conformalRepo
try {
   if (-not (Test-Path -LiteralPath $JunitJar -PathType Leaf)) { throw "JUnit Console Standalone 1.10.2 is required at $JunitJar." }
   $conformalClasses = Join-Path 'build/rfc-conformal' ([Guid]::NewGuid().ToString('N'))
   New-Item -ItemType Directory -Force -Path $conformalClasses | Out-Null
   $conformalSources = @('SphereWorldModel','ClosedQuadSphereTopology','ClosedQuadSphereEmbedding',
      'SphericalLatticePrototype','SphericalIncisionPolicy','SphereSurfaceMapping',
      'ConformalCubedSphere','EquiangularCubedSphere') |
      ForEach-Object { "src/main/java/com/yucareux/tellus/worldgen/global/$_.java" }
   $conformalTests = @('ClosedQuadSphereTopologyTest','SeamGeometryRegressionTest',
      'SphericalLatticePrototypeTest','SphericalIncisionPolicyTest') |
      ForEach-Object { "src/test/java/com/yucareux/tellus/worldgen/$_.java" }
   $conformalTools = Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName
   & javac --release 21 -encoding UTF-8 -cp $JunitJar -d $conformalClasses @conformalSources @conformalTests @conformalTools
   if ($LASTEXITCODE -ne 0) { throw 'Fresh compilation failed.' }
   & java -jar $JunitJar execute --class-path $conformalClasses --scan-class-path --disable-ansi-colors --details summary
   if ($LASTEXITCODE -ne 0) { throw 'Focused validation failed.' }
   & java -Xmx1g -cp $conformalClasses com.yucareux.tellus.research.ConformalCubeStudy $OutputDirectory
   if ($LASTEXITCODE -ne 0) { throw 'Conformal study failed.' }
   Write-Output "Study saved to $OutputDirectory"
} finally { Pop-Location }
