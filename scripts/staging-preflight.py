#!/usr/bin/env python3
"""Validate a dotenv configuration without executing it or printing secret values."""
import argparse
import json
import os
import urllib.parse
import pathlib
import ipaddress
import re

parser=argparse.ArgumentParser()
parser.add_argument('env_file',type=pathlib.Path)
parser.add_argument('--local-test',action='store_true')
parser.add_argument('--with-alerting',action='store_true')
parser.add_argument('--with-offsite',action='store_true')
args=parser.parse_args()
values={}
for raw in args.env_file.read_text().splitlines():
    line=raw.strip()
    if not line or line.startswith('#'): continue
    key,sep,value=line.partition('=')
    if not sep or not re.fullmatch(r'[A-Z][A-Z0-9_]*',key) or key in values: raise SystemExit('Invalid or duplicate dotenv key')
    value=value.strip()
    quoted=value[:1] in ("'",'"')
    quote=value[:1] if quoted else ''
    if quoted:
        if len(value)<2 or value[-1]!=quote: raise SystemExit('Unterminated quoted dotenv value')
        value=value[1:-1]
    else: value=value.split(' #',1)[0].rstrip()
    if '$' in value and quote!="'": raise SystemExit('Use single quotes for literal dollar signs; preflight does not expand environment variables')
    values[key]=value
required=['STAGING_HOST','TLS_ISSUER','POSTGRES_PASSWORD','SMTP_HOST','SMTP_USERNAME','SMTP_PASSWORD','MAIL_FROM']
errors=[f'Missing {key}' for key in required if not values.get(key)]
if len(values.get('POSTGRES_PASSWORD',''))<24 or 'replace' in values.get('POSTGRES_PASSWORD','').lower(): errors.append('POSTGRES_PASSWORD must be a generated secret of at least 24 characters')
try:
    network=ipaddress.ip_network(values.get('STAGING_SUBNET','10.77.0.0/24'))
    proxy=ipaddress.ip_address(values.get('CADDY_IP','10.77.0.10'))
    if network.version!=4 or proxy not in network or proxy in (network.network_address,network.broadcast_address): errors.append('CADDY_IP must be a usable IPv4 address in STAGING_SUBNET')
    literal=re.escape(str(proxy))
    if values.get('TRUSTED_PROXY_REGEX',r'10\.77\.0\.10') not in (literal,'^'+literal+'$'): errors.append('TRUSTED_PROXY_REGEX must trust exactly CADDY_IP')
except ValueError: errors.append('Invalid STAGING_SUBNET or CADDY_IP')
if args.local_test:
    if values.get('STAGING_HOST')!='localhost' or values.get('TLS_ISSUER')!='internal' or values.get('STAGING_BIND_ADDRESS')!='127.0.0.1': errors.append('Local rehearsal must use localhost, internal TLS and loopback binding')
    if values.get('SMTP_HOST')!='mail': errors.append('Local rehearsal must use its isolated Mailpit service')
else:
    if values.get('STAGING_HOST') in ('localhost','') or bool(re.search(r'(^|[.@])example\.(com|org|net)$',values.get('STAGING_HOST',''))) or values.get('TLS_ISSUER')=='internal': errors.append('Set a real hostname and ACME account email for Internet staging')
    if '@' not in values.get('TLS_ISSUER',''): errors.append('TLS_ISSUER must be an ACME account email')
    if values.get('SMTP_AUTH','true')!='true' or values.get('SMTP_STARTTLS','true')!='true': errors.append('External SMTP requires authentication and STARTTLS')
    for key in ('SMTP_HOST','SMTP_USERNAME','SMTP_PASSWORD','MAIL_FROM'):
        if re.search(r'(^|[.@])example\.(com|org|net)$',values.get(key,'')) or values.get(key,'').startswith('replace-'): errors.append(f'Set a real {key}')
if args.with_alerting:
    try:
        uid=int(values['ALERTMANAGER_UID']);gid=int(values['ALERTMANAGER_GID'])
        if uid<1 or gid<1: raise ValueError()
        config_path=pathlib.Path(values['ALERTMANAGER_CONFIG_FILE']);token_path=pathlib.Path(values['ALERTMANAGER_TOKEN_FILE'])
        state_path=pathlib.Path(values['ALERTMANAGER_STATE_DIR'])
        for path in (config_path,token_path):
            if not path.is_file() or path.stat().st_mode & 0o077 or path.stat().st_uid!=uid: raise ValueError()
        if not state_path.is_dir() or state_path.stat().st_uid!=uid or state_path.stat().st_mode & 0o077: raise ValueError()
        config=json.loads(config_path.read_text())
        webhook=config['receivers'][0]['webhook_configs'][0];target=urllib.parse.urlsplit(webhook['url'])
        if target.scheme!='https' and not(args.local_test and target.scheme=='http' and target.hostname in ('receiver','localhost','127.0.0.1','::1')): raise ValueError()
        if target.username or target.password or target.query or target.fragment: raise ValueError()
        if webhook['http_config'].get('tls_config',{}).get('insecure_skip_verify',False) is not False: raise ValueError()
        if webhook['http_config'].get('follow_redirects') is not False or webhook['http_config']['authorization']['credentials_file']!='/run/secrets/alert-webhook-token': raise ValueError()
        if not re.fullmatch(r'[A-Za-z0-9._~+/-]{24,512}={0,2}',token_path.read_text().strip()): raise ValueError()
    except (KeyError,ValueError,OSError,TypeError,IndexError): errors.append('Alerting requires a generated HTTPS webhook config, private token/state and matching container UID/GID')
if args.with_offsite:
    try:
        from backup_store import S3Store,StoreError
        S3Store(values['BACKUP_STORE_CONFIG'],values['BACKUP_REMOTE'],args.local_test)
        if not pathlib.Path(values['BACKUP_RECIPIENT_CERT']).is_file(): raise ValueError()
        directory=pathlib.Path(values['BACKUP_METRICS_DIR'])
        if not directory.is_dir() or directory.stat().st_mode & 0o005!=0o005 or directory.stat().st_mode & 0o022: raise ValueError()
    except (KeyError,ValueError,OSError,StoreError): errors.append('Offsite backup requires a private scoped S3 config, recipient certificate and readable metrics directory')
if errors: raise SystemExit('\n'.join(errors))
print('Staging configuration validation passed (secret values omitted).')
