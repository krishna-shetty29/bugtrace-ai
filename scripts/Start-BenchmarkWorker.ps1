param([Parameter(Mandatory=$true)][string]$ImportRecord,[string]$WorkerId = "laptop-worker-01",[string]$BackendUrl = "http://127.0.0.1:8080",[string]$LocalBase,[string]$CacheSeed = (Join-Path $env:USERPROFILE "benchmark-cache\artifacts"))
. (Join-Path $PSScriptRoot 'WorkerBackend.Common.ps1')

$ErrorActionPreference='Stop'
if($WorkerId -notmatch '^[A-Za-z0-9_-]{1,64}$'){throw 'Invalid worker ID.'}
$projectRoot=Split-Path -Parent $PSScriptRoot
if(-not (Test-Path -LiteralPath $ImportRecord -PathType Leaf)){throw 'ImportRecord must name an existing import JSON file, not a folder.'}
$record=Get-Content -Raw -LiteralPath $ImportRecord | ConvertFrom-Json
$registration=[guid]::Parse($record.registrationId).ToString()
$investigation=[guid]::Parse($record.investigationId).ToString()
$backend=Resolve-WorkerBackendOrigin $BackendUrl
$registered=Invoke-WorkerRegistrationPost $backend "/api/benchmark-registrations/$registration/verify"
$verified=Invoke-WorkerRegistrationPost $backend "/api/benchmark-registrations/$registration/snapshots/$investigation/verify"
if($registered.verified -ne $true -or $verified.verified -ne $true){throw 'Benchmark verification did not pass.'}
if([string]::IsNullOrWhiteSpace($LocalBase)){
 if($backend -notin @('http://127.0.0.1:8080','http://localhost:8080')){throw 'Remote startup requires an explicitly prepared -LocalBase.'}
 $LocalBase=$verified.basePath
}
if(-not(Test-Path -LiteralPath $LocalBase -PathType Container)){throw 'Local snapshot base is missing. Run Prepare-WorkerSnapshot.ps1 first.'}
$LocalBase=(Resolve-Path -LiteralPath $LocalBase).Path
$snapshotHome=Split-Path -Parent $LocalBase
$owner=Split-Path -Leaf $snapshotHome
if((Split-Path -Leaf $LocalBase) -ne 'base' -or $owner -notin @([string]$verified.snapshotId,$investigation)){throw 'LocalBase must be the base directory under this snapshot or investigation UUID.'}
$workspaceRoot=Split-Path -Parent $snapshotHome
$dockerEndpoint=$env:WORKER_DOCKER_ENDPOINT
if([string]::IsNullOrWhiteSpace($dockerEndpoint)){$dockerEndpoint='npipe:////./pipe/docker_engine'}
if($dockerEndpoint -notin @('npipe:////./pipe/docker_engine','npipe:////./pipe/dockerDesktopLinuxEngine')){throw 'This Windows demo requires a local Docker named pipe.'}
$image=$registered.registration.environment.containerImage
if($image -ne 'maven@sha256:5496bf5b90ea4ad5af01d63be191f946f15ef5c375a6dd48fc21482a43108ca5'){throw 'Unsupported benchmark image.'}
$previousDockerContext=$env:DOCKER_CONTEXT
try {
 [Environment]::SetEnvironmentVariable('DOCKER_CONTEXT',$null,'Process')
 & docker --host $dockerEndpoint image inspect $image --format '{{.Id}}'
 $imageInspectExit=$LASTEXITCODE
} finally {[Environment]::SetEnvironmentVariable('DOCKER_CONTEXT',$previousDockerContext,'Process')}
if($imageInspectExit -ne 0){throw 'The registered image is not present locally. Prepare the approved image before starting.'}
if(-not (Test-Path -LiteralPath $CacheSeed -PathType Container)){throw 'Prepare an artifact-only cache with Prepare-BenchmarkCache.ps1, or provide -CacheSeed with an existing prepared cache.'}
$values=@{
 WORKER_DOCKER_ENDPOINT=$dockerEndpoint
 WORKER_WORKSPACE_ROOT=$workspaceRoot
 WORKER_BACKEND_URL=$backend
 WORKER_ID=$WorkerId
 WORKER_CACHE_SEED=(Resolve-Path -LiteralPath $CacheSeed).Path
 WORKER_BENCHMARK_REGISTRATION_ID=$registration
 WORKER_BENCHMARK_INVESTIGATION_ID=$investigation
 WORKER_SNAPSHOT_ID=[guid]::Parse($verified.snapshotId).ToString()
 WORKER_CONTENT_HASH=$verified.contentHash
 WORKER_BASE_PATH=$LocalBase
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
