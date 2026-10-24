param([switch]$Install, [string]$Device='', [int]$AdbPort=5037)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$bt=Join-Path $sdk 'build-tools/35.0.0'
$android=Join-Path $sdk 'platforms/android-35/android.jar'
function Run([string]$exe,[string[]]$Arguments) {
    & $exe @Arguments
    if($LASTEXITCODE -ne 0){ throw "$exe failed ($LASTEXITCODE)" }
}
$aar='deps/libvlc-all-3.6.5.aar'
if(!(Test-Path $aar)){throw 'Missing LibVLC AAR. See README for the official Maven URL.'}
$expected='6A15A1F7ACD0738A31ACCE0C7E5EB6F5F340B62E6B64D8F83DF547DA56A44B47'
if((Get-FileHash $aar -Algorithm SHA256).Hash -ne $expected){throw 'LibVLC dependency checksum does not match the reviewed build.'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(!(Test-Path 'deps/libvlc/classes.jar')) {
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $aar).Path,(Join-Path $PSScriptRoot 'deps/libvlc'))
}
New-Item -ItemType Directory -Force -Path build/classes,build/generated,build/dex,build/package/lib,dist | Out-Null
Run "$bt/aapt.exe" @('package','-f','--auto-add-overlay','-M','AndroidManifest.xml','-S','res','-S','deps/libvlc/res','-A','assets','-I',$android,'-J','build/generated','--extra-packages','org.videolan','-F','build/unsigned.apk')
$sources=@(Get-ChildItem src,build/generated -Filter '*.java' -Recurse | ForEach-Object FullName)
Run javac (@('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',$android,'-classpath','deps/libvlc/classes.jar','-d','build/classes')+$sources)
$classes=@(Get-ChildItem build/classes -Filter '*.class' -Recurse | ForEach-Object FullName)
Run "$bt/d8.bat" (@('--min-api','21','--lib',$android,'--output','build/dex','deps/libvlc/classes.jar')+$classes)
Copy-Item -LiteralPath build/dex/classes.dex -Destination build/package/classes.dex -Force
foreach($abi in @('arm64-v8a','armeabi-v7a')) {
    Copy-Item -LiteralPath "deps/libvlc/jni/$abi" -Destination build/package/lib -Recurse -Force
}
Push-Location build/package
try {
    $files=@(Get-ChildItem -File -Recurse | ForEach-Object { [IO.Path]::GetRelativePath((Get-Location).Path,$_.FullName).Replace('\','/') })
    Run "$bt/aapt.exe" (@('add','../unsigned.apk')+$files)
} finally {Pop-Location}
Run "$bt/zipalign.exe" @('-f','4','build/unsigned.apk','build/aligned.apk')
if(!(Test-Path player-local.jks)) {
    Run keytool @('-genkeypair','-keystore','player-local.jks','-storepass','simplelive-local','-keypass','simplelive-local','-alias','simplelive','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Simple Live TV Local Build')
}
Run "$bt/apksigner.bat" @('sign','--ks','player-local.jks','--ks-pass','pass:simplelive-local','--key-pass','pass:simplelive-local','--min-sdk-version','21','--v1-signing-enabled','true','--out','dist/QianqiuTV-1.3.0.apk','build/aligned.apk')
Run "$bt/apksigner.bat" @('verify','--verbose','dist/QianqiuTV-1.3.0.apk')
Run "$bt/aapt.exe" @('dump','badging','dist/QianqiuTV-1.3.0.apk')
if($Install) {
    if([string]::IsNullOrWhiteSpace($Device)){throw 'Specify your authorized Android device explicitly with -Device.'}
    Run adb @('-H','127.0.0.1','-P',"$AdbPort",'-s',$Device,'install','-r','dist/QianqiuTV-1.3.0.apk')
    # Installation does not start the app or wake the display.
}
Write-Output 'Built dist/QianqiuTV-1.3.0.apk'
