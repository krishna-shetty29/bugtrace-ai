$ErrorActionPreference = 'Stop'

function Resolve-LocalProject([string]$Root) {
    $resolved = (Resolve-Path -LiteralPath $Root).Path
    foreach($relative in @('infra\compose.yaml','backend\mvnw.cmd','worker\mvnw.cmd','frontend\package.json','scripts\Start-ManualBackend.ps1','scripts\Start-BenchmarkWorker.ps1','scripts\Start-ManualWorker.ps1','scripts\Set-BugTraceAccess.ps1')) {
        if(-not(Test-Path -LiteralPath (Join-Path $resolved $relative) -PathType Leaf)){throw "Missing required application file: $relative"}
    }
    return $resolved
}

function Read-LocalConfiguration([string]$Root) {
    $path = Join-Path $Root '.env'
    if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw 'Root .env is missing. Follow docs/local-startup.md. Existing installations must preserve their current database credentials.'}
    $values = @{}
    foreach($line in [IO.File]::ReadAllLines($path)) {
        $trimmed=$line.Trim()
        if(-not $trimmed -or $trimmed.StartsWith('#')){continue}
        $parts=$trimmed -split '=',2
        if($parts.Count -ne 2){throw 'Invalid .env line. Use NAME=value on one line; values are not printed.'}
        $name=$parts[0].Trim()
        if($name -notin @('POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','DB_URL')){continue}
        if($values.ContainsKey($name)){throw "Duplicate configuration entry: $name"}
        $value=$parts[1].Trim();$literal=$value.StartsWith("'") -and $value.EndsWith("'") -and $value.Length -ge 2
        $doubleQuoted=$value.StartsWith('"') -and $value.EndsWith('"') -and $value.Length -ge 2
        if(-not $literal -and -not $doubleQuoted -and $value -match '\s+#'){throw "Inline comments are unsupported for $name; put comments on their own line."}
        if($doubleQuoted -and $value.Contains('\')){throw "Escaped double-quoted values are unsupported for $name; use a simple literal single-quoted value."}
        if($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or $literal)){$value=$value.Substring(1,$value.Length-2)}
        if($value.Contains('$') -and -not $literal){throw "Use single quotes for a literal dollar sign in $name; Compose and direct startup must agree."}
        if([string]::IsNullOrWhiteSpace($value) -or $value -match '^replace_|^CHANGE_ME'){throw "Configure $name privately in .env. Placeholder values are rejected."}
        $values[$name]=$value
    }
    foreach($name in @('POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','DB_URL')){if(-not $values.ContainsKey($name)){throw "Missing required .env entry: $name"}}
    foreach($name in @('POSTGRES_DB','POSTGRES_USER')){if($values[$name] -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$'){throw "$name must be a simple local PostgreSQL identifier."}}
    if($values.DB_URL -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):5432/([A-Za-z_][A-Za-z0-9_]{0,62})$' -or $Matches[2] -ne $values.POSTGRES_DB){throw 'DB_URL must target localhost or 127.0.0.1, port 5432, and the same POSTGRES_DB.'}
    return $values
}

function Require-LocalCommand([string]$Name) {
    if(-not(Get-Command $Name -ErrorAction SilentlyContinue)){throw "Required tool not found: $Name. Install it and reopen PowerShell."}
}

function Assert-LocalJava {
    if(-not $env:JAVA_HOME -or -not(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))){throw 'Set JAVA_HOME to your JDK 21 directory in this terminal. See docs/local-startup.md.'}
    $version=(& (Join-Path $env:JAVA_HOME 'bin\java.exe') --version | Out-String)
    if($LASTEXITCODE -ne 0 -or $version -notmatch '(?m)^(openjdk|java) 21[. ]'){throw 'Use JDK 21 for backend/worker development. Set JAVA_HOME and put its bin first on PATH in this terminal.'}
}

function Assert-FreeLocalPort([int]$Port) {
    $client=New-Object Net.Sockets.TcpClient
    try {
        $pending=$client.BeginConnect('127.0.0.1',$Port,$null,$null)
        if($pending.AsyncWaitHandle.WaitOne(500)) {
            try {$client.EndConnect($pending)} catch {return}
            throw "Port $Port is already in use. Reuse the existing service or stop its own terminal before starting another."
        }
    } finally {$client.Dispose()}
}

function Get-LocalHealth([string]$Uri) {
    try {
        $result=Microsoft.PowerShell.Utility\Invoke-RestMethod -Uri $Uri -TimeoutSec 5 -MaximumRedirection 0
        if($result.status -eq 'UP'){return 'UP'}
        return 'NOT UP'
    } catch {return 'UNAVAILABLE'}
}

function Assert-BackendReady {
    if((Get-LocalHealth 'http://127.0.0.1:8080/actuator/health') -ne 'UP'){throw 'Backend health is not UP. Start PostgreSQL and backend, then inspect the backend terminal.'}
}

function Invoke-LocalCompose([string]$Root,[string[]]$ComposeArguments) {
    Require-LocalCommand 'docker.exe'
    $values=Read-LocalConfiguration $Root
    $previous=@{}
    foreach($name in $values.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$values[$name],'Process')}
    try {
        & docker.exe compose --project-name bugtrace-ai --project-directory $Root --env-file (Join-Path $Root '.env') -f (Join-Path $Root 'infra\compose.yaml') @ComposeArguments
        if($LASTEXITCODE -ne 0){throw 'Docker Compose failed. Check Docker Desktop, image availability, ports and configuration. Do not print or share expanded secret configuration.'}
    } finally {foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}
}
