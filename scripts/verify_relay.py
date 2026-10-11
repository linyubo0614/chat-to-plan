"""Real local workerd -> WebSocket connector -> HTTP backend acceptance check.

Start wrangler dev on 8791 with BRIDGE_SECRET=synthetic-local-bridge-test first.
Only temporary synthetic data is used; port 8000 must be free.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time

import httpx
import uvicorn
import websocket

from backend.app import create_app
from backend.config import Settings
from desktop.relay import RelayConnector


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--relay',default='http://127.0.0.1:8791')
    parser.add_argument('--mcp',action='store_true')
    args=parser.parse_args()
    assert args.relay.startswith('http://127.0.0.1:'), 'This verifier is local only'
    with tempfile.TemporaryDirectory(prefix='planner-relay-test-') as folder:
        root=Path(folder);(root/'data').mkdir();(root/'data/api-token').write_text('a'*32)
        settings=Settings(api_token='a'*32,db_path=root/'data/assistant.db')
        http=httpx.Client(base_url=args.relay,trust_env=False,timeout=45)
        local=httpx.Client(base_url='http://127.0.0.1:8000',trust_env=False,timeout=2)
        try:
            local.get('/healthz')
        except httpx.TransportError:pass
        else:raise RuntimeError('Port 8000 is occupied; refusing to use an existing backend')
        def start_backend():
            server=uvicorn.Server(uvicorn.Config(create_app(settings,run_worker=False),host='127.0.0.1',port=8000,log_level='error'))
            thread=threading.Thread(target=server.run,daemon=True);thread.start()
            for _ in range(100):
                if server.started:return server,thread
                time.sleep(.05)
            raise RuntimeError('Backend failed to start')
        def connector():
            connected=threading.Event()
            bridge=RelayConnector('https://synthetic.example.workers.dev','synthetic-local-bridge-test',
                                  lambda k,v:connected.set() if '已连接' in v else None)
            # Local-only transport override in the test, never a production URL option.
            bridge.address=args.relay.replace('http://','ws://')
            bridge.start()
            assert connected.wait(8),'Connector did not establish a WebSocket'
            return bridge
        server,thread=start_backend();bridge=None
        try:
            assert http.get('/healthz').status_code==503
            try:
                wrong=websocket.create_connection(args.relay.replace('http://','ws://')+'/bridge',header={'Authorization':'Bearer wrong'},timeout=3)
            except websocket.WebSocketBadStatusException as e:assert e.status_code==401
            else:wrong.close();raise AssertionError('Unauthenticated connector accepted')
            bridge=connector()
            assert http.get('/healthz').status_code==200
            assert http.get('/v1/status').status_code==401
            code=local.post('/v1/pairings',headers={'Authorization':'Bearer '+'a'*32},json={}).json()['code']
            paired=http.post('/v1/pairings/claim',json={'code':code,'label':'acceptance-phone'})
            assert paired.status_code==200,paired.text
            token=paired.json()['token'];headers={'Authorization':'Bearer '+token}
            assert http.post('/v1/pairings/claim',json={'code':code}).status_code==410
            assert http.get('/v1/status',headers=headers).status_code==200
            plan={'message_id':'relay-check-operation','text':'明天下午高数课','interpretation':{'items':[
                {'kind':'course','title':'Relay test','starts_at':'2026-10-12T15:00:00+08:00','ends_at':'2026-10-12T16:00:00+08:00'}]}}
            with ThreadPoolExecutor(max_workers=1) as pool:
                waiting=pool.submit(lambda:http.post('/v1/delivery/hita/claim?wait=10',headers=headers,json={'client_id':'test-hita'}))
                time.sleep(.2)
                saved=local.post('/v1/harness/plans',headers=headers,json=plan)
                assert saved.status_code==201
                claimed=waiting.result(timeout=12).json()
            assert claimed['task']['payload']['title']=='Relay test'
            acknowledged=http.post('/v1/delivery/'+claimed['task']['id']+'/ack',headers=headers,json={
                'client_id':'test-hita','lease_token':claimed['lease_token'],'revision':claimed['task']['revision'],'external_id':'synthetic-readback'})
            assert acknowledged.status_code==200
            if args.mcp:
                subprocess.run(['node','scripts/verify_mcp.mjs'],env={**os.environ,'MCP_TEST_ROOT':str(root),'MCP_TEST_PYTHON':sys.executable},check=True)
            bridge.stop();bridge=None
            time.sleep(.1)
            assert http.get('/healthz').status_code==503
            server.should_exit=True;thread.join(5)
            server,thread=start_backend();bridge=connector()
            assert http.get('/v1/status',headers=headers).status_code==200,'Persisted device token lost after restart'
            retry=http.post('/v1/harness/plans',headers=headers,json=plan)
            assert retry.status_code==200 and retry.json()['duplicate']
            assert retry.json()['message']['tasks'][0]['status']=='written'
            print('Real relay: auth, one-use pairing, long-poll HITA delivery, offline recovery and persistent device connection passed.')
        finally:
            if bridge:bridge.stop()
            server.should_exit=True;thread.join(5);http.close();local.close()


if __name__=='__main__':main()
