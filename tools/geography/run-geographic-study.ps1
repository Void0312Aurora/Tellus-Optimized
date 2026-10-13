param(
   [string]$Python = 'build/geography/venv/Scripts/python.exe',
   [string]$JunitJar = 'build/rfc-junit/junit-platform-console-standalone-1.10.2.jar',
   [string]$DatasetCache = 'build/geography/gshhg-2.3.7',
   [string]$OutputDirectory = 'build/geography/results'
)
$ErrorActionPreference='Stop'
$geographicRepo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location -LiteralPath $geographicRepo
try {
   foreach($geographicRequired in @($Python,$JunitJar,"$DatasetCache/gshhs_f.b","$DatasetCache/gshhs_h.b")) {
      if(-not(Test-Path -LiteralPath $geographicRequired -PathType Leaf)) {throw "Required local input missing: $geographicRequired. See tools/geography/README.md."}
   }
   & $Python -m unittest discover -s tools/geography -p 'test_*.py' -v
   if($LASTEXITCODE -ne 0){throw 'Geographic geometry checks failed'}
   & $Python tools/geography/fit_orientation.py --output $OutputDirectory
   if($LASTEXITCODE -ne 0){throw 'Cube fit failed'}
   $geographicClasses=Join-Path 'build/geography/classes' ([Guid]::NewGuid().ToString('N'))
   New-Item -ItemType Directory -Force $geographicClasses | Out-Null
   $geographicSources=@('SphereWorldModel','ClosedQuadSphereTopology','ClosedQuadSphereEmbedding','SphericalLatticePrototype','SphericalIncisionPolicy') |
      ForEach-Object {"src/main/java/com/yucareux/tellus/worldgen/global/$_.java"}
   $geographicTests=@('ClosedQuadSphereTopologyTest','SeamGeometryRegressionTest','SphericalLatticePrototypeTest','SphericalIncisionPolicyTest') |
      ForEach-Object {"src/test/java/com/yucareux/tellus/worldgen/$_.java"}
   $geographicTools=Get-ChildItem tools/conformal -Filter '*.java' | ForEach-Object FullName
   & javac --release 21 -encoding UTF-8 -cp $JunitJar -d $geographicClasses @geographicSources @geographicTests @geographicTools
   if($LASTEXITCODE -ne 0){throw 'Fresh Java compilation failed'}
   & java -jar $JunitJar execute --class-path $geographicClasses --scan-class-path --disable-ansi-colors --details summary
   if($LASTEXITCODE -ne 0){throw 'Java geometry checks failed'}
   & java -Xmx1g -cp $geographicClasses com.yucareux.tellus.research.GeographicQuarantineStudy "$OutputDirectory/candidate-cube-rotation.csv" $OutputDirectory
   if($LASTEXITCODE -ne 0){throw 'Quarantine boundary export failed'}
   & $Python tools/geography/run_coverage.py --cache $DatasetCache --output $OutputDirectory
   if($LASTEXITCODE -ne 0){throw 'Coastline coverage study failed'}
   & $Python tools/geography/score_osm_probe.py --output $OutputDirectory
   if($LASTEXITCODE -ne 0){throw 'Frozen OSM probe scoring failed'}
   & $Python tools/geography/plot_coverage.py --output $OutputDirectory
   if($LASTEXITCODE -ne 0){throw 'Coverage figure failed'}
   Write-Output "Geographic study saved to $OutputDirectory"
} finally {Pop-Location}
