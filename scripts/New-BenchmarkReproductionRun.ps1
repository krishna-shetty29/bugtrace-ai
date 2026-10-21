param(
    [Parameter(Mandatory=$true)][string]$ImportRecord,
    [string]$Query = 'checkout',
    [switch]$BuildIndex
)
. (Join-Path $PSScriptRoot 'Use-BugTraceAccess.ps1')

$ErrorActionPreference = 'Stop'
$record = Get-Content -Raw -LiteralPath $ImportRecord | ConvertFrom-Json
$investigation = [guid]::Parse($record.investigationId).ToString()
$backend = 'http://127.0.0.1:8080'
if ($BuildIndex) {
    Invoke-RestMethod -Method Post -ContentType 'application/json' -Body '{}' -Uri "$backend/api/investigations/$investigation/java-index" -ErrorAction Stop | Out-Null
    Invoke-RestMethod -Method Post -ContentType 'application/json' -Body '{}' -Uri "$backend/api/investigations/$investigation/semantic-index" -ErrorAction Stop | Out-Null
}
$settings = Invoke-RestMethod -Uri "$backend/api/investigations/$investigation/benchmark-reproduction" -ErrorAction Stop
$selected = @($settings.evidence | Where-Object { $_.origin -eq 'CURRENT_SNAPSHOT_CODE' -and $_.sourceRef -like '*.java' } | Select-Object -First 8)
if ($selected.Count -eq 0) { throw 'No indexed Java evidence. Start your existing Ollama service and rerun with -BuildIndex, or build the source/semantic indexes through the investigation page.' }
$runId = [guid]::NewGuid().ToString()
$request = @{id=$runId;query=$Query;evidence=@($selected | ForEach-Object {$_.reference});maxCalls=2;maxOutputTokens=([int]$settings.model.maxOutputTokens * 2);wallSeconds=1800}
$run = Invoke-RestMethod -Method Post -Uri "$backend/api/investigations/$investigation/benchmark-reproduction" -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 8) -ErrorAction Stop
$outputDirectory = Join-Path $env:USERPROFILE 'benchmark-measurements\platform-runs'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$outputPath = Join-Path $outputDirectory "$runId.json"
@{investigationId=$investigation;runId=$runId;model=$settings.model;run=$run} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $outputPath -Encoding UTF8
"Provider: $($settings.model.provider)"
"Model: $($settings.model.model)"
"State: $($run.state)"
"Run record: $outputPath"
'No hypothesis/test model request was sent by this script. Explicitly advance the saved run next.'
