param([string]$ProjectRoot,[switch]$WithOllama)
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
. (Join-Path $PSScriptRoot 'LocalStartup.Common.ps1')
$root=Resolve-LocalProject $ProjectRoot
$services=@('postgres');if($WithOllama){$services+='ollama'}
Invoke-LocalCompose $root (@('stop')+$services)
Write-Host 'Selected infrastructure stopped. Containers and named volumes are retained. Stop worker/backend/frontend first with Ctrl+C in their own terminals.'
