"""Local S3 and real Prometheus -> Alertmanager -> authenticated receiver; no Internet recipients."""
import configparser
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

root=pathlib.Path(sys.argv[1]);receiver_port,s3_port,prometheus_port=map(int,sys.argv[2:5])
sys.path.insert(0,str(pathlib.Path(__file__).parent))
from backup_store import S3Store,metrics

def wait(check,name,timeout=120):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            result=check()
            if result:return result
        except (urllib.error.URLError,OSError,ValueError):pass
        time.sleep(0.5)
    raise AssertionError('Timed out: '+name)
def http(path,body=None,token=None):
    headers={}
    if token:headers['Authorization']='Bearer '+token
    request=urllib.request.Request(f'http://127.0.0.1:{receiver_port}'+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    return json.load(urllib.request.urlopen(request,timeout=5))
def event(name,status):
    return next((item for item in http('/state')['events'] if item['status']==status and any(alert['labels']['alertname']==name for alert in item['alerts'])),None)
def metric(expression):
    import urllib.parse
    url=f'http://127.0.0.1:{prometheus_port}/api/v1/query?'+urllib.parse.urlencode({'query':expression})
    return json.load(urllib.request.urlopen(url,timeout=5))['data']['result']
def run(*command,check=True):
    result=subprocess.run(command,capture_output=True,text=True)
    if check and result.returncode:raise AssertionError('Fixture command failed; secret-bearing output omitted')
    return result

credentials=json.loads((root/'s3-credentials.json').read_text())
parser=configparser.ConfigParser(interpolation=None)
parser['offsite']={'type':'s3','provider':'Other','env_auth':'false','region':'garage','access_key_id':credentials['id'],'secret_access_key':credentials['secret'],'endpoint':f'http://127.0.0.1:{s3_port}'}
config=root/'rclone.conf'
with config.open('w') as output:parser.write(output)
config.chmod(0o600)
# The bucket and scoped key were created by the fixture administrator, outside the backup code.
wait(lambda:run('rclone','--config',str(config),'lsf','offsite:finstep-fixture',check=False).returncode==0,'S3 fixture')
remote='offsite:finstep-fixture/backups'
run('openssl','req','-x509','-newkey','rsa:3072','-nodes','-keyout',str(root/'recipient.key'),'-out',str(root/'recipient.crt'),'-days','2','-subj','/CN=Offsite-Fixture')
plain=root/'fixture.txt';plain.write_text('Synthetic encrypted recovery fixture; no real user data.')
bundle=root/'fixture.cms'
run('openssl','cms','-encrypt','-binary','-stream','-aes-256-gcm','-outform','DER','-recip',str(root/'recipient.crt'),'-in',str(plain),'-out',str(bundle))
cli=['python3','scripts/backup_store.py','--config',str(config),'--remote',remote,'--local-test']
uploaded=json.loads(run(*cli,'upload',str(bundle),'--metrics-file',str(root/'metrics/backup.prom')).stdout)
backup_id=uploaded['backupId'];download=root/'download.cms'
run(*cli,'fetch',backup_id,str(download))
assert download.read_bytes()==bundle.read_bytes()
restored=root/'verified.txt'
run('openssl','cms','-decrypt','-binary','-inform','DER','-in',str(download),'-recip',str(root/'recipient.crt'),'-inkey',str(root/'recipient.key'),'-out',str(restored))
assert restored.read_bytes()==plain.read_bytes()
# A fetched file is never overwritten, including when it belongs to another user workflow.
assert run(*cli,'fetch',backup_id,str(download),check=False).returncode!=0
# A remote object altered after publication fails checksum verification without leaving an output.
corrupt=root/'corrupt.cms';data=bytearray(bundle.read_bytes());data[len(data)//2]^=1;corrupt.write_bytes(data)
store=S3Store(config,remote,True);store.run('copyto',corrupt,store.key(backup_id,'data.cms'))
rejected=root/'rejected.cms';assert run(*cli,'fetch',backup_id,str(rejected),check=False).returncode!=0;assert not rejected.exists()
# Data without a receipt is not a backup a recovery operator may consume.
import uuid
orphan=str(uuid.uuid4());store.run('copyto',bundle,store.key(orphan,'data.cms'))
assert run(*cli,'fetch',orphan,str(root/'orphan.cms'),check=False).returncode!=0
print('Verified encrypted S3 roundtrip, existing-file protection, corruption and incomplete-publication refusal.',flush=True)
# Verify real retry delivery: the local receiver deliberately rejects its first authenticated POST.
firing=wait(lambda:event('FinstepDeliveryCanary','firing'),'canary delivery')
state=http('/state');assert state['attempts']>=2
secret=(root/'alert-webhook-token').read_text().strip()
assert secret not in json.dumps(state)
try:http('/alerts',{'test':'unauthorized'})
except urllib.error.HTTPError as response:assert response.code==401
else:raise AssertionError('Webhook accepted a request without its Bearer token')
http('/control',{'canary':False},secret)
wait(lambda:event('FinstepDeliveryCanary','resolved'),'canary resolution')
# A real failed S3 upload updates status but cannot reset the previous verified-success timestamp.
old_success=next(line for line in (root/'metrics/backup.prom').read_text().splitlines() if 'last_success_timestamp' in line)
parser['offsite']['secret_access_key']='incorrect-fixture-secret';bad=root/'bad-rclone.conf'
with bad.open('w') as output:parser.write(output)
bad.chmod(0o600)
failed=run('python3','scripts/backup_store.py','--config',str(bad),'--remote',remote,'--local-test','upload',str(bundle),'--metrics-file',str(root/'metrics/backup.prom'),check=False)
assert failed.returncode!=0 and credentials['secret'] not in failed.stdout+failed.stderr
assert old_success in (root/'metrics/backup.prom').read_text()
assert 'last_attempt_success 0' in (root/'metrics/backup.prom').read_text()
wait(lambda:metric('finstep_backup_offsite_last_attempt_success == 0'),'failed backup metric visible',30)
print('Verified authenticated retry/resolution and failed-backup metric; waiting for the production backup-failure rule to fire.',flush=True)
wait(lambda:event('FinstepOffsiteBackupFailed','firing'),'backup failure notification')
run(*cli,'upload',str(bundle),'--metrics-file',str(root/'metrics/backup.prom'))
wait(lambda:event('FinstepOffsiteBackupFailed','resolved'),'backup recovery notification')
print(json.dumps({'s3Roundtrip':'verified','corruption':'refused','partialPublication':'refused','alertAuthentication':'verified','retry':'verified','firingAndResolved':'verified'}))
