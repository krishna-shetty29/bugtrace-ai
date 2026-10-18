$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
foreach($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
    if($line -match '^\s*(DB_URL|POSTGRES_USER|POSTGRES_PASSWORD)\s*=\s*(.*)\s*$') {
        $name=$matches[1];$value=$matches[2].Trim();if(($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))){$value=$value.Substring(1,$value.Length-2)}
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
}
$env:RUN_PATCH_REVIEW_PG_TEST='true'
Push-Location (Join-Path $root 'backend')
try { & .\mvnw.cmd '-Dtest=PatchReviewPostgresTest' test; if($LASTEXITCODE -ne 0){throw 'PostgreSQL migration check failed. Read the actual Maven output.'} }
finally {Pop-Location;Remove-Item Env:\RUN_PATCH_REVIEW_PG_TEST -ErrorAction SilentlyContinue}
