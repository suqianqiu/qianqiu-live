$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$app=Resolve-Path ../..
$sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$bt=Join-Path $sdk 'build-tools/35.0.0'
$android=Join-Path $sdk 'platforms/android-35/android.jar'
function Run($exe,$arguments){ & $exe @arguments; if($LASTEXITCODE -ne 0){throw "$exe failed"} }
New-Item -ItemType Directory -Force classes,dex,package | Out-Null
Run jar @('cf',"$app/build/app-classes.jar",'-C',"$app/build/classes",'.')
Run "$bt/aapt.exe" @('package','-f','-M','AndroidManifest.xml','-A','../navigation/fixture','-I',$android,'-F','unsigned.apk')
Run javac @('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',$android,'-classpath',"$app/build/app-classes.jar;$app/deps/libvlc/classes.jar",'-d','classes','InspectionTest.java','HeadlessInspectionTest.java')
Run jar @('cf','tests.jar','-C','classes','.')
Run "$bt/d8.bat" @('--min-api','21','--lib',$android,'--classpath',"$app/build/app-classes.jar",'--output','dex','tests.jar')
Copy-Item dex/classes.dex package/classes.dex -Force
Push-Location package
try{ Run "$bt/aapt.exe" @('add','../unsigned.apk','classes.dex') }finally{Pop-Location}
Run "$bt/zipalign.exe" @('-f','4','unsigned.apk','aligned.apk')
Run "$bt/apksigner.bat" @('sign','--ks',"$app/player-local.jks",'--ks-pass','pass:simplelive-local','--key-pass','pass:simplelive-local','--out','inspection-test.apk','aligned.apk')
