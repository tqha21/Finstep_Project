#!/usr/bin/env python3
"""Render private Alertmanager webhook configuration without printing endpoint/token values."""
import argparse
import json
import os
import pathlib
import re
import urllib.parse

parser=argparse.ArgumentParser()
parser.add_argument('--url-file',required=True,type=pathlib.Path)
parser.add_argument('--token-file',required=True,type=pathlib.Path)
parser.add_argument('--output',required=True,type=pathlib.Path)
parser.add_argument('--local-test',action='store_true')
args=parser.parse_args()
os.umask(0o077)
for file in (args.url_file,args.token_file):
    if not file.is_file() or file.stat().st_mode & 0o077: raise SystemExit('Webhook input files require owner-only permissions')
url=args.url_file.read_text().strip();token=args.token_file.read_text().strip()
try:
    target=urllib.parse.urlsplit(url)
    if not target.hostname or target.username or target.password or target.query or target.fragment or len(url)>4096: raise ValueError()
    if target.scheme!='https' and not(args.local_test and target.scheme=='http' and target.hostname in ('receiver','localhost','127.0.0.1','::1')): raise ValueError()
except ValueError: raise SystemExit('Webhook must be verified HTTPS without userinfo/query/fragment; HTTP is reserved for explicit local fixtures') from None
if not re.fullmatch(r'[A-Za-z0-9._~+/-]{24,512}={0,2}',token): raise SystemExit('Use a valid Bearer token of at least 24 characters')
config={'route':{'receiver':'operations-webhook','group_by':['alertname','instance'],
                'group_wait':'1s' if args.local_test else '30s','group_interval':'1s' if args.local_test else '5m','repeat_interval':'4h'},
        'receivers':[{'name':'operations-webhook','webhook_configs':[{'url':url,'send_resolved':True,
           'http_config':{'follow_redirects':False,'authorization':{'type':'Bearer','credentials_file':'/run/secrets/alert-webhook-token'}}}]}]}
args.output.parent.mkdir(parents=True,exist_ok=True)
with args.output.open('x') as output: json.dump(config,output)
print('Alertmanager configuration created; endpoint and token omitted.')
