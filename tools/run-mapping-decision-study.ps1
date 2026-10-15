param(
   [string]$OutputCsv = 'build/rfc-mapping-decision/mapping-decision-baseline.csv'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $repo
try {
   $classes = Join-Path 'build/rfc-mapping-decision' ([Guid]::NewGuid().ToString('N'))
   New-Item -ItemType Directory -Force -Path $classes | Out-Null
   $sources = @(
      'src/main/java/com/yucareux/tellus/worldgen/global/SphereWorldModel.java',
      'src/main/java/com/yucareux/tellus/worldgen/global/ClosedQuadSphereTopology.java',
      'src/main/java/com/yucareux/tellus/worldgen/global/ClosedQuadSphereEmbedding.java',
      'src/main/java/com/yucareux/tellus/worldgen/global/SphericalIncisionPolicy.java',
      'tools/conformal/ConformalCubedSphere.java',
      'tools/MappingDecisionStudy.java',
      'tools/MappingLocalMetricStudy.java'
   )
   & javac --release 21 -encoding UTF-8 -d $classes @sources
   if ($LASTEXITCODE -ne 0) { throw 'Fresh mapping-study compilation failed.' }
   & java -Xmx1g -cp $classes com.yucareux.tellus.research.MappingDecisionStudy $OutputCsv
   if ($LASTEXITCODE -ne 0) { throw 'Mapping decision study failed.' }
   $outputDirectory = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($OutputCsv))
   $outputStem = [System.IO.Path]::GetFileNameWithoutExtension($OutputCsv)
   $localOutput = Join-Path $outputDirectory ($outputStem + '-local-metrics.csv')
   $metadataOutput = Join-Path $outputDirectory ($outputStem + '-metadata.json')
   & java -Xmx1g -cp $classes com.yucareux.tellus.research.MappingLocalMetricStudy $localOutput
   if ($LASTEXITCODE -ne 0) { throw 'Local metric study failed.' }
   $rows = Import-Csv -LiteralPath $OutputCsv
   if ($rows.Count -ne 24) { throw "Expected 24 comparison rows, got $($rows.Count)." }
   $localRows = Import-Csv -LiteralPath $localOutput
   if ($localRows.Count -ne 12) { throw "Expected 12 local metric rows, got $($localRows.Count)." }
   if (($rows | Where-Object {
      [double]$_.seam_vertex_mismatch_m -gt 1e-7 -or
      [double]$_.seam_interior_mismatch_m -gt 1e-7
   }).Count -ne 0) {
      throw 'Seam mismatch gate failed.'
   }
   if (-not (Test-Path -LiteralPath $metadataOutput -PathType Leaf)) {
      throw "Expected grid metadata at $metadataOutput."
   }
   $metadata = Get-Content -LiteralPath $metadataOutput -Raw | ConvertFrom-Json
   if ($metadata.schema_version -ne 1 -or
       $metadata.grid_version -ne 'tellus:earth-cubed-sphere-rfc-1' -or
       $metadata.topology.face_count -ne 6 -or
       $metadata.topology.ordinary_neighbor_count -ne 4 -or
       $metadata.threshold_status -ne 'provisional') {
      throw 'Grid metadata contract failed.'
   }
   Write-Output "Mapping decision study passed: $OutputCsv"
} finally { Pop-Location }
