param([Parameter(Mandatory=$true)][string]$RunRecord,[switch]$AllowLiveProvider)
. (Join-Path $PSScriptRoot 'Use-BugTraceAccess.ps1')

$ErrorActionPreference = 'Stop'
$record = Get-Content -Raw -LiteralPath $RunRecord | ConvertFrom-Json
$investigation = [guid]::Parse($record.investigationId).ToString()
$runId = [guid]::Parse($record.runId).ToString()
$backend = 'http://127.0.0.1:8080'
$run = Invoke-RestMethod -Uri "$backend/api/investigations/$investigation/benchmark-reproduction/$runId" -ErrorAction Stop
"Saved provider/model: $($run.provider) / $($run.model)"
"Current state: $($run.state)"
if ($run.state -notin @('READY_ANALYSIS','PLAN_READY')) { throw 'This state has no model advance. Load saved output; do not resend.' }
if ($run.provider -ne 'mock' -and -not $AllowLiveProvider) { throw 'This step sends selected public evidence/report to the configured live model and may incur charges. Use -AllowLiveProvider only for an intentional live request.' }
$request = @{requestId=[guid]::NewGuid().ToString()}
$result = Invoke-RestMethod -Method Post -Uri "$backend/api/investigations/$investigation/benchmark-reproduction/$runId/advance" -ContentType 'application/json' -Body ($request | ConvertTo-Json) -ErrorAction Stop
$path = Join-Path (Split-Path -Parent $RunRecord) ("$runId-step-" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.json')
@{investigationId=$investigation;runId=$runId;stepRequestId=$request.requestId;run=$result} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $path -Encoding UTF8
"State: $($result.state)"
"Message: $($result.message)"
"Calls reserved: $($result.calls) / $($result.max_calls)"
"Saved step result: $path"
'No execution job was queued. Inspect plan/source in the saved result.'
