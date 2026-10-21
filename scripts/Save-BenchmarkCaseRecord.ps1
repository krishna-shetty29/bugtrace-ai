param([Parameter(Mandatory=$true)][string]$RunRecord,[string]$Query='')
. (Join-Path $PSScriptRoot 'Use-BugTraceAccess.ps1')

$ErrorActionPreference='Stop'
if(-not (Test-Path -LiteralPath $RunRecord -PathType Leaf)){throw 'RunRecord must name a platform-run JSON file, not a folder.'}
$run=Get-Content -Raw -LiteralPath $RunRecord | ConvertFrom-Json
$investigation=[guid]::Parse($run.investigationId).ToString()
$runId=[guid]::Parse($run.runId).ToString()
$endpoint="http://127.0.0.1:8080/api/investigations/$investigation/benchmark-reproduction/$runId/records"
if([string]::IsNullOrWhiteSpace($Query)){
 $record=Invoke-RestMethod -Method Post -ContentType 'application/json' -Body '{}' -Uri "$endpoint/export"
}else{
 $body=@{query=$Query;kind='ALL';limit=10}|ConvertTo-Json
 $record=Invoke-RestMethod -Method Post -Uri "$endpoint/search" -ContentType 'application/json' -Body $body
}
$id=[guid]::Parse($record.recordId).ToString()
$directory=Join-Path $env:USERPROFILE "benchmark-measurements\platform-records\$runId"
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$path=Join-Path $directory "$id.json"
if(Test-Path -LiteralPath $path){
 $saved=Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
 if($saved.recordId -ne $record.recordId -or $saved.documentSha256 -ne $record.documentSha256){throw 'Existing local record differs. Preserve it for inspection.'}
}else{
 $record | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $path -Encoding UTF8
}
"Saved record: $path"
"Record UUID: $id"
"Kind: $($record.kind)"
"Case: $($record.document.identity.caseId)"
"Run: $runId"
"Record hash: $($record.documentSha256)"
Write-Host 'No model call, experiment, patch application or private grading was performed.'
