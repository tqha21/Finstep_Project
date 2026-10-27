"""Disposable local webhook: authenticate, fail once, expose a controllable canary metric."""
import hmac
import json
import pathlib
import threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
TOKEN=pathlib.Path('/run/secrets/alert-webhook-token').read_text().strip()
state={'attempts':0,'events':[],'canary':1,'unauthorized':0}
lock=threading.Lock()
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def reply(self,status,data,content_type='application/json'):
        payload=data.encode() if isinstance(data,str) else json.dumps(data).encode()
        self.send_response(status);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
    def do_GET(self):
        with lock:
            if self.path=='/metrics': return self.reply(200,f'finstep_delivery_canary {state["canary"]}\n','text/plain')
            if self.path=='/state': return self.reply(200,state)
        self.reply(404,{})
    def do_POST(self):
        if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+TOKEN):
            with lock: state['unauthorized']+=1
            return self.reply(401,{})
        size=int(self.headers.get('Content-Length','0'))
        if size>65536:return self.reply(413,{})
        try: body=json.loads(self.rfile.read(size))
        except ValueError:return self.reply(400,{})
        with lock:
            if self.path=='/control':
                state['canary']=int(bool(body['canary']));return self.reply(200,{})
            if self.path!='/alerts':return self.reply(404,{})
            state['attempts']+=1
            if state['attempts']==1:return self.reply(503,{'fixture':'retry this notification'})
            state['events'].append(body)
            return self.reply(200,{})
ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
