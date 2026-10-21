$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$names = @('DB_URL','POSTGRES_USER','POSTGRES_PASSWORD','RUN_BENCHMARK_EXECUTION_PG_TEST')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $envFile = Join-Path $projectRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { throw 'The project .env is required for local PostgreSQL checks.' }
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $parts = $line -split '=',2
            if ($parts.Count -eq 2 -and $parts[0].Trim() -in @('DB_URL','POSTGRES_USER','POSTGRES_PASSWORD')) {
                $value = $parts[1].Trim()
                if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1,$value.Length-2) }
                [Environment]::SetEnvironmentVariable($parts[0].Trim(),$value,'Process')
            }
        }
    }
    if (($env:DB_URL -notlike 'jdbc:postgresql://localhost:*' -and $env:DB_URL -notlike 'jdbc:postgresql://127.0.0.1:*') -or [string]::IsNullOrWhiteSpace($env:POSTGRES_USER) -or [string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) { throw 'Valid local PostgreSQL configuration is required.' }
    $env:RUN_BENCHMARK_EXECUTION_PG_TEST = 'true'
    $suffix = 'benchmark-execution-' + [guid]::NewGuid().ToString('N')
    Push-Location -LiteralPath (Join-Path $projectRoot 'backend')
    try {
        & .\mvnw.cmd '-Dtest=BenchmarkExecutionPolicyTest,BenchmarkExecutionPostgresTest,BenchmarkReproductionTest,BenchmarkCaseRecordsTest,BenchmarkBrowserTest,QueueContractsTest' "-Dsurefire.reportNameSuffix=$suffix" test
        if ($LASTEXITCODE -ne 0) { throw 'Backend execution checks failed.' }
        $reportPath = Join-Path $projectRoot "backend\target\surefire-reports\TEST-com.bugtrace.backend.BenchmarkExecutionPostgresTest-$suffix.xml"
        if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { throw 'Fresh PostgreSQL test report is missing.' }
        [xml]$report = Get-Content -Raw -LiteralPath $reportPath
        if ([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.skipped -ne 0 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0) { throw 'PostgreSQL test did not pass without skips.' }
    } finally { Pop-Location }
    Push-Location -LiteralPath (Join-Path $projectRoot 'worker')
    try {
        & .\mvnw.cmd '-Dtest=BenchmarkExecutionClaimTest,BenchmarkWorkerRegistrationTest,BenchmarkEnvironmentPolicyTest,GeneratedTestOverlayTest,ExecutionDeadlineTest' test
        if ($LASTEXITCODE -ne 0) { throw 'Worker binding checks failed.' }
    } finally { Pop-Location }
    Push-Location -LiteralPath (Join-Path $projectRoot 'frontend')
    try {
        & node --test .\tests\benchmarkClient.test.mjs
        if ($LASTEXITCODE -ne 0) { throw 'Browser client checks failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
        & npm.cmd run lint
        if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
    } finally { Pop-Location }
    Write-Host 'SQL lifecycle, worker contract and frontend checks passed.'
    Write-Host 'The SQL lifecycle uses synthetic provider/worker results. Run Test-BenchmarkEnvironment.ps1 and the real browser execution/restart checks separately.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
