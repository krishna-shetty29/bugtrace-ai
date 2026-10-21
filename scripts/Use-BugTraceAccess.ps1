# Dot-sourced by API scripts. Preserve all native parameters and add credentials
# only for the trusted loopback backend, never for another service or website.
$metadata = New-Object System.Management.Automation.CommandMetadata (Get-Command Microsoft.PowerShell.Utility\Invoke-RestMethod)
$proxy = [System.Management.Automation.ProxyCommand]::Create($metadata)
$needle = '$scriptCmd = {& $wrappedCmd @PSBoundParameters }'
if (-not $proxy.Contains($needle)) { throw 'Unsupported PowerShell REST proxy layout; no request was sent.' }
$inject = @'
if ($PSBoundParameters.ContainsKey('Uri')) {
    $target = [uri]$PSBoundParameters['Uri']
    if ($target.IsAbsoluteUri -and $target.Scheme -eq 'http' -and $target.Host -in @('127.0.0.1','localhost') -and $target.Port -eq 8080 -and $target.AbsolutePath.StartsWith('/api/')) {
        $key = $env:BUGTRACE_ACCESS_KEY
        if ([string]::IsNullOrWhiteSpace($key)) { $key = $env:WORKER_ACCESS_TOKEN }
        if (-not [string]::IsNullOrWhiteSpace($key)) {
            if ($key -notmatch '^[A-Za-z0-9_-]{43}$') { throw 'Invalid process access key. Load it using Set-BugTraceAccess.ps1.' }
            $headers = @{}
            if ($PSBoundParameters.ContainsKey('Headers')) { foreach ($entry in $PSBoundParameters['Headers'].GetEnumerator()) { $headers[$entry.Key]=$entry.Value } }
            if (-not $headers.ContainsKey('Authorization')) { $headers['Authorization']='Bearer '+$key }
            $PSBoundParameters['Headers']=$headers
            $PSBoundParameters['MaximumRedirection']=0
        }
    }
}
'@
$proxy = $proxy.Replace($needle,$inject + "`n        " + $needle)
Set-Item -Path Function:\Invoke-RestMethod -Value ([scriptblock]::Create($proxy))
