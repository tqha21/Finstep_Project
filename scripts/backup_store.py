#!/usr/bin/env python3
"""Publish/fetch encrypted backup bundles in an existing S3 bucket; never prune objects."""
import argparse
import configparser
import datetime
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile
import time
import urllib.parse
import uuid

class StoreError(Exception):
    pass

def digest(path):
    with pathlib.Path(path).open('rb') as source:
        return hashlib.file_digest(source,'sha256').hexdigest()

def encrypted_bundle(path):
    # CMS AuthEnvelopedData content type, not a plaintext dump or SignedData wrapper.
    # This is a format guard, not proof of recipient identity or a valid authentication tag.
    with pathlib.Path(path).open('rb') as source:
        if source.read(1)!=b'\x30': raise StoreError('Expected a CMS AuthEnvelopedData backup')
        length=source.read(1)
        if not length: raise StoreError('Incomplete CMS header')
        if length[0]>128: source.read(length[0]&127)
        if source.read(2)!=b'\x06\x0b' or source.read(11)!=bytes.fromhex('2a864886f70d0109100117'):
            raise StoreError('Expected a CMS AuthEnvelopedData backup')

def private_file(path):
    path=pathlib.Path(path)
    if not path.is_file() or path.stat().st_mode & 0o077:
        raise StoreError('Credential/config file must exist with owner-only permissions')
    return path.resolve()

class S3Store:
    def __init__(self,config,remote,local_test=False):
        self.config=private_file(config)
        name,sep,directory=remote.partition(':')
        parts=directory.strip('/').split('/')
        if not sep or not re.fullmatch(r'[A-Za-z0-9_-]+',name) or len(parts)<2 or any(not p or p in ('.','..') or not re.fullmatch(r'[A-Za-z0-9._-]+',p) for p in parts):
            raise StoreError('Remote must identify an S3 bucket and a dedicated prefix')
        if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]',parts[0]) or parts[0].startswith('replace-'):
            raise StoreError('Set a real pre-existing S3 bucket name')
        parser=configparser.ConfigParser(interpolation=None)
        try: parser.read(self.config)
        except (configparser.Error,OSError,UnicodeError): raise StoreError("Cannot read private S3 configuration") from None
        if name not in parser or parser[name].get('type')!='s3': raise StoreError('Only an explicit S3 remote is supported')
        settings=parser[name]
        if settings.get('env_auth','false').lower()!='false': raise StoreError('Use explicit scoped credentials in the private config file')
        if not settings.get('access_key_id') or not settings.get('secret_access_key') or any(settings.get(k,'').startswith('replace-') for k in ('access_key_id','secret_access_key')):
            raise StoreError('Set scoped S3 credentials')
        endpoint=settings.get('endpoint','')
        if endpoint:
            url=urllib.parse.urlsplit(endpoint)
            if not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ('','/'):
                raise StoreError('S3 endpoint must be an origin without credentials, path or query')
            if url.scheme!='https' and not (local_test and url.scheme=='http' and url.hostname in ('localhost','127.0.0.1','::1')):
                raise StoreError('S3 requires verified HTTPS; HTTP is reserved for an explicit loopback fixture')
        elif settings.get('provider')!='AWS':
            raise StoreError('Set an HTTPS endpoint for a non-AWS S3 provider')
        self.root=name+':'+ '/'.join(parts)
        self.environment={key:value for key,value in os.environ.items() if not key.startswith(('RCLONE_','AWS_'))}
        if local_test:
            bypass=self.environment.get('NO_PROXY',self.environment.get('no_proxy',''))+',localhost,127.0.0.1,::1'
            self.environment['NO_PROXY']=bypass;self.environment['no_proxy']=bypass
    def run(self,*args):
        command=['rclone','--config',str(self.config),'--no-check-certificate=false','--s3-no-check-bucket',
                 '--contimeout','10s','--timeout','1m','--retries','2',*map(str,args)]
        result=subprocess.run(command,env=self.environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if result.returncode: raise StoreError('S3 operation failed; remote details and credentials omitted')
        return result.stdout
    def key(self,backup_id,name):
        return self.root+'/'+str(uuid.UUID(backup_id))+'/'+name
    def upload(self,path):
        path=pathlib.Path(path).resolve()
        if path.suffix!='.cms' or not path.is_file(): raise StoreError('Supply an encrypted .cms backup bundle')
        encrypted_bundle(path)
        backup_id=str(uuid.uuid4())
        before=path.stat();checksum=digest(path)
        receipt={'format':'finstep-s3-backup-v1','backupId':backup_id,'sha256':checksum,'size':before.st_size,
                 'publishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
        with tempfile.TemporaryDirectory(prefix='finstep-upload-') as directory:
            check=pathlib.Path(directory)/'readback.cms'
            self.run('copyto',path,self.key(backup_id,'data.cms'),'--immutable')
            self.run('copyto',self.key(backup_id,'data.cms'),check)
            if check.stat().st_size!=before.st_size or digest(check)!=checksum or path.stat().st_mtime_ns!=before.st_mtime_ns:
                raise StoreError('Remote readback verification failed; no receipt was published')
            marker=pathlib.Path(directory)/'receipt.json'
            marker.write_text(json.dumps(receipt))
            # The receipt is the commit marker. An orphan data object is never a published backup.
            self.run('copyto',marker,self.key(backup_id,'receipt.json'),'--immutable')
        return {'backupId':backup_id,'readbackVerified':True}
    def fetch(self,backup_id,output):
        backup_id=str(uuid.UUID(backup_id))
        output=pathlib.Path(output).absolute()
        if os.path.lexists(output): raise StoreError('Output already exists; refusing to overwrite')
        raw=self.run('cat',self.key(backup_id,'receipt.json'),'--head','16385')
        try:
            receipt=json.loads(raw)
            if len(raw)>16384 or receipt['format']!='finstep-s3-backup-v1' or receipt['backupId']!=backup_id or not re.fullmatch('[0-9a-f]{64}',receipt['sha256']) or type(receipt['size']) is not int or receipt['size']<=0:
                raise ValueError('Invalid receipt')
        except (ValueError,KeyError,TypeError): raise StoreError('Missing or invalid publication receipt') from None
        output.parent.mkdir(parents=True,exist_ok=True)
        descriptor,temporary=tempfile.mkstemp(prefix='.finstep-fetch-',dir=output.parent)
        os.close(descriptor)
        temporary=pathlib.Path(temporary)
        try:
            self.run('copyto',self.key(backup_id,'data.cms'),temporary)
            if temporary.stat().st_size!=receipt['size'] or digest(temporary)!=receipt['sha256']:
                raise StoreError('Downloaded bundle does not match the publication receipt')
            encrypted_bundle(temporary)
            # Same-filesystem link is atomic and cannot overwrite a file created concurrently.
            os.link(temporary,output)
        finally:
            temporary.unlink(missing_ok=True)
        return {'backupId':backup_id,'sha256Verified':True}

def metrics(path,success):
    path=pathlib.Path(path).absolute()
    if path.suffix!=".prom": raise StoreError("Metrics output must have a .prom extension")
    if not path.parent.exists():
        path.parent.mkdir(mode=0o755,parents=True)
        path.parent.chmod(0o755)
    if path.parent.stat().st_mode & 0o005 != 0o005:
        raise StoreError("Metrics directory must be readable/traversable by the collector")
    if path.is_symlink() or path.parent.stat().st_mode & 0o022:
        raise StoreError('Metrics must be in a trusted directory, without a symlink target')
    previous=0
    if path.exists():
        match=re.search(r'^finstep_backup_offsite_last_success_timestamp_seconds ([0-9]+)$',path.read_text(),re.MULTILINE)
        if match: previous=int(match[1])
    now=int(time.time());latest=now if success else previous
    text=(f'finstep_backup_offsite_last_success_timestamp_seconds {latest}\n'
          f'finstep_backup_offsite_last_attempt_timestamp_seconds {now}\n'
          f'finstep_backup_offsite_last_attempt_success {int(success)}\n')
    descriptor,temporary=tempfile.mkstemp(prefix='.metrics-',dir=path.parent)
    try:
        with os.fdopen(descriptor,'w') as output: output.write(text)
        os.chmod(temporary,0o644)
        os.replace(temporary,path)
    finally:
        pathlib.Path(temporary).unlink(missing_ok=True)

def main():
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',required=True,type=pathlib.Path)
    parser.add_argument('--remote',required=True)
    parser.add_argument('--local-test',action='store_true')
    actions=parser.add_subparsers(dest='action',required=True)
    upload=actions.add_parser('upload');upload.add_argument('bundle',type=pathlib.Path);upload.add_argument('--metrics-file',type=pathlib.Path)
    fetch=actions.add_parser('fetch');fetch.add_argument('backup_id');fetch.add_argument('output',type=pathlib.Path)
    backup=actions.add_parser('backup');backup.add_argument('source_container');backup.add_argument('local_directory');backup.add_argument('recipient_certificate');backup.add_argument('--metrics-file',type=pathlib.Path)
    args=parser.parse_args();success=False
    try:
        store=S3Store(args.config,args.remote,args.local_test)
        if args.action=='fetch': result=store.fetch(args.backup_id,args.output)
        else:
            path=args.bundle if args.action=='upload' else None
            if args.action=='backup':
                producer=pathlib.Path(__file__).with_name('backup-db.sh')
                result=subprocess.run(['bash',str(producer),args.source_container,args.local_directory,args.recipient_certificate],capture_output=True,text=True)
                if result.returncode: raise StoreError('Local encrypted backup failed; no upload was attempted')
                path=pathlib.Path(result.stdout.strip())
            result=store.upload(path)
        success=True
    except (StoreError,ValueError,OSError):
        raise SystemExit('Backup store operation failed; check configuration/storage and keep recovery quarantined. Secret values omitted.') from None
    finally:
        if args.action in ('backup','upload') and args.metrics_file:
            try: metrics(args.metrics_file,success)
            except (StoreError,OSError): raise SystemExit('Backup status metric could not be written; check directory permissions. Secret values omitted.') from None
    print(json.dumps(result))

if __name__=='__main__': main()
