"""Initialize only the disposable Garage S3 test service; never log generated keys."""
import json
import pathlib
import re
import subprocess
import sys
import time
root=pathlib.Path(sys.argv[1]);container=sys.argv[2]
if not re.fullmatch('[a-f0-9]{64}',container):raise SystemExit('Fixture container ID required')
label=subprocess.check_output(['docker','inspect','-f','{{index .Config.Labels "com.docker.compose.project"}}',container],text=True).strip()
if not label.startswith('finstep-reliability-'):raise SystemExit('Refusing to configure a non-fixture object store')
def garage(*args):
    result=subprocess.run(['docker','exec',container,'/garage',*args],capture_output=True,text=True)
    if result.returncode:raise RuntimeError('S3 fixture initialization failed; secret-bearing output omitted')
    return result.stdout
for attempt in range(60):
    try:
        identity=garage('node','id','-q').strip().split('@')[0]
        if re.fullmatch('[a-f0-9]{64}',identity):break
    except RuntimeError:pass
    time.sleep(0.5)
else:raise SystemExit('S3 fixture did not become ready')
garage('layout','assign','-z','fixture','-c','1G',identity)
garage('layout','apply','--version','1')
garage('bucket','create','finstep-fixture')
garage('key','create','fixture-key')
private=garage('key','info','--show-secret','fixture-key')
key=re.search(r'\bGK[a-f0-9]+\b',private)
secret=re.search(r'Secret key:\s*(\S+)',private,re.IGNORECASE)
if not key or not secret:raise SystemExit('Could not read the private fixture key')
garage('bucket','allow','--read','--write','--owner','finstep-fixture','--key','fixture-key')
(root/'s3-credentials.json').write_text(json.dumps({'id':key.group(),'secret':secret.group(1)}))
print('Disposable S3 bucket and scoped fixture key initialized; credentials omitted.')
