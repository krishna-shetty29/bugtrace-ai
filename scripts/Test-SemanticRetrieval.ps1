$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Root .env file was not found.' }
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*(DB_URL|POSTGRES_USER|POSTGRES_PASSWORD)\s*=(.*)$') {
        $name = $Matches[1]; $value = $Matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) { $value = $value.Substring(1, $value.Length - 2) }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}
foreach ($name in @('DB_URL', 'POSTGRES_USER', 'POSTGRES_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { throw "Missing $name in the root .env" }
}
$env:RUN_SEMANTIC_PG_TEST = 'true'
Set-Location -LiteralPath (Join-Path $projectRoot 'backend')
& .\mvnw.cmd '-Dtest=SemanticChunksTest,HybridRankingTest,OllamaEmbeddingsTest,SemanticRetrievalPostgresTest' test
if ($LASTEXITCODE -ne 0) { throw 'Semantic retrieval checks failed. Read the first test failure above.' }
Write-Host 'Semantic retrieval checks passed, including the isolated PostgreSQL/pgvector integration test.'
