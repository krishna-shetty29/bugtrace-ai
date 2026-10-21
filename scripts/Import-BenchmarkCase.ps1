param([ValidateSet('case-001','case-002','case-003','case-004','case-005','case-006')][string]$CaseId = 'case-001')
. (Join-Path $PSScriptRoot 'Use-BugTraceAccess.ps1')

$ErrorActionPreference = 'Stop'
$backend = 'http://127.0.0.1:8080'
$registration = Invoke-RestMethod -Method Post -Uri "$backend/api/benchmark-registrations" -ContentType 'application/json' -Body (@{caseId=$CaseId} | ConvertTo-Json) -ErrorAction Stop
$info = $registration.registration
$caseFile = Join-Path (Join-Path $info.repository $info.modulePath) 'case.json'
$case = Get-Content -Raw -LiteralPath $caseFile | ConvertFrom-Json
$projects = Invoke-RestMethod -Uri "$backend/api/projects" -ErrorAction Stop
$projects = @($projects)
if ($projects.Count -ne 1) { throw 'Sign in with a personal key bound to one project before importing a benchmark case.' }
$project = $projects[0]
$request = @{projectId=$project.id;repositoryUrl=$info.repository;commitSha=$info.releaseCommit;title="Benchmark $CaseId";bugDescription=$case.bugReport;expectedBehavior=$case.expectedBehavior;observedBehavior='Public report only. No experiment has been executed in this investigation.'}
$investigation = Invoke-RestMethod -Method Post -Uri "$backend/api/investigations" -ContentType 'application/json' -Body ($request | ConvertTo-Json) -ErrorAction Stop
$imported = Invoke-RestMethod -Method Post -Uri "$backend/api/benchmark-registrations/$($registration.registrationId)/snapshots" -ContentType 'application/json' -Body (@{investigationId=$investigation.id} | ConvertTo-Json) -ErrorAction Stop
$result = [ordered]@{registrationId=$registration.registrationId;projectId=$project.id;investigationId=$investigation.id;import=$imported;browserUrl="http://127.0.0.1:5173/#/investigations/$($investigation.id)"}
$result | ConvertTo-Json -Depth 12
$recordDirectory = Join-Path $env:USERPROFILE 'benchmark-measurements\platform-imports'
New-Item -ItemType Directory -Path $recordDirectory -Force | Out-Null
$recordPath = Join-Path $recordDirectory "$($investigation.id).json"
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $recordPath -Encoding UTF8
"Import record saved: $recordPath"
"Registration UUID: $($registration.registrationId)"
"Investigation UUID: $($investigation.id)"
