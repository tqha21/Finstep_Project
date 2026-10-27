#!/usr/bin/env python3
"""Validate a dotenv configuration without executing it or printing secret values."""
import argparse
import pathlib
import ipaddress
import re

parser=argparse.ArgumentParser()
parser.add_argument('env_file',type=pathlib.Path)
parser.add_argument('--local-test',action='store_true')
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
if errors: raise SystemExit('\n'.join(errors))
print('Staging configuration validation passed (secret values omitted).')
