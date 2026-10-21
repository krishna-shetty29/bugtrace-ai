param([string]$BackendUrl='http://127.0.0.1:8080')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'WorkerBackend.Common.ps1')
$origin=Resolve-WorkerBackendOrigin $BackendUrl
if($env:BUGTRACE_ACCESS_KEY -notmatch '^[A-Za-z0-9_-]{43}$'){throw 'Load a Human project access key first using Set-BugTraceAccess.ps1 -Kind Human.'}
Microsoft.PowerShell.Utility\Invoke-RestMethod -Uri ($origin+'/api/workers/presence') -Method Get -Headers @{Authorization=('Bearer '+$env:BUGTRACE_ACCESS_KEY)} -MaximumRedirection 0 -TimeoutSec 15
