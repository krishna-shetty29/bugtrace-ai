param()
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'LocalStartup.Common.ps1')
$values=Read-LocalConfiguration $root
Assert-LocalJava
$values.RUN_WORKER_PRESENCE_PG_TEST='true'
$values.BUGTRACE_AI_PROVIDER='mock'
$values.OPENAI_API_KEY=''
$previous=@{}
$suffix=[guid]::NewGuid().ToString('N')
$evidence=Join-Path $env:USERPROFILE ('benchmark-measurements\step48-presence\'+$suffix)
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$passed=$false
try {
 foreach($key in $values.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
 Push-Location (Join-Path $root 'backend')
 try {
  & .\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=WorkerPresenceAuthorizationTest,WorkerPresencePostgresTest,TeamPermissionInterceptorTest' "-Dsurefire.reportNameSuffix=$suffix" test | Tee-Object -FilePath (Join-Path $evidence 'backend.log')
  if($LASTEXITCODE -ne 0){throw 'Backend presence checks failed.'}
  $report=Join-Path $root "backend\target\surefire-reports\TEST-com.bugtrace.backend.WorkerPresencePostgresTest-$suffix.xml"
  [xml]$xml=Get-Content -Raw -LiteralPath $report
  if([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0){throw 'PostgreSQL presence check did not pass without skips.'}
 }finally{Pop-Location}
 Push-Location (Join-Path $root 'worker')
 try {
  & .\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=WorkerPresenceClientTest,QueueClientTest,WorkerLocalSnapshotTest' "-Dsurefire.reportNameSuffix=$suffix" test | Tee-Object -FilePath (Join-Path $evidence 'worker.log')
  if($LASTEXITCODE -ne 0){throw 'Worker presence/queue checks failed.'}
 }finally{Pop-Location}
 $passed=$true
}finally{
 foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
 foreach($module in @('backend','worker')){
  Get-ChildItem -LiteralPath (Join-Path $root "$module\target\surefire-reports") -File -ErrorAction SilentlyContinue | Where-Object {$_.Name -like "*-$suffix.*"} | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $evidence ($module+'-'+$_.Name))}
 }
 @{automatedChecksPassed=$passed;checkedAt=[DateTimeOffset]::UtcNow.ToString('o');livePresenceAndRestart='NOT_EVALUATED'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding UTF8
 Write-Host "Presence test evidence: $evidence"
}
if($passed){Write-Host 'Automated presence checks passed. Live startup, idle/offline/revocation and restart acceptance remain required.'}
