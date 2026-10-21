$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$evidence = Join-Path $env:USERPROFILE ('benchmark-measurements\access-checks\' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
Write-Host "Access-check evidence: $evidence"
$suffix = 'team-access-' + [guid]::NewGuid().ToString('N')
$checksPassed=$false
$names = @('DB_URL','POSTGRES_USER','POSTGRES_PASSWORD','RUN_TEAM_ACCESS_PG_TEST')
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
    $env:RUN_TEAM_ACCESS_PG_TEST = 'true'
    Push-Location -LiteralPath (Join-Path $projectRoot 'backend')
    try {
        & .\mvnw.cmd '-Dtest=TeamPermissionPolicyTest,TeamAccessFilterTest,TeamPermissionInterceptorTest,TeamAccessPostgresTest,BenchmarkExecutionPolicyTest,BenchmarkReproductionTest,BenchmarkCaseRecordsTest,BenchmarkBrowserTest,QueueContractsTest' "-Dsurefire.reportNameSuffix=$suffix" test | Tee-Object -FilePath (Join-Path $evidence 'backend.log')
        if ($LASTEXITCODE -ne 0) { throw 'Backend execution checks failed.' }
        $reportPath = Join-Path $projectRoot "backend\target\surefire-reports\TEST-com.bugtrace.backend.TeamAccessPostgresTest-$suffix.xml"
        if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { throw 'Fresh PostgreSQL test report is missing.' }
        [xml]$report = Get-Content -Raw -LiteralPath $reportPath
        if ([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.skipped -ne 0 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0) { throw 'PostgreSQL test did not pass without skips.' }
    } finally { Pop-Location }
    Push-Location -LiteralPath (Join-Path $projectRoot 'worker')
    try {
        & .\mvnw.cmd '-Dtest=WorkerAccessFilterTest,QueueClientTest,BenchmarkExecutionClaimTest,BenchmarkWorkerRegistrationTest,BenchmarkEnvironmentPolicyTest,GeneratedTestOverlayTest,ExecutionDeadlineTest' "-Dsurefire.reportNameSuffix=$suffix" test | Tee-Object -FilePath (Join-Path $evidence 'worker.log')
        if ($LASTEXITCODE -ne 0) { throw 'Worker binding checks failed.' }
    } finally { Pop-Location }
    Push-Location -LiteralPath (Join-Path $projectRoot 'frontend')
    try {
        & node --test .\tests\teamClient.test.mjs .\tests\benchmarkClient.test.mjs | Tee-Object -FilePath (Join-Path $evidence 'frontend-tests.log')
        if ($LASTEXITCODE -ne 0) { throw 'Browser client checks failed.' }
        & npm.cmd run build | Tee-Object -FilePath (Join-Path $evidence 'frontend-build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
        & npm.cmd run lint | Tee-Object -FilePath (Join-Path $evidence 'frontend-lint.log')
        if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
    } finally { Pop-Location }
    $checksPassed=$true
    Write-Host 'Project permissions, SQL persistence, worker authentication and frontend checks passed.'
    Write-Host 'SQL and mock HTTP checks do not replace real browser, backend restart, and worker revocation checks. Complete the README acceptance steps.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    foreach($module in @('backend','worker')) {
        $reports=Join-Path $projectRoot "$module\target\surefire-reports"
        if(Test-Path -LiteralPath $reports){Get-ChildItem -LiteralPath $reports -File | Where-Object {$_.Name -like "*-$suffix.*"} | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $evidence ($module+'-'+$_.Name))}}
    }
    @{automatedChecksPassed=$checksPassed; checkedAt=[DateTimeOffset]::UtcNow.ToString('o'); browserRestartAndWorkerSmoke='NOT_EVALUATED'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'check-summary.json') -Encoding UTF8
    Write-Host "Access-check evidence: $evidence"
}
