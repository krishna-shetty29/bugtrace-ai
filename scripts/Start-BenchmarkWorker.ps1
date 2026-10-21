param([Parameter(Mandatory=$true)][string]$ImportRecord,[string]$WorkerId = "laptop-worker-01",[string]$CacheSeed = (Join-Path $env:USERPROFILE "benchmark-cache\artifacts"))
. (Join-Path $PSScriptRoot 'Use-BugTraceAccess.ps1')

$ErrorActionPreference='Stop'
if($WorkerId -notmatch '^[A-Za-z0-9_-]{1,64}$'){throw 'Invalid worker ID.'}
$projectRoot=Split-Path -Parent $PSScriptRoot
if(-not (Test-Path -LiteralPath $ImportRecord -PathType Leaf)){throw 'ImportRecord must name an existing import JSON file, not a folder.'}
$record=Get-Content -Raw -LiteralPath $ImportRecord | ConvertFrom-Json
$registration=[guid]::Parse($record.registrationId).ToString()
$investigation=[guid]::Parse($record.investigationId).ToString()
$backend='http://127.0.0.1:8080'
$registered=Invoke-RestMethod -Method Post -ContentType 'application/json' -Body '{}' -Uri "$backend/api/benchmark-registrations/$registration/verify"
$verified=Invoke-RestMethod -Method Post -ContentType 'application/json' -Body '{}' -Uri "$backend/api/benchmark-registrations/$registration/snapshots/$investigation/verify"
if($registered.verified -ne $true -or $verified.verified -ne $true){throw 'Benchmark verification did not pass.'}
$image=$registered.registration.environment.containerImage
if($image -ne 'maven@sha256:5496bf5b90ea4ad5af01d63be191f946f15ef5c375a6dd48fc21482a43108ca5'){throw 'Unsupported benchmark image.'}
& docker image inspect $image --format '{{.Id}}'
if($LASTEXITCODE -ne 0){throw 'The registered image is not present locally. Prepare the approved image before starting.'}
if(-not (Test-Path -LiteralPath $CacheSeed -PathType Container)){throw 'Prepare an artifact-only cache with Prepare-BenchmarkCache.ps1, or provide -CacheSeed with an existing prepared cache.'}
$values=@{
 WORKER_ID=$WorkerId
 WORKER_CACHE_SEED=(Resolve-Path -LiteralPath $CacheSeed).Path
 WORKER_BENCHMARK_REGISTRATION_ID=$registration
 WORKER_BENCHMARK_INVESTIGATION_ID=$investigation
 WORKER_SNAPSHOT_ID=[guid]::Parse($verified.snapshotId).ToString()
 WORKER_CONTENT_HASH=$verified.contentHash
 WORKER_BASE_PATH=$verified.basePath
 WORKER_CONTAINER_IMAGE=$image
}
$previous=@{}
foreach($key in $values.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
try {
 Write-Host "Starting worker for $($verified.caseId), snapshot $($verified.snapshotId)."
 Write-Host 'Worker ready to poll this registered snapshot. Queue an experiment explicitly from the benchmark browser.'
 Push-Location -LiteralPath (Join-Path $projectRoot 'worker')
 try { & .\mvnw.cmd spring-boot:run; if($LASTEXITCODE -ne 0){throw 'Worker startup failed. Inspect the error above.'} }
 finally {Pop-Location}
} finally {foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}}
