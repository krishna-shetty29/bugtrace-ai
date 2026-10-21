$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { throw 'Root .env was not found.' }
Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $parts = $line -split '=', 2
        if ($parts.Count -eq 2 -and $parts[0].Trim() -in @('DB_URL','POSTGRES_USER','POSTGRES_PASSWORD')) {
            $value = $parts[1].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1,$value.Length-2) }
            [Environment]::SetEnvironmentVariable($parts[0].Trim(),$value,'Process')
        }
    }
}
if ($env:DB_URL -notlike 'jdbc:postgresql://*' -or [string]::IsNullOrWhiteSpace($env:POSTGRES_USER) -or [string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) { throw 'Valid PostgreSQL settings are required.' }
$previousLocal = $env:RUN_BENCHMARK_BROWSER_LOCAL_TEST
$previousPg = $env:RUN_BENCHMARK_BROWSER_PG_TEST
try {
    $env:RUN_BENCHMARK_BROWSER_LOCAL_TEST = 'true'
    $env:RUN_BENCHMARK_BROWSER_PG_TEST = 'true'
    Push-Location -LiteralPath (Join-Path $projectRoot 'backend')
    try {
        & .\mvnw.cmd '-Dtest=BenchmarkBrowserTest,BenchmarkBrowserPostgresTest,BenchmarkRegistrationServiceTest,BenchmarkCaseRecordsTest,BenchmarkReproductionTest,SnapshotSearchEngineTest,SemanticChunksTest' test
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark browser backend checks failed; read the test output.' }
    } finally { Pop-Location }
} finally {
    $env:RUN_BENCHMARK_BROWSER_LOCAL_TEST = $previousLocal
    $env:RUN_BENCHMARK_BROWSER_PG_TEST = $previousPg
}


Push-Location -LiteralPath (Join-Path $projectRoot 'frontend')
try {
    & node --test .\tests\benchmarkClient.test.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Browser client checks failed.' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    & npm.cmd run lint
    if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
} finally { Pop-Location }
