param()
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'LocalStartup.Common.ps1')
Assert-LocalJava
$values=Read-LocalConfiguration $root
$values.RUN_QUEUE_PG_TEST='true'
$values.RUN_BENCHMARK_EXECUTION_PG_TEST='true'
$values.BUGTRACE_AI_PROVIDER='mock'
$values.BUGTRACE_AI_MODEL='mock-v1'
$values.OPENAI_API_KEY=''
$previous=@{}
$run=[guid]::NewGuid().ToString('N')
$folder=Join-Path $env:USERPROFILE ('benchmark-measurements\step48-reconnect\'+$run)
New-Item -ItemType Directory -Force -Path $folder | Out-Null
function Invoke-ReconnectGroup([string]$Module,[string[]]$Classes) {
 $suffix=$run+'-'+$Module
 Push-Location -LiteralPath (Join-Path $root $Module)
 try {
  $old=$ErrorActionPreference
  try{
   $ErrorActionPreference='Continue'
   & .\mvnw.cmd --batch-mode --no-transfer-progress ("-Dtest="+($Classes -join ',')) "-Dsurefire.reportNameSuffix=$suffix" test 2>&1 | Tee-Object -FilePath (Join-Path $folder ($Module+'.log'))
   $exit=$LASTEXITCODE
  }finally{$ErrorActionPreference=$old}
  foreach($class in $Classes){
   $report=Join-Path $root "$Module\target\surefire-reports\TEST-com.bugtrace.$Module.$class-$suffix.xml"
   if(-not(Test-Path -LiteralPath $report -PathType Leaf)){throw "Missing fresh test report: $class"}
   Copy-Item -LiteralPath $report -Destination (Join-Path $folder ($Module+'-'+[IO.Path]::GetFileName($report)))
   [xml]$xml=Get-Content -Raw -LiteralPath $report
   if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0){throw "Reconnect test failed or skipped: $class"}
  }
  if($exit -ne 0){throw "$Module reconnect checks failed."}
 }finally{Pop-Location}
}
$passed=$false
try {
 foreach($key in $values.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
 Invoke-ReconnectGroup backend @('DurableQueuePostgresTest','BenchmarkExecutionPostgresTest','QueueContractsTest','BenchmarkExecutionPolicyTest','TeamPermissionInterceptorTest')
 Invoke-ReconnectGroup worker @('ReconnectStorageTest','QueueReconnectTest','QueueClientTest','WorkerPresenceClientTest','ExecutionDeadlineTest','WorkerAccessFilterTest')
 $passed=$true
}finally{
 foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
 @{automatedReconnectChecksPassed=$passed;checkedAt=[DateTimeOffset]::UtcNow.ToString('o');liveNetworkAndRestartAcceptance='NOT_EVALUATED';liveAiCalls='NOT RUN'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'summary.json') -Encoding UTF8
 Write-Host "Worker reconnect evidence: $folder"
}
if($passed){Write-Host 'Selected reconnect checks passed without skips. Complete live backend interruption and saved-result acceptance separately.'}
