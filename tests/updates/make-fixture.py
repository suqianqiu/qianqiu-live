import sys,zipfile
from pathlib import Path
d=Path(__file__).resolve().parent/'fixtures'
with zipfile.ZipFile(d/'manifest.apk') as compiled:
    manifest=compiled.read('AndroidManifest.xml')
with zipfile.ZipFile(sys.argv[1]) as source,zipfile.ZipFile(d/'raw.apk','w',compression=zipfile.ZIP_DEFLATED) as target:
    for info in source.infolist():
        if not info.filename.startswith('META-INF/'):
            target.writestr(info,manifest if info.filename=='AndroidManifest.xml' else source.read(info))
