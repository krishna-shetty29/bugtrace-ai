param([Parameter(Mandatory=$true)][string]$Baseline)
. (Join-Path $PSScriptRoot 'Use-BugTraceAccess.ps1')

$ErrorActionPreference='Stop'
function Get-BackendIdentity {
 $owners=@(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique)
 if($owners.Count -ne 1){throw 'Expected exactly one backend process listening on port 8080.'}
 $process=Get-Process -Id $owners[0] -ErrorAction Stop
 return @{processId=[int]$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('o')}
}

if(-not(Test-Path -LiteralPath $Baseline -PathType Leaf)){throw 'Supply the saved isolation baseline JSON file.'}
$before=Get-Content -Raw -LiteralPath $Baseline|ConvertFrom-Json
$investigation=[guid]::Parse($before.investigationId).ToString()
$runId=[guid]::Parse($before.runId).ToString()
$recordId=[guid]::Parse($before.recordId).ToString()
$endpoint="http://127.0.0.1:8080/api/investigations/$investigation/benchmark-reproduction/$runId"
$identity=Get-BackendIdentity
if($identity.processId -eq $before.backendProcess.processId -and $identity.startedAt -eq $before.backendProcess.startedAt){throw 'The same backend process is still running. Restart only the backend before checking persistence.'}
$record=Invoke-RestMethod -Uri "$endpoint/records/$recordId"
$run=Invoke-RestMethod -Uri $endpoint
$passed=($record.recordId -eq $before.recordId -and $record.documentSha256 -eq $before.documentSha256 -and $run.state -eq $before.runState -and $run.calls -eq $before.calls -and $run.reserved_output_tokens -eq $before.reservedOutputTokens)
$report=[ordered]@{
 checkedAt=[DateTimeOffset]::UtcNow.ToString('o');investigationId=$investigation;runId=$runId;recordId=$recordId
 backendProcessChanged=$true;beforeProcess=$before.backendProcess;afterProcess=$identity
 sameRecordHash=($record.documentSha256 -eq $before.documentSha256);sameRunState=($run.state -eq $before.runState)
 sameCallCounters=($run.calls -eq $before.calls -and $run.reserved_output_tokens -eq $before.reservedOutputTokens)
 noPatchObservedBefore=$before.noPatchObserved;passed=$passed;liveModelCalled=$false;liveSmokeReady=$false
}
$path=Join-Path (Split-Path -Parent $Baseline) (([guid]::NewGuid().ToString())+'-restart-result.json')
$report|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $path -Encoding UTF8
"Restart result saved: $path"
"Persistence passed: $passed"
if(-not $passed){throw 'Saved record or run counters changed. Inspect the restart report before continuing.'}
Write-Host 'Live smoke remains blocked until execution-count and repair-count budgets exist and pass their tests.'
