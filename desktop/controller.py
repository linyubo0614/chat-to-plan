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
from tools.openclaw_config import OpenClaw,SetupError,find_command,command_args,refreshed_path

BASE='http://127.0.0.1:8000'

class Controller:
    def __init__(self,root,emit=lambda kind,value:None):
        self.root=Path(root).resolve();self.emit=emit;self.children={};self.handles=[];self.address='';self.running=False;self.lock=threading.RLock()
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
    def start(self,with_wechat=True):
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
            if status['integrations'].get('openclaw')!='structured_tools':raise SetupError('8000 端口正在运行旧程序，请关闭旧服务窗口再启动。')
            self.running=True;self.emit('state','电脑服务运行中')
            try:
                existing=self.children.get('tunnel')
                if existing is None or existing.poll() is not None:
                    tunnel=self.child('tunnel',[find_command('cloudflared'),'tunnel','--no-autoupdate','--url',BASE],pipe=True)
                    threading.Thread(target=self.read_tunnel,args=(tunnel,),daemon=True).start()
            except SetupError as e:self.emit('error',str(e))
            if with_wechat:
                try:self.start_gateway()
                except SetupError as e:self.emit('error',str(e))
            return status
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
        if primary!='deepseek/deepseek-chat':
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
        self.prepare()
        request=urllib.request.Request('https://api.deepseek.com/models',headers={'Authorization':'Bearer '+key.strip()})
        try:
            with urllib.request.urlopen(request,timeout=20) as r:data=json.load(r)
        except urllib.error.HTTPError as e:raise SetupError('DeepSeek Key 无效或没有访问权限。' if e.code in (401,403) else 'DeepSeek 暂时无法访问，请稍后重试。') from None
        except (OSError,ValueError):raise SetupError('无法连接 DeepSeek，请检查电脑网络。') from None
        if 'deepseek-chat' not in [m.get('id') for m in data.get('data',[])]:raise SetupError('当前 DeepSeek 账号未提供 deepseek-chat。')
        # Verify the same API can return the tool calls needed by WeChat recording.
        body={'model':'deepseek-chat','messages':[{'role':'user','content':'Call planner_connection_check to verify tool support.'}],'tools':[{'type':'function','function':{'name':'planner_connection_check','description':'Test tool support only; performs no action.','parameters':{'type':'object','properties':{},'additionalProperties':False}}}],'tool_choice':{'type':'function','function':{'name':'planner_connection_check'}},'max_tokens':64}
        req=urllib.request.Request('https://api.deepseek.com/chat/completions',headers={'Authorization':'Bearer '+key.strip(),'Content-Type':'application/json'},data=json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req,timeout=30) as r:answer=json.load(r)
        except urllib.error.HTTPError as e:raise SetupError('DeepSeek 模型调用失败，请检查 API Key、余额和账号权限。') from None
        except (OSError,ValueError):raise SetupError('DeepSeek 模型调用超时或响应异常，请重试。') from None
        if not any(t.get('function',{}).get('name')=='planner_connection_check' for t in answer.get('choices',[{}])[0].get('message',{}).get('tool_calls',[])):raise SetupError('DeepSeek 没有返回所需工具调用，配置尚未应用。')
        self.api('/v1/status')
        count=OpenClaw(self.root).configure_deepseek(key)
        self.api('/v1/settings','PUT',{'deepseek_api_key':key.strip(),'model':'deepseek-chat'})
        self.emit('wechat',f'{count} 个助手及子助手已设置 DeepSeek，未配置 ChatGPT 回退。请重启微信助手并发 /new。')
    def diagnose(self):
        lines=[]
        try:
            status=self.api('/v1/status');lines.append('电脑服务：正常；自动处理：'+('开启' if status['enabled'] else '关闭（请在手机设置开启）'))
            links=status.get('connections',{})
            lines.append('最近微信工具调用：'+str(links.get('openclaw') or '尚未收到'))
            lines.append('最近 HITA 连接：'+str(links.get('hita') or '尚未收到'))
        except (OSError,ValueError):lines.append('电脑服务：未连接，请启动')
        if self.address:
            try:
                with urllib.request.urlopen(self.address+'/healthz',timeout=15) as r:assert json.load(r)['status']=='ok'
                lines.append('手机 HTTPS 通道：正常')
            except Exception:lines.append('手机 HTTPS 通道：未能到达，请检查网络')
        else:lines.append('手机 HTTPS 通道：尚未取得地址')
        try:
            info=OpenClaw(self.root).inspect();lines.append('OpenClaw 保存工具：已加载；默认模型：'+str(info['primary']))
        except SetupError as e:lines.append(str(e))
        self.emit('diagnosis','\n'.join(lines))
        return lines
    def write_test(self):
        import uuid
        payload={'record_id':uuid.uuid4().hex,'kind':'memo','title':'电脑手机连接测试','body':'看到这条备忘，说明电脑服务到手机已经连通。','category':'连接测试','important':False,'urgent':False,'completed':False,'task_date':None}
        self.api('/v1/planner/records','POST',payload)
        self.emit('diagnosis','测试备忘已保存。请在手机“分类备忘”查看“电脑手机连接测试”。这一步不代表微信工具已调用。')
