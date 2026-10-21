param([string]$ProjectRoot)
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
. (Join-Path $PSScriptRoot 'LocalStartup.Common.ps1')
$root=Resolve-LocalProject $ProjectRoot
Invoke-LocalCompose $root @('ps','-a')
Write-Host ('Backend health: '+(Get-LocalHealth 'http://127.0.0.1:8080/actuator/health'))
Write-Host ('Worker health: '+(Get-LocalHealth 'http://127.0.0.1:8083/actuator/health'))
try {
    $page=Microsoft.PowerShell.Utility\Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:5173' -TimeoutSec 5 -MaximumRedirection 0
    Write-Host ('Frontend HTTP: '+$page.StatusCode)
} catch {Write-Host 'Frontend HTTP: UNAVAILABLE'}
Write-Host 'Health does not prove worker registration, job success, authentication acceptance or model readiness. Confirm those in their saved records.'
