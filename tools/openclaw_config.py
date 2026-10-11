"""Local OpenClaw integration. Secrets never appear in CLI arguments or diagnostics."""
from __future__ import annotations
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

TOOLS = ['chat_planner_record', 'chat_planner_status']
MODEL = 'deepseek/deepseek-chat'

class SetupError(RuntimeError):
    pass

def refreshed_path():
    paths=[os.environ.get('PATH','')]
    if os.name=='nt':
        import winreg
        for hive,key in [(winreg.HKEY_LOCAL_MACHINE,r'SYSTEM\CurrentControlSet\Control\Session Manager\Environment'),(winreg.HKEY_CURRENT_USER,r'Environment')]:
            try:
                with winreg.OpenKey(hive,key) as k: paths.append(os.path.expandvars(winreg.QueryValueEx(k,'Path')[0]))
            except OSError: pass
        paths.extend([str(Path(os.environ.get('APPDATA',''))/'npm'),str(Path(os.environ.get('LOCALAPPDATA',''))/'Microsoft/WinGet/Links')])
    return os.pathsep.join(paths)

def find_command(name):
    path=shutil.which(name,path=refreshed_path())
    if path:return path
    if name=='cloudflared' and os.name=='nt':
        for base in [Path(os.environ.get('ProgramFiles',r'C:\Program Files'))/'cloudflared',Path(os.environ.get('LOCALAPPDATA',''))/'Microsoft/WinGet/Packages']:
            if base.is_dir():
                for item in base.glob('**/cloudflared.exe'):return str(item)
    raise SetupError('未找到 OpenClaw。请确认微信机器人在 Windows 原生运行；WSL 版本需要单独接入。' if name=='openclaw' else '未找到 Cloudflare。请点击“安装连接组件”，安装完成后再启动。')

def command_args(command,arguments):
    # No secret is an argument. cmd wrappers are only npm-installed executables.
    return [os.environ.get('COMSPEC','cmd.exe'),'/d','/c',subprocess.list2cmdline([command,*arguments])] if os.name=='nt' and command.lower().endswith(('.cmd','.bat')) else [command,*arguments]

class OpenClaw:
    def __init__(self,root,command=None):
        self.root=Path(root).resolve();self.command=command or find_command('openclaw')
    def run(self,*args,timeout=120):
        try:
            result=subprocess.run(command_args(self.command,list(args)),cwd=self.root,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout,creationflags=0x08000000 if os.name=='nt' else 0,env={**os.environ,'PATH':refreshed_path()})
        except (OSError,subprocess.TimeoutExpired):raise SetupError('OpenClaw 启动失败或超时，请检查它是否正常运行。') from None
        return result
    def get(self,path,default=None):
        r=self.run('config','get',path,'--json')
        if r.returncode:
            if 'unset:' in r.stdout+r.stderr or 'Config path not found:' in r.stdout+r.stderr:return copy.deepcopy(default)
            raise SetupError(f'无法读取 OpenClaw {path}，原配置未覆盖。')
        try:return json.loads(r.stdout)
        except ValueError:raise SetupError('OpenClaw 返回的配置格式不兼容，请提供版本号。') from None
    def apply(self,batch):
        self.root.joinpath('data').mkdir(exist_ok=True)
        fd,path=tempfile.mkstemp(prefix='openclaw-',suffix='.batch.json',dir=self.root/'data')
        try:
            with os.fdopen(fd,'w',encoding='utf-8') as f:json.dump(batch,f,ensure_ascii=False)
            dry=self.run('config','set','--batch-file',path,'--dry-run')
            if dry.returncode:raise SetupError('OpenClaw 不支持或拒绝此配置；没有应用修改。请提供 OpenClaw 版本号。')
            actual=self.run('config','set','--batch-file',path)
            if actual.returncode:raise SetupError('OpenClaw 配置保存失败，请重新运行连接检查。')
        finally:Path(path).unlink(missing_ok=True)
    def plugin_path(self):
        path=self.root/'openclaw'
        if path.is_dir():return path
        import sys
        bundled=Path(getattr(sys,'_MEIPASS',str(self.root)))/'openclaw'
        if bundled.is_dir():return bundled
        raise SetupError('软件缺少微信保存工具，请下载完整 Windows 包。')
    def install(self):
        token=self.root/'data/api-token'
        if not token.exists():raise SetupError('请先启动电脑服务。')
        r=self.run('plugins','install',str(self.plugin_path()),'--force','--accept-capabilities','--no-enable',timeout=180)
        if r.returncode:raise SetupError('OpenClaw 工具安装失败，请检查版本及安装权限。')
        plugins=self.get('plugins.allow',[])
        if 'chat-planner' not in plugins:plugins.append('chat-planner')
        tools=enable_tools(self.get('tools',{}))
        agents=self.get('agents',{})
        for _,agent in agent_values(agents):agent['tools']=enable_tools(agent.get('tools',{}))
        batch=[{'path':'plugins.allow','value':plugins},{'path':'plugins.entries.chat-planner.enabled','value':True},{'path':'plugins.entries.chat-planner.config.baseUrl','value':'http://127.0.0.1:8000'},{'path':'plugins.entries.chat-planner.config.tokenFile','value':str(token)},{'path':'tools','value':tools}]
        if list(agent_values(agents)):batch.append({'path':'agents','value':agents})
        self.apply(batch)
        self.inspect()
    def inspect(self):
        r=self.run('plugins','inspect','chat-planner','--runtime','--json')
        try:plugin=json.loads(r.stdout)['plugin']
        except (ValueError,KeyError):raise SetupError('OpenClaw 工具检查失败，不能确认微信可以调用保存工具。') from None
        if r.returncode or plugin.get('status')!='loaded' or not set(TOOLS)<=set(plugin.get('toolNames',[])):raise SetupError('保存工具尚未加载，请重新安装并重启 OpenClaw。')
        agents=self.get('agents',{})
        restricted=[]
        for name,agent in agent_values(agents):
            policy=agent.get('tools',{})
            if any(name in policy.get('deny',[]) for name in [*TOOLS,'group:plugins']):restricted.append(name)
        policy=self.get('tools',{})
        if any(name in policy.get('deny',[]) for name in [*TOOLS,'group:plugins']):restricted.append('全局工具策略')
        if restricted:raise SetupError('保存工具被禁止：'+', '.join(restricted)+'。请在 OpenClaw 中移除对应禁止项。')
        return {'tools_loaded':True,'agent_count':len(list(agent_values(agents))) or 1,'primary':agents.get('defaults',{}).get('model',{})}
    def configure_deepseek(self,key):
        if not key.strip() or '\n' in key or len(key)>512:raise SetupError('请填写有效的 DeepSeek API Key。')
        agents=deepseek_agents(self.get('agents',{}))
        secrets=self.get('secrets.providers',{})
        secretfile=self.root/'data/openclaw-deepseek.json'
        secrets['planner-deepseek']={'source':'file','path':str(secretfile),'mode':'json'}
        provider={'baseUrl':'https://api.deepseek.com','api':'openai-completions','apiKey':{'source':'file','provider':'planner-deepseek','id':'/key'},'models':[{'id':'deepseek-chat','name':'DeepSeek Chat','reasoning':False,'input':['text'],'contextWindow':128000,'maxTokens':8192}]}
        original=secretfile.read_bytes() if secretfile.exists() else None
        fd=os.open(secretfile,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
        with os.fdopen(fd,'w',encoding='utf-8') as f:json.dump({'key':key.strip()},f)
        try:self.apply([{'path':'secrets.providers','value':secrets},{'path':'models.providers.deepseek','value':provider},{'path':'agents','value':agents}])
        except Exception:
            if original is None:secretfile.unlink(missing_ok=True)
            else:secretfile.write_bytes(original)
            raise
        configured=self.get('agents',{})
        if deepseek_agents(configured)!=configured:raise SetupError('部分助手的模型设置未生效，请重新检查。')
        return len(list(agent_values(configured))) or 1
    def restart(self):
        r=self.run('gateway','restart',timeout=60)
        if r.returncode:raise SetupError('未找到可重启的 OpenClaw 后台服务。请关闭原 OpenClaw 窗口，再点击“启动微信助手”。')

def enable_tools(policy):
    policy=copy.deepcopy(policy)
    if 'group:plugins' in policy.get('deny',[]):return policy
    policy['deny']=[t for t in policy.get('deny',[]) if t not in TOOLS]
    if not policy['deny']:policy.pop('deny')
    path='allow' if policy.get('allow') else 'alsoAllow'
    policy[path]=list(dict.fromkeys([*policy.get(path,[]),*TOOLS]))
    return policy

def agent_values(agents):
    for name,value in agents.get('entries',{}).items():
        if isinstance(value,dict):yield name,value
    for value in agents.get('list',[]):
        if isinstance(value,dict):yield value.get('id','unknown'),value

def deepseek_agents(agents):
    agents=copy.deepcopy(agents)
    def change(agent):
        agent['model']={'primary':MODEL,'fallbacks':[]}
        agent.setdefault('subagents',{})['model']={'primary':MODEL,'fallbacks':[]}
        if isinstance(agent.get('heartbeat'),dict) and 'model' in agent['heartbeat']:agent['heartbeat']['model']=MODEL
        # Restrict selectable chat models; old credentials remain intact for rollback.
        if 'models' in agent:agent['models']={MODEL:agent['models'].get(MODEL,{})}
        if isinstance(agent.get('compaction'),dict) and 'model' in agent['compaction']:agent['compaction']['model']=MODEL
    change(agents.setdefault('defaults',{}))
    agents['defaults']['models']={MODEL:agents['defaults'].get('models',{}).get(MODEL,{'alias':'DeepSeek'})}
    for _,agent in agent_values(agents):change(agent)
    return agents

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--root',default='.');parser.add_argument('--install',action='store_true');args=parser.parse_args()
    try:
        claw=OpenClaw(args.root)
        if args.install:claw.install();print('OPENCLAW_PLANNER_INSTALLED: tools loaded for all configured agents. Restart the gateway.')
        else:print(json.dumps(claw.inspect(),ensure_ascii=False))
    except SetupError as e:print(str(e));raise SystemExit(1)
if __name__=='__main__':main()
