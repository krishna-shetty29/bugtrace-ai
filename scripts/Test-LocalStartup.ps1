param()
. (Join-Path $PSScriptRoot 'LocalStartup.Common.ps1')
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('bugtrace-startup-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$count=0
function Check([string]$Name,[scriptblock]$Body){& $Body;$script:count++;Write-Host "PASS: $Name"}
function Rejected([scriptblock]$Body){$failed=$false;try{& $Body | Out-Null}catch{$failed=$true};if(-not $failed){throw 'Expected rejection was not observed.'}}
function Config([string]$Text){[IO.File]::WriteAllText((Join-Path $testRoot '.env'),$Text)}
$valid="POSTGRES_DB=bugtrace`nPOSTGRES_USER=bugtrace_dev`nPOSTGRES_PASSWORD='fake_test_only'`nDB_URL=jdbc:postgresql://127.0.0.1:5432/bugtrace`n"
Check 'missing .env rejected' {Rejected {Read-LocalConfiguration $testRoot}}
Config $valid
Check 'local configuration is parsed without printing values' {$v=Read-LocalConfiguration $testRoot;if($v.Count -ne 4 -or $v.POSTGRES_DB -ne 'bugtrace'){throw 'Unexpected configuration'}}
Check 'duplicate required key rejected' {Config ($valid+"POSTGRES_DB=other`n");Rejected {Read-LocalConfiguration $testRoot}}
Check 'placeholder password rejected' {Config ($valid.Replace("'fake_test_only'",'replace_with_your_own_local_password'));Rejected {Read-LocalConfiguration $testRoot}}
Check 'mismatched database name rejected' {Config ($valid.Replace('5432/bugtrace','5432/other'));Rejected {Read-LocalConfiguration $testRoot}}
Check 'remote database rejected by local launcher' {Config ($valid.Replace('127.0.0.1','example.invalid'));Rejected {Read-LocalConfiguration $testRoot}}
Check 'wrong database port rejected' {Config ($valid.Replace(':5432/',':5433/'));Rejected {Read-LocalConfiguration $testRoot}}
Check 'missing required entry rejected' {Config ($valid.Replace("POSTGRES_USER=bugtrace_dev`n",''));Rejected {Read-LocalConfiguration $testRoot}}
Check 'unquoted interpolation rejected' {Config ($valid.Replace("'fake_test_only'",'fake$VARIABLE'));Rejected {Read-LocalConfiguration $testRoot}}
Check 'single-quoted literal dollar sign preserved' {Config ($valid.Replace("'fake_test_only'","'fake`$VARIABLE'"));$v=Read-LocalConfiguration $testRoot;if($v.POSTGRES_PASSWORD -ne 'fake$VARIABLE'){throw 'Literal changed'}}
Check 'unknown provider fields do not leak into Compose overrides' {Config ($valid+"BUGTRACE_AI_PROVIDER=mock`n");$v=Read-LocalConfiguration $testRoot;if($v.Count -ne 4){throw 'Unexpected environment override'}}
Check 'empty values rejected' {Config ($valid.Replace("'fake_test_only'",''));Rejected {Read-LocalConfiguration $testRoot}}
Check 'invalid identifiers rejected' {Config ($valid.Replace('POSTGRES_USER=bugtrace_dev','POSTGRES_USER=bad user'));Rejected {Read-LocalConfiguration $testRoot}}
Check 'inline comments rejected before Compose and backend disagree' {Config ($valid.Replace("'fake_test_only'",'fake_test_only # inline'));Rejected {Read-LocalConfiguration $testRoot}}
Check 'double-quoted escape sequences rejected before interpretation differs' {Config ($valid.Replace("'fake_test_only'",'"fake\nvalue"'));Rejected {Read-LocalConfiguration $testRoot}}
Config $valid
$prior=$env:POSTGRES_PASSWORD
function docker.exe {
    $script:receivedArguments=@($args)
    if($env:POSTGRES_PASSWORD -ne 'fake_test_only'){throw 'Expected scoped test password'}
    $global:LASTEXITCODE=0
}
try {
    Check 'Compose uses explicit root, env file, project name and config path' {
        Invoke-LocalCompose $testRoot @('config','--quiet')
        if($script:receivedArguments[0] -ne 'compose' -or $script:receivedArguments -notcontains 'bugtrace-ai' -or $script:receivedArguments -notcontains (Join-Path $testRoot '.env') -or $script:receivedArguments -notcontains '--quiet'){throw 'Wrong Compose argument binding'}
    }
    Check 'successful Compose call restores parent secret environment' {if($env:POSTGRES_PASSWORD -cne $prior){throw 'Parent environment changed'}}
    function docker.exe {$global:LASTEXITCODE=1}
    Check 'failed Compose call is a failure and restores environment' {
        Rejected {Invoke-LocalCompose $testRoot @('config','--quiet')}
        if($env:POSTGRES_PASSWORD -cne $prior){throw 'Failed call leaked environment change'}
    }
} finally {Remove-Item Function:\docker.exe -ErrorAction SilentlyContinue}
Check 'startup rejects an incomplete application checkout' {Rejected {Resolve-LocalProject $testRoot}}
Write-Host "Local startup checks passed: $count"
Write-Host 'These checks use fake values and a mocked Docker command. No containers, application processes, real credentials or database contents were changed.'
Write-Host "Disposable test files retained at: $testRoot"
