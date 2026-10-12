"""Exercise the shipped Windows executable over its real stdio protocol."""
import json
from pathlib import Path
import subprocess
import sys

root=Path(sys.argv[1]).resolve()
messages=[{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-11-25','capabilities':{},'clientInfo':{'name':'test','version':'1'}}},
          {'jsonrpc':'2.0','method':'notifications/initialized'},
          {'jsonrpc':'2.0','id':2,'method':'tools/list'},
          {'jsonrpc':'2.0','id':3,'method':'tools/call','params':{'name':'chat_planner_status','arguments':{}}}]
run=subprocess.run([str(root/'ChatPlannerMcp.exe'),'--root',str(root)],input=''.join(json.dumps(m)+'\n' for m in messages),
                   text=True,encoding='utf-8',capture_output=True,timeout=45)
assert run.returncode==0,'MCP executable failed'
replies=[json.loads(line) for line in run.stdout.splitlines()]
assert [r['id'] for r in replies]==[1,2,3], 'Unexpected stdout or notification response'
assert len(replies[1]['result']['tools'])==2
assert replies[2]['result']['structuredContent']['ok'],replies[2]
print('Standalone MCP executable: handshake, tools and authenticated backend call passed.')
