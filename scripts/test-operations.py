"""Real HTTPS/API/SMTP/backup/restore checks; all identities and secrets are disposable fixtures."""
import hashlib
import http.cookiejar
import json
import pathlib
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

state=pathlib.Path(sys.argv[1])
https_port,http_port,metrics_port,mail_port=map(int,sys.argv[2:6])
source,target=sys.argv[6:8]
origin=f'https://localhost:{https_port}'
context=ssl.create_default_context(cafile=str(state/'root.crt'))
class Client:
    def __init__(self):
        self.cookies=http.cookiejar.CookieJar()
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context),urllib.request.HTTPCookieProcessor(self.cookies))
    def request(self,path,method='GET',body=None,key=None):
        headers={'Content-Type':'application/json'}
        if method!='GET':
            _,csrf,_=self.request('/api/v1/auth/csrf')
            headers[csrf['headerName']]=csrf['token']
        if key: headers['Idempotency-Key']=key
        request=urllib.request.Request(origin+path,data=None if body is None else json.dumps(body).encode(),method=method,headers=headers)
        try:
            response=self.opener.open(request,timeout=10)
        except urllib.error.HTTPError as error:
            response=error
        data=response.read()
        try: value=json.loads(data) if data else None
        except json.JSONDecodeError: value=data.decode()
        return response.status,value,response.headers

def command(*args,input=None):
    return subprocess.check_output(args,input=input,stderr=subprocess.PIPE,text=True)
def sql(container,query):
    return command('docker','exec','-i',container,'sh','-ceu','exec psql -X -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',input=query)
def wait_until(check,description,seconds=90):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            value=check()
            if value: return value
        except (urllib.error.URLError,OSError,ValueError): pass
        time.sleep(1)
    raise AssertionError('Timed out: '+description)
def mail_token(email):
    base=f'http://127.0.0.1:{mail_port}'
    def lookup():
        listing=json.load(urllib.request.urlopen(base+'/api/v1/messages',timeout=5))
        for message in listing['messages']:
            if any(recipient['Address']==email for recipient in message['To']):
                body=json.load(urllib.request.urlopen(base+'/api/v1/message/'+message['ID'],timeout=5))
                import re
                return re.search(r'[A-Za-z0-9_-]{43}',body['Text']).group()
    return wait_until(lookup,'local fixture email',30)
def account():
    client=Client();email=f'ops-{uuid.uuid4()}@example.test';password='Operations-Fixture-Password-123!'
    assert client.request('/api/v1/auth/register','POST',{'email':email,'password':password,'fullName':'Operations Fixture'})[0]==202
    assert client.request('/api/v1/auth/verify-email','POST',{'token':mail_token(email)})[0]==204
    status,_,headers=client.request('/api/v1/auth/login','POST',{'email':email,'password':password})
    assert status==204
    cookie='; '.join(headers.get_all('Set-Cookie',[])).lower()
    assert 'secure' in cookie and 'httponly' in cookie and 'samesite=lax' in cookie
    profile=client.request('/api/v1/me')[1]
    return client,profile,password

def wallet(client,name,amount):
    status,data,_=client.request('/api/v1/wallets','POST',{'name':name,'type':'CASH','openingBalance':amount,'effectiveAt':'2025-01-01T00:00:00Z'})
    assert status==201
    return data

anonymous=Client()
wait_until(lambda: anonymous.request('/')[0]==200,'HTTPS frontend')
# The test trusts only the copied local CA, never disables verification or changes the OS trust store.
try:
    urllib.request.urlopen(origin,timeout=5)
except urllib.error.URLError as error:
    assert isinstance(error.reason,ssl.SSLCertVerificationError)
else: raise AssertionError('Self-signed rehearsal CA unexpectedly trusted by the default client')
wait_until(lambda: anonymous.request('/api/v1/me')[0]==401,'API authentication')
for path in ['/actuator','/actuator/prometheus','/actuator/health/readiness','/actuator/env']:
    assert anonymous.request(path)[0]==404
_,html,headers=anonymous.request('/')
import re
assets=re.findall(r'(?:src|href)="(/_next/static/[^" ]+)"',html)
assert assets, 'No standalone static assets found'
for asset in assets[:3]: assert anonymous.request(asset)[0]==200
assert anonymous.request('/auth/login')[0]==200
assert headers['Strict-Transport-Security']=='max-age=31536000'
assert headers['X-Content-Type-Options']=='nosniff'
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
try: urllib.request.build_opener(NoRedirect).open(f'http://localhost:{http_port}/',timeout=5)
except urllib.error.HTTPError as response:
    assert response.code==308 and response.headers['Location'].startswith('https://localhost/')
else: raise AssertionError('HTTP did not redirect to HTTPS')

keeper,keep_profile,keep_password=account()
removed,removed_profile,removed_password=account()
a=wallet(keeper,'Cash fixture','1000000');b=wallet(keeper,'Bank fixture','0')
wallet(removed,'Deleted account fixture','777')
expense=next(item for item in keeper.request('/api/v1/categories')[1] if item['type']=='EXPENSE')
income=next(item for item in keeper.request('/api/v1/categories')[1] if item['type']=='INCOME')
for kind,category,amount in [('EXPENSE',expense,'45000'),('INCOME',income,'200000')]:
    assert keeper.request('/api/v1/transactions','POST',{'walletId':a['id'],'categoryId':category['id'],'type':kind,'amount':amount,'occurredAt':'2026-01-02T00:00:00Z','note':'Encrypted backup fixture'},str(uuid.uuid4()))[0]==201
assert keeper.request('/api/v1/transfers','POST',{'fromWalletId':a['id'],'toWalletId':b['id'],'amount':'100000','occurredAt':'2026-01-02T00:00:00Z'},str(uuid.uuid4()))[0]==201
assert keeper.request('/api/v1/budgets/2026-01','PUT',{'items':[{'categoryId':expense['id'],'limitAmount':'100000'}]})[0]==200
for client in [keeper,removed]:
    assert client.request('/api/v1/feedback','POST',{'type':'OTHER','message':'Backup and restore fixture feedback'},str(uuid.uuid4()))[0]==201
# Incoming forged forwarded headers must be discarded by Caddy, rather than becoming a rate-limit identity.
request=urllib.request.Request(origin+'/api/v1/auth/login',data=b'{}',method='POST',headers={'X-Forwarded-For':'198.51.100.88'})
try: anonymous.opener.open(request,timeout=5)
except urllib.error.HTTPError as response: assert response.code==403
forged=hashlib.sha256(b'ip:198.51.100.88').hexdigest()
assert sql(source,f"SELECT count(*) FROM auth_rate_limits WHERE bucket_hash='{forged}';").strip()=='0'

backup=command('bash','scripts/backup-db.sh',source,str(state/'backups'),str(state/'recipient.crt')).strip()
assert pathlib.Path(backup).stat().st_mode & 0o077==0
assert b'Encrypted backup fixture' not in pathlib.Path(backup).read_bytes()
assert keeper.request('/api/v1/me','PATCH',{'fullName':'Current profile fixture'})[0]==200
assert keeper.request('/api/v1/me/password','POST',{'currentPassword':keep_password,'newPassword':'Current-Operations-Password-456!'})[0]==204
# A current disabled status must also survive the old data snapshot.
sql(source,f"UPDATE users SET status='DISABLED' WHERE id='{keep_profile['id']}';")
assert removed.request('/api/v1/me','DELETE',{'password':removed_password,'confirmation':'XÓA TÀI KHOẢN'})[0]==204
assert removed.request('/api/v1/me')[0]==401
# A post-backup account remains in the source but correctly cannot appear in the older restore.
newer,newer_profile,_=account()
# A corrupt authenticated envelope must fail before any target table is created.
corrupt=state/'corrupt.cms';data=bytearray(pathlib.Path(backup).read_bytes());data[len(data)//2]^=1;corrupt.write_bytes(data)
result=subprocess.run(['bash','scripts/restore-rehearsal.sh',source,target,str(corrupt),str(state/'recipient.crt'),str(state/'recipient.key')],capture_output=True,text=True)
assert result.returncode!=0
assert sql(target,"SELECT count(*) FROM pg_tables WHERE schemaname='public';").strip()=='0'
result=json.loads(command('bash','scripts/restore-rehearsal.sh',source,target,backup,str(state/'recipient.crt'),str(state/'recipient.key')))
assert result=={'removedAccounts':1,'survivingAccounts':1,'authenticationStateCleared':True}
assert sql(target,f"SELECT count(*) FROM users WHERE id='{removed_profile['id']}';").strip()=='0'
assert sql(target,f"SELECT count(*) FROM users WHERE id='{newer_profile['id']}';").strip()=='0'
assert sql(source,'SELECT count(*) FROM users;').strip()=='2'
assert sql(target,"SELECT count(*) FROM spring_session;").strip()=='0'
assert sql(target,"SELECT count(*) FROM identity_tokens;").strip()=='0'
assert sql(target,f"SELECT balance FROM wallet_balances WHERE wallet_id='{a['id']}';").strip()=='1055000'
assert sql(target,f"SELECT balance FROM wallet_balances WHERE wallet_id='{b['id']}';").strip()=='100000'
assert sql(target,'SELECT count(*) FROM feedback;').strip()=='1'
assert sql(target,'SELECT count(*) FROM budget_items;').strip()=='1'
assert sql(target,'SELECT auth_version FROM users;').strip()=='2'
assert sql(target,'SELECT status FROM users;').strip()=='DISABLED'
assert sql(target,'SELECT full_name FROM users;').strip()=='Current profile fixture'
assert sql(target,'SELECT password_hash FROM users;')==sql(source,f"SELECT password_hash FROM users WHERE id='{keep_profile['id']}';")
# A second restore into the now non-empty target must be refused without changing data.
again=subprocess.run(['bash','scripts/restore-rehearsal.sh',source,target,backup,str(state/'recipient.crt'),str(state/'recipient.key')],capture_output=True,text=True)
assert again.returncode!=0
assert sql(target,'SELECT count(*) FROM users;').strip()=='1'
# Prometheus must see both private readiness probes and the real application metrics endpoint.
def query(expression):
    import urllib.parse
    return json.load(urllib.request.urlopen(f'http://127.0.0.1:{metrics_port}/api/v1/query?'+urllib.parse.urlencode({'query':expression}),timeout=5))['data']['result']
wait_until(lambda: len(query('up{job="finstep-api"} == 1'))==1,'Prometheus API target')
wait_until(lambda: len(query('probe_success{job="finstep-readiness"} == 1'))==2,'readiness probes')
print(json.dumps({'https':'verified','secureSession':True,'forwardedHeaders':'not spoofable','encryptedRestore':'passed',
                  'deletedAccountKeptDeleted':True,'financialBalances':'verified','privateMetrics':'ready'}))
