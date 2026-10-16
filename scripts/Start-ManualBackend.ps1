$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

Get-Content -LiteralPath (Join-Path $projectRoot '.env') | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $parts = $line -split '=', 2
        if ($parts.Count -eq 2) {
            $name = $parts[0].Trim()
            $value = $parts[1].Trim()
            if ($value.Length -ge 2 -and (
                ($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'"))
            )) { $value = $value.Substring(1, $value.Length - 2) }
            if ($name -in @('DB_URL','POSTGRES_USER','POSTGRES_PASSWORD')) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
}
if ($env:DB_URL -notlike 'jdbc:postgresql://*' -or
    [string]::IsNullOrWhiteSpace($env:POSTGRES_USER) -or
    [string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) {
    throw 'Load valid DB_URL, POSTGRES_USER and POSTGRES_PASSWORD from the root .env.'
}
Set-Location -LiteralPath (Join-Path $projectRoot 'backend')
& .\mvnw.cmd spring-boot:run
if ($LASTEXITCODE -ne 0) { throw 'Backend stopped with an error. Read the startup output above.' }
