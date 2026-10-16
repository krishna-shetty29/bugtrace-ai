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
$env:RUN_AI_PG_TEST = 'true'
# These selected tests explicitly instantiate mocks and local HTTP fakes, never a live provider.
Set-Location -LiteralPath (Join-Path $projectRoot 'backend')
& .\mvnw.cmd '-Dtest=AiProviderTest,AiCallsPostgresTest' test
if ($LASTEXITCODE -ne 0) { throw 'Model integration tests failed. If PostgreSQL refused the connection, start it and rerun this script.' }
Write-Host 'All model integration tests passed without a live AI call.'
