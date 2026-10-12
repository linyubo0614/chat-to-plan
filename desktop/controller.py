"""Own only the child processes started by this desktop session."""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
from tools.openclaw_config import OpenClaw,SetupError,find_command,command_args,refreshed_path,DEEPSEEK_MODELS

BASE='http://127.0.0.1:8000'

class Controller:
    def __init__(self,root,emit=lambda kind,value:None):
        self.root=Path(root).resolve();self.emit=emit;self.children={};self.handles=[];self.address='';self.running=False;self.lock=threading.RLock();self.relay=None
    def prepare(self):
        data=self.root/'data';data.mkdir(exist_ok=True)
        if os.name=='nt':
            r=subprocess.run(['icacls',str(data),'/inheritance:r','/grant:r',f'{os.environ.get("USERDOMAIN","")}\\{os.environ.get("USERNAME","")}:(OI)(CI)F','*S-1-5-18:(OI)(CI)F'],capture_output=True,creationflags=0x08000000)
            if r.returncode:raise SetupError('无法保护数据目录，请解压到你自己的用户文件夹。')
        path=data/'api-token'
        try:fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        except FileExistsError:pass
        else:
            with os.fdopen(fd,'w') as f:f.write(secrets.token_urlsafe(32))
    def api(self,path,method='GET',body=None,timeout=10):
        token=(self.root/'data/api-token').read_text().strip()
        request=urllib.request.Request(BASE+path,method=method,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},data=None if body is None else json.dumps(body).encode())
        with urllib.request.urlopen(request,timeout=timeout) as r:return json.load(r)
    def child(self,name,args,env=None,pipe=False):
        if name in self.children and self.children[name].poll() is None:return self.children[name]
        out=subprocess.PIPE if pipe else open(self.root/'data'/f'{name}.log','ab')
        if not pipe:self.handles.append(out)
        p=subprocess.Popen(args,cwd=self.root,env={**os.environ,'PATH':refreshed_path(),**(env or {})},stdout=out,stderr=subprocess.STDOUT,creationflags=0x08000000 if os.name=='nt' else 0)
        self.children[name]=p
        return p
    def start(self,with_wechat=False):
        with self.lock:
            self.prepare();self.emit('state','正在启动电脑服务…')
            try:self.api('/v1/status')
            except (OSError,ValueError):
                args=[sys.executable,'--serve','--root',str(self.root)] if getattr(sys,'frozen',False) else [sys.executable,'-m','desktop.app','--serve','--root',str(self.root)]
                self.child('backend',args)
            for _ in range(60):
                try:status=self.api('/v1/status',timeout=1);break
                except (OSError,ValueError):time.sleep(.5)
            else:raise SetupError('电脑服务没有启动成功。请先关闭旧 start-assistant 窗口，再重试；错误保存在 data/backend.log。')
            if status.get('version')!='0.7.0' or status['integrations'].get('harness')!='structured_tools':raise SetupError('8000 端口正在运行旧程序，请关闭旧服务窗口再启动。')
            self.running=True;self.emit('state','电脑服务运行中')
            relay_path=self.root/'data/relay.json'
            relay_config=json.loads(relay_path.read_text(encoding='utf-8')) if relay_path.exists() else {}
            if relay_config.get('address'):
                from desktop.relay import RelayConnector
                if self.relay is None:
                    self.relay=RelayConnector(relay_config['address'],relay_config['secret'],self.emit)
                    self.relay.start()
                self.address=relay_config['address'];self.emit('address',self.address)
            else:
                self.emit('tunnel','请先配置免费固定中转，再生成手机配对二维码')
            if with_wechat:
                try:self.start_gateway()
                except SetupError as e:self.emit('error',str(e))
            return status
    def setup_relay(self):
        if self.running:raise SetupError('请先停止服务，再配置固定中转。')
        self.prepare()
        from tools.relay_setup import deploy
        self.address=deploy(self.root,self.emit);self.emit('address',self.address)

    def pairing(self):
        if not self.address:raise SetupError('请先配置固定中转并启动服务。')
        from urllib.parse import quote
        ticket=self.api('/v1/pairings','POST',{'label':'对话计划扫码'})
        value='chatplanner://pair?server='+quote(self.address,safe='')+'#'+ticket['code']
        self.emit('pairing',{'link':value,'expires_at':ticket['expires_at']})

    def export_harness(self):
        self.prepare()
        from tools.harness_config import export_config
        path=export_config(self.root)
        self.emit('diagnosis','Harness 配置已生成：'+str(path)+'\n按 docs/harness.md 把生成的 patch 加入现有 Harness 启动配置。无需再次填写模型 Key。')

    def read_tunnel(self,p):
        connected=False
        for raw in iter(p.stdout.readline,b''):
            line=raw.decode('utf-8','replace')
            match=re.search(r'https://[a-z0-9-]+\.trycloudflare\.com',line)
            if match:self.address=match.group(0);self.emit('address',self.address)
            if 'Registered tunnel connection' in line:connected=True;self.emit('tunnel','通道已连接')
        p.stdout.close()
        if self.children.get('tunnel') is p:self.address='';self.emit('address','')
        self.emit('tunnel','手机通道已停止' if connected else '通道未建立，请检查网络或 VPN')
    def start_gateway(self):
        claw=OpenClaw(self.root)
        agents=claw.get('agents',{})
        model=agents.get('defaults',{}).get('model',{})
        primary=model.get('primary') if isinstance(model,dict) else model
        if primary not in ['deepseek/'+m for m in DEEPSEEK_MODELS]:
            self.emit('wechat','请先配置 DeepSeek，尚未启动旧模型助手');return
        if self.children.get('gateway') and self.children['gateway'].poll() is None:return
        check=claw.run('gateway','status','--json',timeout=20)
        try:status=json.loads(check.stdout)
        except ValueError:status={}
        if status.get('rpc',{}).get('ok'):
            self.emit('wechat','使用已经运行的微信助手');return
        self.child('gateway',command_args(claw.command,['gateway','run']))
        self.emit('wechat','微信助手正在后台启动')
    def stop_child(self,name):
        p=self.children.pop(name,None)
        if p is None or p.poll() is not None:return
        if os.name=='nt':subprocess.run(['taskkill','/PID',str(p.pid),'/T','/F'],capture_output=True,creationflags=0x08000000)
        else:
            p.terminate()
            try:p.wait(5)
            except subprocess.TimeoutExpired:p.kill();p.wait()
    def stop(self):
        with self.lock:
            if self.relay:self.relay.stop();self.relay=None
            for name in ['gateway','tunnel','backend']:self.stop_child(name)
            for f in self.handles:f.close()
            self.handles=[];self.address='';self.running=False;self.emit('state','已停止本软件启动的服务')
    def install_tools(self):
        self.prepare();claw=OpenClaw(self.root);claw.install()
        self.emit('wechat','保存工具已安装到所有助手；请点击重启微信助手')
    def restart_gateway(self):
        with self.lock:
            if self.children.get('gateway'):
                self.stop_child('gateway');self.start_gateway()
            else:OpenClaw(self.root).restart()
        self.emit('wechat','微信助手已重启；发 /new 开始新会话，再测试记录')
    def deepseek(self,key):
        key=key.strip()
        if not key or any(c.isspace() for c in key) or len(key)>512:raise SetupError('请填写完整有效的 DeepSeek API Key。')
        self.prepare()
        request=urllib.request.Request('https://api.deepseek.com/models',headers={'Authorization':'Bearer '+key.strip()})
        try:
            with urllib.request.urlopen(request,timeout=20) as r:data=json.load(r)
        except urllib.error.HTTPError as e:raise SetupError('DeepSeek Key 无效或没有访问权限。' if e.code in (401,403) else 'DeepSeek 暂时无法访问，请稍后重试。') from None
        except (OSError,ValueError):raise SetupError('无法连接 DeepSeek，请检查电脑网络。') from None
        if not isinstance(data,dict) or not isinstance(data.get('data'),list):raise SetupError('DeepSeek 模型列表响应异常，请重试；配置未修改。')
        available={m.get('id') for m in data['data'] if isinstance(m,dict) and isinstance(m.get('id'),str)}
        model_id=next((m for m in DEEPSEEK_MODELS if m in available),None)
        if model_id is None:raise SetupError('账号未返回受支持的 DeepSeek 模型，请确认 Key 来自 platform.deepseek.com；配置未修改。')
        self.emit('wechat','正在验证 '+model_id+' 的工具调用能力…')
        # Verify the same API can return the tool calls needed by WeChat recording.
        body={'model':model_id,'thinking':{'type':'disabled'},'messages':[{'role':'user','content':'Call planner_connection_check to verify tool support.'}],'tools':[{'type':'function','function':{'name':'planner_connection_check','description':'Test tool support only; performs no action.','parameters':{'type':'object','properties':{},'additionalProperties':False}}}],'tool_choice':{'type':'function','function':{'name':'planner_connection_check'}},'max_tokens':256}
        req=urllib.request.Request('https://api.deepseek.com/chat/completions',headers={'Authorization':'Bearer '+key.strip(),'Content-Type':'application/json'},data=json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req,timeout=30) as r:answer=json.load(r)
        except urllib.error.HTTPError as e:
            messages={401:'DeepSeek Key 无效，请在官方平台重新生成。',403:'DeepSeek 账号无访问权限。',402:'DeepSeek API 余额不足，请在官方开放平台检查余额。',404:'DeepSeek 模型暂不可用，请重试模型验证。',429:'DeepSeek 请求繁忙，请稍后重试。'}
            raise SetupError(messages.get(e.code,'DeepSeek 模型调用失败（HTTP '+str(e.code)+'），配置未修改。')) from None
        except (OSError,ValueError):raise SetupError('DeepSeek 模型调用超时或响应异常，请重试。') from None
        choices=answer.get('choices',[]) if isinstance(answer,dict) else []
        message=choices[0].get('message',{}) if choices and isinstance(choices[0],dict) else {}
        calls=message.get('tool_calls',[]) if isinstance(message,dict) else []
        if not isinstance(calls,list) or not any(isinstance(t,dict) and isinstance(t.get('function'),dict) and t['function'].get('name')=='planner_connection_check' for t in calls):raise SetupError('DeepSeek 没有返回所需工具调用，配置尚未应用。')
        self.api('/v1/status')
        count=OpenClaw(self.root).configure_deepseek(key,model_id)
        self.api('/v1/settings','PUT',{'deepseek_api_key':key.strip(),'model':model_id})
        self.emit('wechat',f'{count} 个助手及子助手已设置 {model_id}，未配置 ChatGPT 回退。请重启微信助手并发 /new。')
    def diagnose(self):
        lines=[]
        try:
            status=self.api('/v1/status');lines.append('电脑服务：正常；自动处理：'+('开启' if status['enabled'] else '关闭（请在手机设置开启）'))
            links=status.get('connections',{})
            lines.append('最近 Harness 计划调用：'+str(links.get('harness') or '尚未收到'))
            lines.append('最近微信工具调用：'+str(links.get('openclaw') or '尚未收到'))
            lines.append('最近 HITA 连接：'+str(links.get('hita') or '尚未收到'))
        except (OSError,ValueError):lines.append('电脑服务：未连接，请启动')
        if self.address:
            try:
                with urllib.request.urlopen(self.address+'/healthz',timeout=15) as r:assert json.load(r)['status']=='ok'
                lines.append('手机 HTTPS 通道：正常')
            except Exception:lines.append('手机 HTTPS 通道：未能到达，请检查网络')
        else:lines.append('手机 HTTPS 通道：尚未取得地址')
        self.emit('diagnosis','\n'.join(lines))
        return lines

    def diagnose_openclaw(self):
        lines=[]
        try:
            claw=OpenClaw(self.root)
            result=claw.run('gateway','status','--json',timeout=20)
            gateway=json.loads(result.stdout)
            online=isinstance(gateway,dict) and gateway.get('rpc',{}).get('ok')
            lines.append('微信助手网关：'+('已连接' if online else '未连接，请点击启动全部'))
            info=claw.inspect()
            primary=info['primary']
            primary=primary.get('primary','未配置') if isinstance(primary,dict) else primary
            lines.append('OpenClaw 保存工具：已加载；配置模型：'+str(primary))
            if primary not in ['deepseek/'+m for m in DEEPSEEK_MODELS]:
                lines.append('模型尚未切换：请验证并应用 DeepSeek，再重启微信助手；微信发 /new。')
            else:
                lines.append('配置已保存；正在运行的旧会话仍需重启助手并发 /new，配置模型不等于本次回复模型。')
        except SetupError as e:lines.append(str(e))
        except (ValueError,TypeError):lines.append('微信助手网关：状态响应异常，请确认 OpenClaw 版本。')
        self.emit('diagnosis','\n'.join(lines))
        return lines
    def write_test(self):
        import uuid
        payload={'record_id':uuid.uuid4().hex,'kind':'memo','title':'电脑手机连接测试','body':'看到这条备忘，说明电脑服务到手机已经连通。','category':'连接测试','important':False,'urgent':False,'completed':False,'task_date':None}
        self.api('/v1/planner/records','POST',payload)
        self.emit('diagnosis','测试备忘已保存。请在手机“分类备忘”查看“电脑手机连接测试”。这一步不代表微信工具已调用。')
