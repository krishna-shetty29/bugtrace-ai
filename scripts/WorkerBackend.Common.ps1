# Operator-selected worker origin. No credential values are logged.
function Resolve-WorkerBackendOrigin([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -cne $Value.Trim() -or $Value.Contains('\')) { throw 'Invalid worker backend origin.' }
    # Restrict syntax before .NET URI normalization can erase paths or escapes.
    if ($Value -notmatch '^(?i:https?|https)://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/?$') { throw 'Use an origin without credentials, path, query or fragment.' }
    $uri=[uri]$Value
    if (-not $uri.IsAbsoluteUri -or -not $uri.Host -or $uri.Port -lt 1 -or $uri.Port -gt 65535) { throw 'Invalid worker backend origin.' }
    if ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $uri.Host -in @('localhost','127.0.0.1'))) { throw 'Remote worker backends require HTTPS.' }
    return $uri.GetLeftPart([System.UriPartial]::Authority)
}
function Invoke-WorkerRegistrationPost([string]$Origin,[string]$Path) {
    $originChecked=Resolve-WorkerBackendOrigin $Origin
    if ($Path -notmatch '^/api/benchmark-registrations/[0-9a-f-]{36}/(?:snapshots/[0-9a-f-]{36}/)?verify$') { throw 'Invalid registration API path.' }
    if ($env:WORKER_ACCESS_TOKEN -notmatch '^[A-Za-z0-9_-]{43}$') { throw 'Load the snapshot-bound worker key first.' }
    try {
        Microsoft.PowerShell.Utility\Invoke-RestMethod -Method Post -Uri ($originChecked+$Path) -Headers @{Authorization=('Bearer '+$env:WORKER_ACCESS_TOKEN)} -ContentType 'application/json' -Body '{}' -MaximumRedirection 0 -TimeoutSec 30 -ErrorAction Stop
    } catch { throw 'Worker registration request failed. Check backend address, HTTPS certificate, credential and registration. Redirects are not followed.' }
}
