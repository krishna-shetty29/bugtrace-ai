param([Parameter(Mandatory=$true)][string]$RunRecord,[string]$OutputPath)
. (Join-Path $PSScriptRoot 'Use-BugTraceAccess.ps1')

$ErrorActionPreference='Stop'
function Get-BackendIdentity {
 $owners=@(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique)
 if($owners.Count -ne 1){throw 'Expected exactly one backend process listening on port 8080.'}
 $process=Get-Process -Id $owners[0] -ErrorAction Stop
 return @{processId=[int]$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('o')}
}

if(-not(Test-Path -LiteralPath $RunRecord -PathType Leaf)){throw 'Supply the platform-run JSON file.'}
$inputRecord=Get-Content -Raw -LiteralPath $RunRecord|ConvertFrom-Json
$investigation=[guid]::Parse($inputRecord.investigationId).ToString()
$runId=[guid]::Parse($inputRecord.runId).ToString()
$endpoint="http://127.0.0.1:8080/api/investigations/$investigation/benchmark-reproduction/$runId"
$run=Invoke-RestMethod -Uri $endpoint
if($run.state -eq 'BUSY'){throw 'Wait for the running model step before making the restart baseline.'}
$processIdentity=Get-BackendIdentity
$record=Invoke-RestMethod -Method Post -ContentType 'application/json' -Body '{}' -Uri "$endpoint/records/export"
$directory=Join-Path $env:USERPROFILE 'benchmark-measurements\isolation-baselines'
New-Item -ItemType Directory -Path $directory -Force|Out-Null
$id=[guid]::NewGuid().ToString()
$path=Join-Path $directory "$id.json"
if(-not [string]::IsNullOrWhiteSpace($OutputPath)){
 $path=[IO.Path]::GetFullPath($OutputPath)
 if((Split-Path -Parent $path) -ine [IO.Path]::GetFullPath($directory)){throw 'OutputPath must be inside benchmark-measurements\isolation-baselines.'}
}
if(Test-Path -LiteralPath $path){throw 'Baseline file already exists. Use a fresh filename.'}
$baseline=[ordered]@{
 investigationId=$investigation;runId=$runId;recordId=$record.recordId;documentSha256=$record.documentSha256
 backendProcess=$processIdentity;runState=$run.state;provider=$run.provider;calls=$run.calls;reservedOutputTokens=$run.reserved_output_tokens
 noPatchObserved=($run.state -eq 'FINISHED_NO_PATCH' -and $null -eq $run.source_document)
 record=$record;createdAt=[DateTimeOffset]::UtcNow.ToString('o')
}
$baseline|ConvertTo-Json -Depth 70|Set-Content -LiteralPath $path -Encoding UTF8
"Restart baseline saved: $path"
"Run state: $($run.state)"
"No-patch saved run observed: $($baseline.noPatchObserved)"
Write-Host 'Restart only the backend, then run Test-BenchmarkIsolationRestart.ps1 with this printed baseline file.'
