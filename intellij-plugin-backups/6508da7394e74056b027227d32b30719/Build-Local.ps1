param([string]$IdeHome='C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3',[switch]$SkipChecks)
$ErrorActionPreference='Stop'
$IdeHome=(Resolve-Path -LiteralPath $IdeHome).Path
$product=Get-Content -Raw -LiteralPath (Join-Path $IdeHome 'product-info.json') | ConvertFrom-Json
if($product.version -ne '2026.2.3' -or $product.buildNumber -ne '262.10968.63'){throw 'This local build is validated against IntelliJ IDEA 2026.2.3 build 262.10968.63. Review compatibility before changing the target.'}
$java=Join-Path $IdeHome 'jbr\bin\java.exe'
$compiler=Join-Path $IdeHome 'plugins\Kotlin\kotlinc\lib'
if(-not(Test-Path -LiteralPath (Join-Path $compiler 'kotlin-compiler.jar'))){throw 'Installed IDE Kotlin compiler was not found.'}
$build=Join-Path $PSScriptRoot ('build\local-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $build -Force | Out-Null
$libraries=@(Get-ChildItem -LiteralPath (Join-Path $IdeHome 'lib') -Recurse -Filter '*.jar' -File | Select-Object -ExpandProperty FullName)
$libraries+=@(Get-ChildItem -LiteralPath $compiler -Filter '*.jar' -File | Select-Object -ExpandProperty FullName)
$classpath=$libraries -join ';'
$utf8=New-Object Text.UTF8Encoding($false)
function Compile-Kotlin([string[]]$Sources,[string]$Output,[string]$ExtraClasspath='') {
    $argsFile=Join-Path $build (([guid]::NewGuid().ToString('N'))+'.args')
    $cp=$classpath
    if($ExtraClasspath){$cp=$cp+';'+$ExtraClasspath}
    $arguments=@('-jvm-target','25','-no-stdlib','-no-reflect','-classpath',('"'+$cp.Replace('\','/')+'"'),'-d',('"'+$Output.Replace('\','/')+'"'))
    foreach($source in $Sources){$arguments+=('"'+$source.Replace('\','/')+'"')}
    [IO.File]::WriteAllLines($argsFile,$arguments,$utf8)
    & $java '-Xmx1024m' '-cp' (Join-Path $compiler '*') 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' "@$argsFile"
    if($LASTEXITCODE -ne 0){throw 'Kotlin compilation failed. Keep the printed diagnostics.'}
}
$main=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\kotlin') -Recurse -Filter '*.kt' -File | Select-Object -ExpandProperty FullName)
$jar=Join-Path $build 'bugtrace-ai-intellij-0.1.0.jar'
Compile-Kotlin $main $jar
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$resourceRoot=Join-Path $PSScriptRoot 'src\main\resources'
$archive=[IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Update)
try {foreach($file in Get-ChildItem -LiteralPath $resourceRoot -Recurse -File){
    $relative=$file.FullName.Substring($resourceRoot.Length).TrimStart('\','/').Replace('\','/')
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$relative)
}} finally {$archive.Dispose()}
if(-not $SkipChecks) {
    $tests=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\kotlin') -Recurse -Filter '*.kt' -File | Select-Object -ExpandProperty FullName)
    $testJar=Join-Path $build 'core-checks.jar'
    Compile-Kotlin $tests $testJar $jar
    # Java's argument-file syntax avoids Windows command-length limits.
    $runArgs=Join-Path $build 'run-checks.args'
    [IO.File]::WriteAllLines($runArgs,@('-cp',('"'+($classpath+';'+$jar+';'+$testJar).Replace('\','/')+'"'),'com.bugtrace.idea.CoreChecksKt',('"'+$build.Replace('\','/')+'"')),$utf8)
    & $java "@$runArgs"
    if($LASTEXITCODE -ne 0){throw 'Plugin core checks failed.'}
}
$staging=Join-Path $build 'distribution\bugtrace-ai-intellij\lib'
New-Item -ItemType Directory -Path $staging -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination $staging
$zip=Join-Path $build 'bugtrace-ai-intellij-0.1.0.zip'
[IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $build 'distribution'),$zip)
$manifest=[ordered]@{pluginVersion='0.1.0';ideVersion=$product.version;ideBuild=$product.buildNumber;javaTarget=25;checksExecuted=(-not $SkipChecks);builtAt=[DateTimeOffset]::UtcNow.ToString('o');archiveSha256=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $build 'build-record.json') -Encoding UTF8
Write-Host "Plugin ZIP: $zip"
Write-Host "Build record: $(Join-Path $build 'build-record.json')"
Write-Host 'Compilation/core checks do not establish real IDE, Password Safe or backend integration acceptance. Follow the guide.'
