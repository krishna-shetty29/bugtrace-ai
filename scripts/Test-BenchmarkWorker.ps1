$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath (Join-Path $projectRoot 'worker')
try {
 & .\mvnw.cmd '-Dtest=BenchmarkEnvironmentPolicyTest,BenchmarkWorkerRegistrationTest,GeneratedTestOverlayTest,WorkerServiceTest,QueueClientTest,MavenCommandsTest,ExecutionEvidenceTest,ExecutionDeadlineTest' test
 if($LASTEXITCODE -ne 0){throw 'Worker checks failed. Read the error above.'}
} finally {Pop-Location}
