param()
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$folder=Join-Path $env:USERPROFILE ('benchmark-measurements\step48-dashboard\'+[guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$passed=$false
Push-Location -LiteralPath (Join-Path $root 'frontend')
try {
 & node.exe --test tests/workerPresence.test.mjs tests/teamClient.test.mjs tests/benchmarkClient.test.mjs | Tee-Object -FilePath (Join-Path $folder 'client-tests.log')
 if($LASTEXITCODE -ne 0){throw 'Dashboard client tests failed.'}
 & npm.cmd run build | Tee-Object -FilePath (Join-Path $folder 'build.log')
 if($LASTEXITCODE -ne 0){throw 'Frontend type check or build failed.'}
 & npm.cmd run lint | Tee-Object -FilePath (Join-Path $folder 'lint.log')
 if($LASTEXITCODE -ne 0){throw 'Frontend lint failed.'}
 $passed=$true
}finally{
 Pop-Location
 @{automatedChecksPassed=$passed;checkedAt=[DateTimeOffset]::UtcNow.ToString('o');liveBrowserAcceptance='NOT_EVALUATED'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'summary.json') -Encoding UTF8
 Write-Host "Dashboard test evidence: $folder"
}
if($passed){Write-Host 'Dashboard automated checks passed. Complete the live browser acceptance checks in the guide.'}
