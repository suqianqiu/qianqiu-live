$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$app=(Resolve-Path '../..').Path
$sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$bt=Join-Path $sdk 'build-tools/35.0.0'
$android=Join-Path $sdk 'platforms/android-35/android.jar'
function Run($exe,$arguments){ & $exe @arguments; if($LASTEXITCODE -ne 0){throw "$exe failed"} }
New-Item -ItemType Directory -Force ui-classes,ui-dex,ui-package | Out-Null
Run "$bt/aapt.exe" @('package','-f','-M','AndroidManifest.xml','-I',$android,'-F','ui-unsigned.apk')
Run javac @('-encoding','UTF-8','--release','8','-classpath',"$android;$app/build/app-classes.jar;$app/deps/libvlc/classes.jar",'-d','ui-classes','UiChecks.java')
Run jar @('cf','ui-checks.jar','-C','ui-classes','.')
Run "$bt/d8.bat" @('--min-api','21','--lib',$android,'--classpath',"$app/build/app-classes.jar",'--classpath',"$app/deps/libvlc/classes.jar",'--output','ui-dex','ui-checks.jar')
Copy-Item -LiteralPath ui-dex/classes.dex -Destination ui-package/classes.dex -Force
Push-Location ui-package
try{Run "$bt/aapt.exe" @('add','../ui-unsigned.apk','classes.dex')}finally{Pop-Location}
Run "$bt/zipalign.exe" @('-f','4','ui-unsigned.apk','ui-aligned.apk')
Run "$bt/apksigner.bat" @('sign','--ks',"$app/player-local.jks",'--ks-pass','pass:simplelive-local','--key-pass','pass:simplelive-local','--out','ui-checks.apk','ui-aligned.apk')
