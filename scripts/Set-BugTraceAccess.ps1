param([ValidateSet('Human','Worker')][string]$Kind='Human')
$ErrorActionPreference='Stop'
$secure = Read-Host "Paste the $Kind access key (input is hidden)" -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
try {
    $value = $value.Trim()
    if ($value -notmatch '^[A-Za-z0-9_-]{43}$') { throw 'Expected the complete generated access key.' }
    $name = if ($Kind -eq 'Worker') { 'WORKER_ACCESS_TOKEN' } else { 'BUGTRACE_ACCESS_KEY' }
    [Environment]::SetEnvironmentVariable($name,$value,'Process')
    Write-Host "$Kind key loaded into this terminal and its child processes. The key was not printed or saved."
} finally { $value=$null; $secure.Dispose() }
