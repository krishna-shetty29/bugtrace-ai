param(
    [Parameter(Mandatory=$true)][ValidateSet('Infrastructure','Backend','Frontend','Worker')][string]$Component,
    [string]$ProjectRoot,
    [ValidateSet('Benchmark','Manual')][string]$WorkerMode='Benchmark',
    [string]$ImportRecord,
    [string]$WorkerId='laptop-worker-01',
    [string]$CacheSeed=(Join-Path $env:USERPROFILE 'benchmark-cache\artifacts'),
    [switch]$WithOllama,
    [switch]$CheckOnly
)
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
. (Join-Path $PSScriptRoot 'LocalStartup.Common.ps1')
$root=Resolve-LocalProject $ProjectRoot
if($WithOllama -and $Component -ne 'Infrastructure'){throw 'WithOllama applies only to Infrastructure.'}
switch($Component) {
    'Infrastructure' {
        Invoke-LocalCompose $root @('config','--quiet')
        if($CheckOnly){Write-Host 'Compose configuration passed; no containers started.';return}
        $services=@('postgres');if($WithOllama){$services+='ollama'}
        Invoke-LocalCompose $root (@('up','-d','--wait','--wait-timeout','120')+$services)
        Write-Host 'Infrastructure started. PostgreSQL readiness was checked. An Ollama container, if selected, still needs an installed generation model for live investigations.'
    }
    'Backend' {
        $null=Read-LocalConfiguration $root
        Assert-LocalJava
        Assert-FreeLocalPort 8080
        if($CheckOnly){Write-Host 'Backend configuration, Java and port checks passed. No backend started.';return}
        Write-Host 'Starting authenticated backend in this terminal. Flyway applies pending migrations; keep this window open.'
        & (Join-Path $root 'scripts\Start-ManualBackend.ps1')
    }
    'Frontend' {
        Require-LocalCommand 'node.exe';Require-LocalCommand 'npm.cmd'
        $nodeVersion=(& node.exe --version | Out-String).Trim().TrimStart('v')
        if($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^\d+\.\d+\.\d+$'){throw 'Could not determine Node.js version.'}
        $node=[version]$nodeVersion
        if(-not(($node.Major -eq 20 -and $node -ge [version]'20.19.0') -or $node -ge [version]'22.12.0')){throw 'Installed Vite requires Node 20.19.x or Node 22.12.0 and newer. Use a supported Node LTS release.'}
        Assert-FreeLocalPort 5173
        if(-not(Test-Path -LiteralPath (Join-Path $root 'frontend\node_modules\vite\bin\vite.js') -PathType Leaf)){throw 'Frontend dependencies are missing. Run npm.cmd ci in frontend, then retry.'}
        if($CheckOnly){Write-Host 'Frontend dependency and port checks passed. No frontend started.';return}
        Write-Host 'Starting frontend at http://127.0.0.1:5173. Keep this window open.'
        Push-Location -LiteralPath (Join-Path $root 'frontend')
        try {& npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort;if($LASTEXITCODE -ne 0){throw 'Frontend exited with an error.'}} finally {Pop-Location}
    }
    'Worker' {
        Assert-LocalJava
        Require-LocalCommand 'docker.exe'
        Assert-FreeLocalPort 8083
        if($WorkerId -notmatch '^[A-Za-z0-9_-]{1,64}$'){throw 'Invalid WorkerId.'}
        if($WorkerMode -eq 'Benchmark') {
            if(-not $ImportRecord -or -not(Test-Path -LiteralPath $ImportRecord -PathType Leaf)){throw 'Supply the downloaded import JSON file with -ImportRecord; a directory is not a file.'}
            $record=Get-Content -Raw -LiteralPath $ImportRecord | ConvertFrom-Json
            $null=[guid]::Parse($record.registrationId);$null=[guid]::Parse($record.investigationId)
            if(-not(Test-Path -LiteralPath $CacheSeed -PathType Container)){throw 'Artifact-only benchmark cache is missing. Follow Prepare-BenchmarkCache.ps1 instructions in the guide.'}
        }
        if($CheckOnly){Write-Host 'Worker local prerequisites passed. Worker credential, registration, image and runtime validation still occur at startup. No worker started.';return}
        Assert-BackendReady
        # Do not let a human key override the worker key in the existing API helper.
        $previousHuman=$env:BUGTRACE_ACCESS_KEY
        $previousWorker=$env:WORKER_ACCESS_TOKEN
        try {
            Remove-Item Env:\BUGTRACE_ACCESS_KEY -ErrorAction SilentlyContinue
            & (Join-Path $root 'scripts\Set-BugTraceAccess.ps1') -Kind Worker
            if($WorkerMode -eq 'Benchmark') {
                & (Join-Path $root 'scripts\Start-BenchmarkWorker.ps1') -ImportRecord $ImportRecord -WorkerId $WorkerId -CacheSeed $CacheSeed
            } else {
                & (Join-Path $root 'scripts\Start-ManualWorker.ps1') -WorkerId $WorkerId
            }
        } finally {
            [Environment]::SetEnvironmentVariable('BUGTRACE_ACCESS_KEY',$previousHuman,'Process')
            [Environment]::SetEnvironmentVariable('WORKER_ACCESS_TOKEN',$previousWorker,'Process')
        }
    }
}
