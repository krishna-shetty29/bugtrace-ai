$ErrorActionPreference='Stop'
$bytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($bytes) } finally { $random.Dispose() }
$env:BUGTRACE_BOOTSTRAP_SECRET = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
Set-Clipboard -Value $env:BUGTRACE_BOOTSTRAP_SECRET
Write-Host 'A temporary bootstrap secret is on your clipboard. Paste it only into the initial BugTrace setup form. Do not paste it into a chat or report.'
Write-Host 'Once access has been initialized, bootstrap is permanently rejected by the database state.'
try { & (Join-Path $PSScriptRoot 'Start-ManualBackend.ps1') }
finally { Remove-Item Env:\BUGTRACE_BOOTSTRAP_SECRET -ErrorAction SilentlyContinue }
