param(
    [string]$Source = (Join-Path $env:USERPROFILE '.m2\repository'),
    [string]$Destination = (Join-Path $env:USERPROFILE 'benchmark-cache\artifacts')
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Source -PathType Container)) { throw 'The prepared Maven artifact cache does not exist.' }
if (Test-Path -LiteralPath $Destination) { throw 'Destination already exists. Reuse it or choose a fresh destination; do not overwrite a worker cache in use.' }
$Source = (Resolve-Path -LiteralPath $Source).Path
$Destination = [IO.Path]::GetFullPath($Destination)
if ($Destination.StartsWith($Source.TrimEnd('\') + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Destination must be outside the source cache.' }
$items = @(Get-ChildItem -LiteralPath $Source -Force -Recurse)
if (@($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -gt 0) { throw 'Cache links are not permitted.' }
$artifacts = @($items | Where-Object {
    -not $_.PSIsContainer -and ($_.Name -match '\.(jar|pom|sha1|sha256|md5|lastUpdated)$' -or $_.Name -in @('_remote.repositories','resolver-status.properties') -or $_.Name -match '^maven-metadata[^/\\]*\.xml$')
})
$total = ($artifacts | Measure-Object -Property Length -Sum).Sum
if ($artifacts.Count -eq 0 -or $total -gt 3GB) { throw 'Artifact cache must be nonempty and no larger than 3 GiB.' }
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
foreach ($file in $artifacts) {
    $relative = $file.FullName.Substring($Source.TrimEnd('\').Length).TrimStart('\')
    $target = Join-Path $Destination $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $target
}
Write-Host "Prepared artifact files: $($artifacts.Count)"
Write-Host "Cache seed: $Destination"
Write-Host 'This copies existing artifacts only; it does not download missing dependencies or verify their provenance.'
