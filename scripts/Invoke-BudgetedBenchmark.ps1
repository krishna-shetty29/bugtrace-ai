param(
    [Parameter(Mandatory=$true)][string]$ImportRecord,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:/-]{1,150}$')][string]$ExpectedModel,
    [ValidateRange(1,8)][int]$MaxCalls=2,
    [ValidateRange(128,32000)][int]$MaxOutputReservation=3000,
    [ValidateRange(120,1800)][int]$WallSeconds=600,
    [string]$OutputDirectory,
    [switch]$AllowLiveProvider
)
$ErrorActionPreference='Stop'
if(-not $AllowLiveProvider){throw 'Live benchmark requires explicit -AllowLiveProvider. No request sent.'}
if($env:CI -eq 'true' -and $env:GITHUB_EVENT_NAME -ne 'workflow_dispatch'){throw 'Ordinary CI cannot run live benchmarks. Use the manual benchmark workflow.'}
if($env:BUGTRACE_ACCESS_KEY -notmatch '^[A-Za-z0-9_-]{43}$'){throw 'Load a personal project key through the hidden access-key prompt or protected benchmark environment.'}
if(-not(Test-Path -LiteralPath $ImportRecord -PathType Leaf)){throw 'Supply an existing public case import JSON file, not a folder.'}
$import=Get-Content -Raw -LiteralPath $ImportRecord | ConvertFrom-Json
$investigation=[guid]::Parse($import.investigationId).ToString()
$registration=[guid]::Parse($import.registrationId).ToString()
$backend='http://127.0.0.1:8080'
$headers=@{Authorization='Bearer '+$env:BUGTRACE_ACCESS_KEY}
function Call-Backend([string]$Method,[string]$Path,$Body=$null) {
    $request=@{Method=$Method;Uri=$backend+'/api'+$Path;Headers=$headers;TimeoutSec=150;MaximumRedirection=0;ErrorAction='Stop'}
    if($null -ne $Body){$request.ContentType='application/json';$request.Body=($Body | ConvertTo-Json -Depth 30 -Compress)}
    try {Microsoft.PowerShell.Utility\Invoke-RestMethod @request}
    catch {throw 'Backend request failed or timed out. No automatic retry was made; inspect the saved run UUID before retrying.'}
}
$null=Call-Backend 'POST' "/benchmark-registrations/$registration/verify" @{}
$verified=Call-Backend 'POST' "/benchmark-registrations/$registration/snapshots/$investigation/verify" @{}
if($verified.verified -ne $true){throw 'Public benchmark snapshot verification failed.'}
$settings=Call-Backend 'GET' "/investigations/$investigation/benchmark-reproduction"
if($settings.model.provider -ne 'openai' -or $settings.model.mock -eq $true){throw 'This job requires the intentionally configured live OpenAI backend adapter; mock is not a live evaluation.'}
if($settings.model.model -cne $ExpectedModel){throw 'Configured model does not match ExpectedModel. No generation request sent.'}
$perCall=[int]$settings.model.maxOutputTokens
if($perCall -lt 128 -or $perCall -gt 4000 -or $perCall*$MaxCalls -gt $MaxOutputReservation -or $MaxOutputReservation -gt $perCall*8){throw 'Output reservation must cover every permitted call and stay within eight configured per-call reservations.'}
$evidence=@($settings.evidence | Where-Object {$_.origin -eq 'CURRENT_SNAPSHOT_CODE' -and $_.reference.kind -eq 'CODE'} | Select-Object -First 8)
if($evidence.Count -eq 0){throw 'Prepare public source evidence through the platform first. This job does not build indexes or retrieve private grading data.'}
$runId=[guid]::NewGuid().ToString()
if(-not $OutputDirectory){$OutputDirectory=Join-Path $env:USERPROFILE 'benchmark-measurements\explicit-live-jobs'}
$folder=Join-Path $OutputDirectory $runId
New-Item -ItemType Directory -Path $folder -ErrorAction Stop | Out-Null
function Save-Record([string]$Name,$Value){$Value | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath (Join-Path $folder $Name) -Encoding UTF8}
$started=[DateTimeOffset]::UtcNow
$timer=[Diagnostics.Stopwatch]::StartNew()
$summary=[ordered]@{scope='PROPOSAL_ONLY';investigationId=$investigation;registrationId=$registration;runId=$runId;
    provider='openai';model=$ExpectedModel;maxModelCalls=$MaxCalls;outputTokensPerCall=$perCall;maxOutputReservation=$MaxOutputReservation;
    wallSeconds=$WallSeconds;maximumExperimentJobs=0;maximumCompilerRepairs=0;started=$started.ToString('o');
    inputTokenCap='NOT ENFORCED';monetaryCostCap='NOT ENFORCED';cost='UNKNOWN - inspect actual provider billing';state='NOT_CREATED';terminationReason='NOT_FINISHED'}
Save-Record 'job.json' $summary
$run=$null
try {
    $body=@{id=$runId;query='Investigate this public case. Ground claims in evidence, distinguish proposed checks from executed checks, and abstain when evidence is insufficient.';
        evidence=@($evidence | ForEach-Object {$_.reference});maxCalls=$MaxCalls;maxOutputTokens=$MaxOutputReservation;wallSeconds=$WallSeconds}
    Save-Record 'request.json' $body
    $run=Call-Backend 'POST' "/investigations/$investigation/benchmark-reproduction" $body
    Save-Record 'created-run.json' $run
    for($attempt=1;$attempt -le $MaxCalls;$attempt++) {
        if($run.state -notin @('READY_ANALYSIS','PLAN_READY')){break}
        if($timer.Elapsed.TotalSeconds -ge $WallSeconds){$summary.terminationReason='CLIENT_WALL_LIMIT';break}
        $step=@{requestId=[guid]::NewGuid().ToString()}
        Save-Record ("step-$attempt-request.json") $step
        $run=Call-Backend 'POST' "/investigations/$investigation/benchmark-reproduction/$runId/advance" $step
        Save-Record ("step-$attempt-result.json") $run
    }
    $run=Call-Backend 'GET' "/investigations/$investigation/benchmark-reproduction/$runId"
    Save-Record 'final-run.json' $run
    $summary.state=$run.state
    $summary.callsReserved=$run.calls
    $summary.outputTokensReserved=$run.reserved_output_tokens
    $summary.terminationReason=switch($run.state){
        'TEST_READY' {'PROPOSAL_READY_UNEXECUTED'}
        'FINISHED_NO_PATCH' {'FINISHED_NO_PATCH'}
        'PLAN_READY' {'MODEL_CALL_LIMIT_REACHED'}
        default {$run.state}
    }
    foreach($step in @($run.steps)) {
        if($step.call_id){$call=[guid]::Parse($step.call_id).ToString();Save-Record ("model-call-$call.json") (Call-Backend 'GET' "/investigations/$investigation/ai/calls/$call")}
    }
    if($run.state -notin @('TEST_READY','FINISHED_NO_PATCH','PLAN_READY')){throw 'Benchmark did not finish with a proposal or supported abstention; inspect saved state.'}
} catch {
    $summary.terminationReason='FAILED_OR_UNCERTAIN - inspect saved backend run; do not automatically resend'
    throw
} finally {
    $summary.finished=[DateTimeOffset]::UtcNow.ToString('o')
    $summary.durationSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,1)
    Save-Record 'job.json' $summary
    Get-ChildItem -LiteralPath $folder -File | Where-Object {$_.Name -ne 'answer-hashes.csv'} |
        Get-FileHash -Algorithm SHA256 | Export-Csv -LiteralPath (Join-Path $folder 'answer-hashes.csv') -NoTypeInformation
    Write-Host "Benchmark run UUID: $runId"
    Write-Host "Saved benchmark records: $folder"
    Write-Host 'Proposal-only job. No experiment, repair, private grading, approval or patch application was requested.'
}
