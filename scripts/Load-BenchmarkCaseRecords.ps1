param([Parameter(Mandatory=$true)][string]$RunRecord)
. (Join-Path $PSScriptRoot 'Use-BugTraceAccess.ps1')

$ErrorActionPreference='Stop'
if(-not (Test-Path -LiteralPath $RunRecord -PathType Leaf)){throw 'Supply the platform-run JSON file.'}
$run=Get-Content -Raw -LiteralPath $RunRecord | ConvertFrom-Json
$investigation=[guid]::Parse($run.investigationId).ToString()
$id=[guid]::Parse($run.runId).ToString()
$endpoint="http://127.0.0.1:8080/api/investigations/$investigation/benchmark-reproduction/$id/records"
$records=Invoke-RestMethod -Uri $endpoint
$count=0
$records | Select-Object id,kind,document_sha256,created_at | Format-Table -AutoSize
foreach($record in $records){
 $count++
 $recordId=[guid]::Parse($record.id).ToString()
 $verified=Invoke-RestMethod -Uri "$endpoint/$recordId"
 if($verified.recordId -ne $recordId -or $verified.documentSha256 -ne $record.document_sha256){throw 'Saved record failed verification.'}
}
"Revalidated saved records: $count"
