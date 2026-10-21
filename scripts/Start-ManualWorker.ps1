param([string]$WorkerId = "laptop-worker-01")

. (Join-Path $PSScriptRoot 'Use-BugTraceAccess.ps1')
$ErrorActionPreference = 'Stop'
if($WorkerId -notmatch '^[A-Za-z0-9_-]{1,64}$'){throw 'Invalid worker ID.'}
$env:WORKER_ID=$WorkerId
$projectRoot = Split-Path -Parent $PSScriptRoot
$backend = 'http://127.0.0.1:8080'
$fixtures = Invoke-RestMethod -Uri "$backend/api/manual-workflow/fixtures" -ErrorAction Stop
$fixtures = @($fixtures)
if ($fixtures.Count -ne 1) { throw 'Configure exactly one demo fixture in the backend first.' }
$fixture = $fixtures[0]
$owner = [guid]::Parse($fixture.importingInvestigationId).ToString()
$verification = Invoke-RestMethod -Uri "$backend/api/investigations/$owner/snapshot/verify" -ErrorAction Stop
if ($verification.verified -ne $true -or $verification.contentHash -ne $fixture.contentHash) {
    throw 'The configured fixture did not pass content verification.'
}
$env:WORKER_SNAPSHOT_ID = [guid]::Parse($fixture.snapshotId).ToString()
$env:WORKER_BASE_PATH = Join-Path $env:USERPROFILE "bugtrace-workspace\$owner\base"
$env:WORKER_CONTENT_HASH = $verification.contentHash
if (-not (Test-Path -LiteralPath $env:WORKER_BASE_PATH -PathType Container)) {
    throw 'The standard local workspace base was not found. Check the workspace configuration.'
}
$digest = & docker image inspect 'maven:3.10.0-eclipse-temurin-21-noble' --format '{{index .RepoDigests 0}}'
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($digest)) { throw 'Prepare the worker Maven image first.' }
$env:WORKER_CONTAINER_IMAGE = $digest.Trim()
if ($env:WORKER_CONTAINER_IMAGE -notmatch '^maven@sha256:[0-9a-f]{64}$') { throw 'Expected a pinned Maven image digest.' }
Write-Host "Starting the worker for fixture $env:WORKER_SNAPSHOT_ID"
Set-Location -LiteralPath (Join-Path $projectRoot 'worker')
& .\mvnw.cmd spring-boot:run
if ($LASTEXITCODE -ne 0) { throw 'Worker stopped with an error. Read the startup output above.' }
