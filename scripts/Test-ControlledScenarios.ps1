$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot '.env')) {
    if ($line -match '^\s*(DB_URL|POSTGRES_USER|POSTGRES_PASSWORD)\s*=(.*)$') {
        $name = $Matches[1]; $value = $Matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) { $value = $value.Substring(1, $value.Length - 2) }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}
foreach ($name in @('DB_URL', 'POSTGRES_USER', 'POSTGRES_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { throw "Missing $name in root .env" }
}
$env:RUN_LOOP_PG_TEST = 'true'
# The selected test instantiates a mock provider, a fake embedder and a private PostgreSQL schema only.
Set-Location -LiteralPath (Join-Path $projectRoot 'backend')
& .\mvnw.cmd '-Dtest=InvestigationLoopPostgresTest' test
if ($LASTEXITCODE -ne 0) { throw 'Investigation loop integration test failed. Read the Maven test error above; the cause is not necessarily a database connection problem.' }
Write-Host 'Controlled scenario persistence and queue checks passed using simulated worker results. Run the four real Docker batches separately.'

