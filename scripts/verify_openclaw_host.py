"""Exercise the official model/tool loop against a synthetic local model and real backend. Never uses user credentials or sends a channel message."""
import os,sys,json,tempfile,threading,subprocess,time,uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from tools.openclaw_config import OpenClaw
import httpx
root=Path(__file__).resolve().parents[1];state=Path(os.environ['OPENCLAW_STATE_DIR']);config=Path(os.environ['OPENCLAW_CONFIG_PATH'])
if 'test' not in str(state).lower():raise SystemExit('Only an explicitly isolated test state may be used')
os.environ.setdefault('XDG_CACHE_HOME',str(state.parent/'cache'))
original=config.read_bytes();calls=[];session=uuid.uuid4().hex
plan={'text':'今天交高数作业，重要不紧急；她喜欢茉莉花茶；2026年10月14日15点到16点高数课，二教301','interpretation':{'items':[{'kind':'todo','title':'原生工具高数作业','category':'高数','important':True,'urgent':False},{'kind':'memo','title':'原生工具喜欢的茶','category':'女朋友','body':'茉莉花茶'},{'kind':'course','title':'原生工具高数课','category':'高数','location':'二教301','starts_at':'2026-10-14T15:00:00+08:00','ends_at':'2026-10-14T16:00:00+08:00'}]}}
class Model(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));calls.append(body)
  available=[t['function']['name'] for t in body.get('tools',[])]
  if 'chat_planner_record' not in available and 'tool_call' not in available: self.send_error(500,'planner tool absent');return
  used=any(m.get('role')=='tool' for m in body['messages'])
  base={'id':'fake-completion','object':'chat.completion.chunk','created':int(time.time()),'model':'deepseek-chat'}
  delta={'role':'assistant','content':'已按工具返回状态保存。'} if used else {'role':'assistant','tool_calls':[{'index':0,'id':'call_planner_verify','type':'function','function':{'name':'chat_planner_record' if 'chat_planner_record' in available else 'tool_call','arguments':json.dumps(plan if 'chat_planner_record' in available else {'id':'chat_planner_record','args':plan},ensure_ascii=False)}}]}
  if body.get('stream'):
   self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
   for choices in [[{'index':0,'delta':delta,'finish_reason':None}],[{'index':0,'delta':{},'finish_reason':'stop' if used else 'tool_calls'}]]:self.wfile.write(('data: '+json.dumps({**base,'choices':choices})+'\n\n').encode())
   self.wfile.write(b'data: [DONE]\n\n');self.wfile.flush()
  else:
   message={k:v for k,v in delta.items()};message['content']=message.get('content')
   self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(json.dumps({**base,'object':'chat.completion','choices':[{'index':0,'message':message,'finish_reason':'stop' if used else 'tool_calls'}],'usage':{'prompt_tokens':100,'completion_tokens':100,'total_tokens':200}}).encode())
with tempfile.TemporaryDirectory(prefix='planner-host-loop-') as d:
 tmp=Path(d);token=tmp/'token';token.write_text('a'*32)
 server=ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=server.serve_forever,daemon=True).start()
 backend=subprocess.Popen([sys.executable,'-m','uvicorn','backend.app:create_app','--factory','--host','127.0.0.1','--port','8019','--no-access-log'],cwd=root,env={**os.environ,'ASSISTANT_API_TOKEN':'a'*32,'ASSISTANT_DB':str(tmp/'db')},stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 try:
  for _ in range(40):
   try:httpx.get('http://127.0.0.1:8019/healthz').raise_for_status();break
   except httpx.HTTPError:time.sleep(.2)
  claw=OpenClaw(root)
  claw.apply([{'path':'models.providers.deepseek.baseUrl','value':f'http://127.0.0.1:{server.server_port}'},{'path':'agents.defaults.workspace','value':str(state/'workspace')},{'path':'plugins.entries.chat-planner.config.baseUrl','value':'http://127.0.0.1:8019'},{'path':'plugins.entries.chat-planner.config.tokenFile','value':str(token)},{'path':'plugins.entries.chat-planner.config.receiptWaitMs','value':0}])
  result=claw.run('agent','--local','--agent','main','--session-id',session,'--message',plan['text'],'--json','--timeout','90',timeout=120)
  print('Native agent exit',result.returncode,'model requests',len(calls))
  if result.returncode:print('Available tools:',[t['function']['name'] for t in calls[0].get('tools',[])] if calls else []);print('Synthetic diagnostic',result.stdout[-1800:],result.stderr[-1800:])
  auth={'Authorization':'Bearer '+'a'*32}
  notes=httpx.get('http://127.0.0.1:8019/v1/planner/records?kind=memo',headers=auth).json()['records']
  tasks=httpx.get('http://127.0.0.1:8019/v1/planner/records?kind=todo',headers=auth).json()['records']
  messages=httpx.get('http://127.0.0.1:8019/v1/messages',headers=auth).json()['messages']
  if not notes:print('Synthetic tool results:',[m.get('content') for m in calls[-1].get('messages',[]) if m.get('role')=='tool'] if calls else [])
  assert result.returncode==0 and len(calls)>=2 and notes[0]['category']=='女朋友' and tasks[0]['important'] and not tasks[0]['urgent']
  message=httpx.get('http://127.0.0.1:8019/v1/messages/'+messages[0]['id'],headers=auth).json()
  assert any(t['target']=='hita' and t['status']=='blocked' for t in message['tasks'])
  print('Official OpenClaw model -> registered tool -> real backend -> categorized memo, quadrant task, HITA queue: PASSED')
 finally:
  config.write_bytes(original);server.shutdown();server.server_close();backend.terminate();backend.wait(timeout=10)
