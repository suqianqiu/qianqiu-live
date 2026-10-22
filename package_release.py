from pathlib import Path
import hashlib, json, zipfile, re
root=Path(__file__).resolve().parent
version=re.search(r'android:versionName="([^"]+)"',(root/'AndroidManifest.xml').read_text('utf-8')).group(1)
out=root/'dist';out.mkdir(exist_ok=True)
slug=root.name
archive=out/(slug+'-'+version+'-source.zip')
excluded={'.git','build','dist','__pycache__'}
files=[]
for p in root.rglob('*'):
    rel=p.relative_to(root)
    if not p.is_file() or any(x in excluded for x in rel.parts):continue
    if p.suffix.lower() in ('.jks','.keystore','.p12','.pfx','.pyc','.log') or p.name.startswith('.env'):continue
    if rel.parts[0]=='deps' and len(rel.parts)>2:continue
    files.append(p)
for item in json.loads((root/'tools/dependencies.json').read_text('utf-8')):
    p=root/'deps'/item['file']
    assert p.exists() and hashlib.sha256(p.read_bytes()).hexdigest()==item['sha256'], 'Fetch dependencies first'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(files):z.write(p,Path(slug)/p.relative_to(root))
print(archive)
