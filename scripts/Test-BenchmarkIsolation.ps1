$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$runId=[guid]::NewGuid().ToString()
$evidence=Join-Path $env:USERPROFILE "benchmark-measurements\isolation-checks\$runId"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
Write-Host "Isolation evidence: $evidence"
$envFile=Join-Path $projectRoot '.env'
if(-not(Test-Path -LiteralPath $envFile -PathType Leaf)){throw 'Application root .env was not found.'}
Get-Content -LiteralPath $envFile | ForEach-Object {
 $line=$_.Trim()
 if($line -and -not $line.StartsWith('#')){
  $parts=$line -split '=',2
  if($parts.Count -eq 2 -and $parts[0].Trim() -in @('DB_URL','POSTGRES_USER','POSTGRES_PASSWORD')){
   $value=$parts[1].Trim()
   if($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))){$value=$value.Substring(1,$value.Length-2)}
   [Environment]::SetEnvironmentVariable($parts[0].Trim(),$value,'Process')
  }
 }
}
if($env:DB_URL -notlike 'jdbc:postgresql://*' -or [string]::IsNullOrWhiteSpace($env:POSTGRES_USER) -or [string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)){throw 'PostgreSQL settings are incomplete.'}
function Invoke-IsolationGroup([string]$Name,[string]$Module,[string[]]$Classes){
 $folder=Join-Path $evidence $Name
 New-Item -ItemType Directory -Path $folder -Force | Out-Null
 $suffix="$runId-$Name"
 $nativeExit=-1
 Push-Location -LiteralPath (Join-Path $projectRoot $Module)
 try {
  $previousPreference=$ErrorActionPreference
  try {
   $ErrorActionPreference='Continue'
   & .\mvnw.cmd "-Dtest=$($Classes -join ',')" "-Dsurefire.reportNameSuffix=$suffix" test 2>&1 | Tee-Object -FilePath (Join-Path $folder 'maven.log') | Out-Host
   $nativeExit=$LASTEXITCODE
  } finally {$ErrorActionPreference=$previousPreference}
  $reports=@(Get-ChildItem -LiteralPath '.\target\surefire-reports' -Filter "TEST-*-$suffix.xml" -File -ErrorAction SilentlyContinue)
  $tests=0;$failures=0;$errors=0;$skipped=0
  foreach($report in $reports){
   Copy-Item -LiteralPath $report.FullName -Destination (Join-Path $folder $report.Name)
   [xml]$xml=Get-Content -Raw -LiteralPath $report.FullName
   $tests += [int]$xml.testsuite.tests
   $failures += [int]$xml.testsuite.failures
   $errors += [int]$xml.testsuite.errors
   $skipped += [int]$xml.testsuite.skipped
  }
  $passed=($nativeExit -eq 0 -and $reports.Count -eq $Classes.Count -and $tests -gt 0 -and $failures -eq 0 -and $errors -eq 0 -and $skipped -eq 0)
  $result=[ordered]@{name=$Name;passed=$passed;exitCode=$nativeExit;expectedReports=$Classes.Count;reports=$reports.Count;tests=$tests;failures=$failures;errors=$errors;skipped=$skipped}
  $result|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $folder 'group-result.json') -Encoding UTF8
  return [pscustomobject]$result
 } finally {Pop-Location}
}
$flagNames=@('RUN_BENCHMARK_REPRODUCTION_PG_TEST','RUN_BENCHMARK_CASE_RECORDS_PG_TEST','RUN_BENCHMARK_ENVIRONMENT_DOCKER_TEST')
$prior=@{}
foreach($flag in $flagNames){$prior[$flag]=[Environment]::GetEnvironmentVariable($flag,'Process');[Environment]::SetEnvironmentVariable($flag,'true','Process')}
try {
 $backend=Invoke-IsolationGroup 'backend' 'backend' @('BenchmarkCaseRecordsTest','BenchmarkCaseRecordsPostgresTest','BenchmarkReproductionTest','BenchmarkReproductionPostgresTest','BenchmarkRegistrationServiceTest','SnapshotSearchEngineTest','SemanticChunksTest','GeneratedReproductionTest','InvestigationLoopPolicyTest','ManualWorkflowTest','QueueContractsTest')
 $worker=Invoke-IsolationGroup 'worker' 'worker' @('BenchmarkEnvironmentPolicyTest','BenchmarkWorkerRegistrationTest','GeneratedTestOverlayTest','WorkerServiceTest','MavenCommandsTest','ExecutionEvidenceTest','ExecutionDeadlineTest','QueueClientTest')
 $docker=Invoke-IsolationGroup 'docker-environment' 'worker' @('BenchmarkEnvironmentDockerTest')
 $existing=($backend.passed -and $worker.passed -and $docker.passed)
 $summary=[ordered]@{
  reportVersion='benchmark-isolation-v1';createdAt=[DateTimeOffset]::UtcNow.ToString('o');evidenceFolder=$evidence
  groups=@($backend,$worker,$docker);existingControlsPassed=$existing
  crossCaseEvidence= $(if($backend.passed){'PASSED'}else{'NOT_ESTABLISHED'})
  privateFolderRequests= $(if($backend.passed){'PASSED'}else{'NOT_ESTABLISHED'})
  generatedPathAndHash= $(if($worker.passed){'PASSED'}else{'NOT_ESTABLISHED'})
  unsupportedEnvironment= $(if($worker.passed -and $docker.passed){'PASSED'}else{'NOT_ESTABLISHED'})
  modelRequestTokenDeadlineBudgets= $(if($backend.passed){'PASSED'}else{'NOT_ESTABLISHED'})
  noPatchServicePath= $(if($backend.passed){'PASSED_WITH_TEST_PROVIDER'}else{'NOT_ESTABLISHED'})
  executionCountBudget='NOT_IMPLEMENTED';repairCountBudget='NOT_IMPLEMENTED'
  backendProcessRestart='SEPARATE_CHECK_REQUIRED'
  liveModelCalled=$false;repositoryTestExecuted=$false;liveSmokeReady=$false
  status=$(if($existing){'PARTIAL_IMPLEMENTATION'}else{'FAILED_OR_BLOCKED'})
 }
 $summary|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $evidence 'isolation-results.json') -Encoding UTF8
 Write-Host "Isolation evidence: $evidence"
 Write-Host "Existing controls passed: $existing"
 Write-Host 'Live smoke ready: False. Execution-count and repair-count budgets are not implemented. Complete the separate backend restart check too.'
 if(-not $existing){throw 'One or more isolation groups failed or were blocked. Inspect isolation-results.json and the saved logs.'}
} finally {foreach($flag in $flagNames){[Environment]::SetEnvironmentVariable($flag,$prior[$flag],'Process')}}
