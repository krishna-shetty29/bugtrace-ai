$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$prior=[Environment]::GetEnvironmentVariable('RUN_BENCHMARK_ENVIRONMENT_DOCKER_TEST','Process')
try {
 $env:RUN_BENCHMARK_ENVIRONMENT_DOCKER_TEST='true'
 Push-Location -LiteralPath (Join-Path $projectRoot 'worker')
 try {
  & .\mvnw.cmd '-Dtest=BenchmarkEnvironmentDockerTest' test
  if($LASTEXITCODE -ne 0){throw 'The real Docker environment check failed. Keep the printed evidence and inspect the error.'}
 } finally {Pop-Location}
} finally {[Environment]::SetEnvironmentVariable('RUN_BENCHMARK_ENVIRONMENT_DOCKER_TEST',$prior,'Process')}
