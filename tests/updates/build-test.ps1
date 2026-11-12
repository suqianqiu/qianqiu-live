param([switch]$TestsOnly)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$app=(Resolve-Path ../..).Path
$isAnime=([IO.File]::ReadAllText("$app/AndroidManifest.xml") -match 'package="com.qianq.kandongman"')
$current=if($isAnime){'1.6.7'}else{'1.3.4'}
$next=if($isAnime){'1.6.8'}else{'1.3.5'}
$code=if($isAnime){17}else{18}
$key=if($isAnime){'anime-local.jks'}else{'player-local.jks'}
$apk=if($isAnime){"$app/dist/KanDongman-$current.apk"}else{"$app/dist/QianqiuTV-$current.apk"}
$sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$bt=Join-Path $sdk 'build-tools/35.0.0'
$android=Join-Path $sdk 'platforms/android-35/android.jar'
function Run($exe,$arguments){& $exe @arguments;if($LASTEXITCODE -ne 0){throw "$exe failed"}}
New-Item -ItemType Directory -Force classes,dex,fixtures | Out-Null
Run javac @('-encoding','UTF-8','--release','8','-classpath',$android,'-d','classes','TestBase.java','UpdateChecks.java','UpdateUi.java','UpdateRemote.java')
Run jar @('cf','tests.jar','-C','classes','.')
Run "$bt/d8.bat" @('--min-api','21','--lib',$android,'--output','dex','tests.jar')
Run "$bt/aapt.exe" @('package','-f','-M','AndroidManifest.xml','-I',$android,'-F','unsigned.apk')
Copy-Item dex/classes.dex classes.dex -Force
Run "$bt/aapt.exe" @('add','unsigned.apk','classes.dex')
Run "$bt/zipalign.exe" @('-f','4','unsigned.apk','aligned.apk')
Run "$bt/apksigner.bat" @('sign','--ks',"$app/$key",'--ks-pass','pass:simplelive-local','--key-pass','pass:simplelive-local','--out','update-qa.apk','aligned.apk')
if($TestsOnly){exit 0}
$m=[IO.File]::ReadAllText("$app/AndroidManifest.xml").Replace("versionName=`"$current`"","versionName=`"$next`"")
$m=$m -replace 'android:versionCode="[0-9]+"',"android:versionCode=`"$code`""
[IO.File]::WriteAllText("$PSScriptRoot/fixtures/AndroidManifest.xml",$m)
Run "$bt/aapt.exe" @('package','-f','--auto-add-overlay','-M','fixtures/AndroidManifest.xml','-S',"$app/res",'-S',"$app/deps/libvlc/res",'-I',$android,'-F','fixtures/manifest.apk')
Run py @('-3.12','make-fixture.py',$apk)
Run "$bt/zipalign.exe" @('-f','4','fixtures/raw.apk','fixtures/aligned.apk')
Run "$bt/apksigner.bat" @('sign','--ks',"$app/$key",'--ks-pass','pass:simplelive-local','--key-pass','pass:simplelive-local','--out','fixtures/update-new.apk','fixtures/aligned.apk')
if(!(Test-Path fixtures/wrong.jks)){Run keytool @('-genkeypair','-keystore','fixtures/wrong.jks','-storepass','test-fixture','-keypass','test-fixture','-alias','wrong','-keyalg','RSA','-validity','30','-dname','CN=Update Test Wrong Signer')}
Run "$bt/apksigner.bat" @('sign','--ks','fixtures/wrong.jks','--ks-pass','pass:test-fixture','--key-pass','pass:test-fixture','--out','fixtures/update-wrong-signature.apk','fixtures/aligned.apk')
