param([switch]$IncludeDocker)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'LocalStartup.Common.ps1')
Assert-LocalJava
$values=Read-LocalConfiguration $root
$endpoint=$env:WORKER_DOCKER_ENDPOINT
if([string]::IsNullOrWhiteSpace($endpoint)){$endpoint='npipe:////./pipe/docker_engine'}
if($endpoint -notin @('npipe:////./pipe/docker_engine','npipe:////./pipe/dockerDesktopLinuxEngine')){throw 'Use an approved local Docker Desktop named pipe.'}
$values.WORKER_DOCKER_ENDPOINT=$endpoint
$values.DOCKER_HOST=$endpoint
$values.DOCKER_CONTEXT=''
$values.DOCKER_TLS_VERIFY=''
$values.DOCKER_CERT_PATH=''
$values.BUGTRACE_AI_PROVIDER='mock'
$values.BUGTRACE_AI_MODEL='mock-v1'
$values.OPENAI_API_KEY=''
foreach($name in @('RUN_BENCHMARK_CATALOG_LOCAL_TEST','RUN_BENCHMARK_REGISTRATION_PG_TEST','RUN_BENCHMARK_SNAPSHOT_LOCAL_TEST','RUN_BENCHMARK_SNAPSHOT_PG_TEST','RUN_BENCHMARK_EXECUTION_PG_TEST','RUN_TEAM_ACCESS_PG_TEST')){$values[$name]='true'}
$values.RUN_BENCHMARK_ENVIRONMENT_DOCKER_TEST=if($IncludeDocker){'true'}else{'false'}
$previous=@{}
$run=[guid]::NewGuid().ToString('N')
$folder=Join-Path $env:USERPROFILE ('benchmark-measurements\step48-restrictions\'+$run)
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$results=@()
function Invoke-RestrictionGroup([string]$Module,[string[]]$Classes,[string]$Group='') {
 if(-not $Group){$Group=$Module}
 $suffix=$run+'-'+$Group
 Push-Location -LiteralPath (Join-Path $root $Module)
 try {
  $oldPreference=$ErrorActionPreference
  try{
   $ErrorActionPreference='Continue'
   & .\mvnw.cmd --batch-mode --no-transfer-progress ("-Dtest="+($Classes -join ',')) "-Dsurefire.reportNameSuffix=$suffix" test 2>&1 | Tee-Object -FilePath (Join-Path $folder ($Group+'.log'))
   $exit=$LASTEXITCODE
  }finally{$ErrorActionPreference=$oldPreference}
  foreach($class in $Classes){
   $report=Join-Path $root "$Module\target\surefire-reports\TEST-com.bugtrace.$Module.$class-$suffix.xml"
   if(-not(Test-Path -LiteralPath $report -PathType Leaf)){throw "Missing fresh report for $class"}
   Copy-Item -LiteralPath $report -Destination (Join-Path $folder ($Group+'-'+[IO.Path]::GetFileName($report)))
   [xml]$xml=Get-Content -Raw -LiteralPath $report
   if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0){throw "Restriction check failed or skipped: $class"}
  }
  if($exit -ne 0){throw "$Module restriction checks failed."}
 }finally{Pop-Location}
}
$passed=$false
try{
 foreach($key in $values.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
 Invoke-RestrictionGroup backend @('BenchmarkCatalogTest','BenchmarkCatalogLocalTest','BenchmarkRegistrationServiceTest','BenchmarkRegistrationPostgresTest','BenchmarkSnapshotLocalTest','BenchmarkSnapshotPostgresTest','BenchmarkExecutionPolicyTest','BenchmarkExecutionPostgresTest','BenchmarkReproductionTest','QueueContractsTest','TeamPermissionPolicyTest','TeamPermissionInterceptorTest','TeamAccessPostgresTest')
 Invoke-RestrictionGroup worker @('LocalDockerPolicyTest','WorkerLocalSnapshotTest','BenchmarkExecutionClaimTest','BenchmarkWorkerRegistrationTest','BenchmarkEnvironmentPolicyTest','GeneratedTestOverlayTest','ExecutionDeadlineTest','QueueClientTest','WorkerAccessFilterTest')
 if($IncludeDocker){Invoke-RestrictionGroup worker @('BenchmarkEnvironmentDockerTest') 'docker-environment'}
 $passed=$true
}finally{
 foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
 @{automatedRestrictionsPassed=$passed;checkedAt=[DateTimeOffset]::UtcNow.ToString('o');realDockerProbe=if($IncludeDocker -and $passed){'PASS'}elseif($IncludeDocker){'NOT CONFIRMED'}else{'NOT RUN'};realGeneratedTestAndRestart='NOT_EVALUATED'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'summary.json') -Encoding UTF8
 Write-Host "Trusted-sample restriction evidence: $folder"
}
if($passed){Write-Host 'Selected restriction checks passed without skips. Complete real trusted-sample execution and restart acceptance separately.'}
