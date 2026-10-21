param(
 [Parameter(Mandatory=$true)][string]$ImportRecord,
 [string]$BackendUrl='http://127.0.0.1:8080',
 [string]$Repository=(Join-Path $env:USERPROFILE 'bbp'),
 [string]$Workspace=(Join-Path $env:USERPROFILE 'bugtrace-worker-snapshots')
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'WorkerBackend.Common.ps1')
$backend=Resolve-WorkerBackendOrigin $BackendUrl
if(-not(Test-Path -LiteralPath $ImportRecord -PathType Leaf)){throw 'Supply an import JSON file.'}
$record=Get-Content -Raw -LiteralPath $ImportRecord | ConvertFrom-Json
$registration=[guid]::Parse($record.registrationId).ToString()
$investigation=[guid]::Parse($record.investigationId).ToString()
$registered=Invoke-WorkerRegistrationPost $backend "/api/benchmark-registrations/$registration/verify"
$verified=Invoke-WorkerRegistrationPost $backend "/api/benchmark-registrations/$registration/snapshots/$investigation/verify"
$contract=$registered.registration
$snapshot=[guid]::Parse($verified.snapshotId).ToString()
$hash=[string]$verified.contentHash
if($registered.verified -ne $true -or $verified.verified -ne $true -or $registered.registrationId -ne $registration -or $verified.registrationId -ne $registration -or $verified.investigationId -ne $investigation){throw 'Authenticated registration binding mismatch.'}
if($contract.caseId -notmatch '^case-00[1-6]$' -or $contract.modulePath -ne ('cases/'+$contract.caseId) -or $verified.caseId -ne $contract.caseId -or $verified.modulePath -ne $contract.modulePath -or $contract.releaseCommit -ne '6c4f8856bb7e4d49e953a8b086d594d2f505838d' -or $verified.releaseCommit -ne $contract.releaseCommit -or $hash -notmatch '^[0-9a-f]{64}$' -or $verified.benchmarkContentHash -ne $contract.snapshotContentHash){throw 'Unsupported or mismatched public release.'}
if(-not $env:JAVA_HOME){throw 'Set JAVA_HOME to your JDK 21 installation.'}
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
if(-not(Test-Path -LiteralPath $java -PathType Leaf)){throw 'JAVA_HOME does not contain java.exe.'}
# Source-file mode compiles only the trusted helper, never repository Java sources.
& $java (Join-Path $PSScriptRoot 'snapshot-tools/PrepareWorkerSnapshot.java') $Repository $contract.releaseCommit $contract.modulePath $Workspace $snapshot $hash
if($LASTEXITCODE -ne 0){throw 'Local snapshot preparation failed; no worker was started.'}
Write-Host 'Use this laptop-local directory as -LocalBase:'
Write-Host (Join-Path (Join-Path $Workspace $snapshot) 'base')
Write-Host 'No experiment was queued or executed.'
