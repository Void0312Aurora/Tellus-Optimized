param(
   [string]$Python = 'build/geography/venv/Scripts/python.exe',
   [string]$DatasetCache = 'build/geography/gshhg-2.3.7',
   [string]$OutputDirectory = 'build/geography/final'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location -LiteralPath $repo
try {
   foreach($required in @(
      $Python,
      "$DatasetCache/gshhs_f.b",
      "$DatasetCache/gshhs_h.b",
      "$OutputDirectory/candidate-cube-rotation.csv",
      "$OutputDirectory/candidate-cube-orientation.json"
   )) {
      if(-not(Test-Path -LiteralPath $required -PathType Leaf)) {
         throw "Required local input missing: $required. Run the pinned geographic study first."
      }
   }
   $classes = Join-Path 'build/geography/classes' ([Guid]::NewGuid().ToString('N'))
   New-Item -ItemType Directory -Force -Path $classes | Out-Null
   $sources = @(
      'src/main/java/com/yucareux/tellus/worldgen/global/SphereWorldModel.java',
      'src/main/java/com/yucareux/tellus/worldgen/global/ClosedQuadSphereTopology.java',
      'src/main/java/com/yucareux/tellus/worldgen/global/ClosedQuadSphereEmbedding.java',
      'tools/conformal/ConformalCubedSphere.java',
      'tools/geography/MappingBoundaryStudy.java'
   )
   & javac --release 21 -encoding UTF-8 -d $classes @sources
   if($LASTEXITCODE -ne 0){throw 'Mapping boundary compilation failed'}
   $boundaries = Join-Path $OutputDirectory 'mapping-comparison'
   & java -Xmx1g -cp $classes com.yucareux.tellus.research.MappingBoundaryStudy `
      "$OutputDirectory/candidate-cube-rotation.csv" $boundaries 128
   if($LASTEXITCODE -ne 0){throw 'Mapping boundary export failed'}
   $result = Join-Path $OutputDirectory 'mapping-geographic-loss.csv'
   & $Python tools/geography/compare_mapping_coverage.py `
      --cache $DatasetCache `
      --orientation "$OutputDirectory/candidate-cube-orientation.json" `
      --boundaries $boundaries `
      --output $result
   if($LASTEXITCODE -ne 0){throw 'Mapping geographic comparison failed'}
   $rows = Import-Csv -LiteralPath $result
   if($rows.Count -ne 192){throw "Expected 192 comparison rows, got $($rows.Count)."}
   Write-Output "Mapping geographic study saved to $result"
} finally { Pop-Location }
