$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$composeArgs = @('compose', '--env-file', '.\.env', '-f', '.\infra\compose.yaml')

function Invoke-DockerChecked {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed; setup stopped. Your database volume has not been deleted." }
}

# Run before recreating PostgreSQL. The old container must still be running.
$currentVersion = 'SHOW server_version_num;' | & docker @composeArgs exec -T postgres sh -c 'exec psql -X -A -t --username=$POSTGRES_USER --dbname=$POSTGRES_DB'
if ($LASTEXITCODE -ne 0) { throw 'Start your existing PostgreSQL container before running this script.' }
$versionNumber = 0
if (-not [int]::TryParse(($currentVersion -join '').Trim(), [ref]$versionNumber) -or [math]::Floor($versionNumber / 10000) -ne 17) {
    throw 'This update supports an existing PostgreSQL 17 database only.'
}

$backupFolder = Join-Path $env:USERPROFILE 'bugtrace-backups'
New-Item -ItemType Directory -Force -Path $backupFolder | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupFile = Join-Path $backupFolder "before-semantic-$stamp.dump"
$containerBackup = "/tmp/bugtrace-before-semantic-$stamp.dump"
# Custom format stays binary: never pipe pg_dump bytes through Windows PowerShell.
Invoke-DockerChecked -Arguments ($composeArgs + @('exec', '-T', 'postgres', 'sh', '-c', 'exec pg_dump --format=custom --username=$POSTGRES_USER --dbname=$POSTGRES_DB --file=$1', 'sh', $containerBackup))
Invoke-DockerChecked -Arguments ($composeArgs + @('exec', '-T', 'postgres', 'pg_restore', '--list', $containerBackup))
Invoke-DockerChecked -Arguments ($composeArgs + @('cp', "postgres:$containerBackup", $backupFile))
if (-not (Test-Path -LiteralPath $backupFile) -or (Get-Item -LiteralPath $backupFile).Length -eq 0) { throw 'Backup was not saved. Setup stopped.' }
Write-Host "Database backup saved: $backupFile"

$image = 'pgvector/pgvector:0.8.7-pg17-bookworm'
Invoke-DockerChecked -Arguments @('pull', $image)
$targetVersion = & docker run --rm --network none --entrypoint postgres $image --version
if ($LASTEXITCODE -ne 0 -or ($targetVersion -join '') -notmatch '(\d+)\.(\d+)') { throw 'Could not inspect the new PostgreSQL version.' }
$targetNumber = [int]$Matches[1] * 10000 + [int]$Matches[2]
if ([int]$Matches[1] -ne 17 -or $targetNumber -lt $versionNumber) { throw 'Refusing a PostgreSQL major change or minor downgrade. Keep your backup and request an updated image.' }
Write-Host "Version check passed: $targetVersion"

Invoke-DockerChecked -Arguments ($composeArgs + @('up', '-d', '--no-deps', 'postgres'))
$containerId = & docker @composeArgs ps -q postgres
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) { throw 'PostgreSQL container was not found after restart.' }
$healthy = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    $health = & docker inspect --format '{{.State.Health.Status}}' $containerId
    if ($health -eq 'healthy') { $healthy = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $healthy) { throw 'PostgreSQL did not become healthy. Inspect its logs; keep the backup and volume.' }
Invoke-DockerChecked -Arguments ($composeArgs + @('ps', 'postgres'))
Write-Host 'PostgreSQL is ready. Start the backend to let Flyway install the vector extension and tables.'
