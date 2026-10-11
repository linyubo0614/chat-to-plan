"""Single-window Windows control app, also the frozen background service entry."""
import argparse
import os
from pathlib import Path
import queue
import sys
import threading


def serve(root):
    os.chdir(root)
    os.environ['ASSISTANT_API_TOKEN']=(root/'data/api-token').read_text().strip()
    os.environ['ASSISTANT_DB']=str(root/'data/assistant.db')
    import uvicorn
    from backend.app import create_app
    # Frozen windowed executables may have no std streams; use a protected local log.
    if sys.stdout is None:sys.stdout=open(root/'data/backend.log','a',encoding='utf-8')
    if sys.stderr is None:sys.stderr=sys.stdout
    uvicorn.run(create_app(),host='127.0.0.1',port=8000,access_log=False,timeout_graceful_shutdown=10)


def gui(root,check_ui=False):
    import tkinter as tk
    from tkinter import ttk,messagebox
    from desktop.controller import Controller
    from tools.openclaw_config import SetupError,find_command,command_args
    import subprocess
    events=queue.Queue();controller=Controller(root,lambda k,v:events.put((k,v)))
    window=tk.Tk();window.title('对话计划 · 电脑连接中心');window.geometry('780x720');window.minsize(650,620);window.configure(bg='#f6f5f0')
    style=ttk.Style();style.theme_use('clam');style.configure('TFrame',background='#f6f5f0');style.configure('TLabel',background='#f6f5f0',foreground='#243c3b',font=('Microsoft YaHei UI',11));style.configure('TButton',font=('Microsoft YaHei UI',10),padding=(12,9));style.configure('Title.TLabel',font=('Microsoft YaHei UI',24,'bold'));style.configure('TLabelframe',background='#f6f5f0');style.configure('TLabelframe.Label',background='#f6f5f0',font=('Microsoft YaHei UI',11,'bold'))
    frame=ttk.Frame(window,padding=24);frame.pack(fill='both',expand=True)
    ttk.Label(frame,text='对话计划',style='Title.TLabel').pack(anchor='w');ttk.Label(frame,text='微信记录 → 每日任务、分类备忘和 HITA',padding=(0,4)).pack(anchor='w')
    state=tk.StringVar(value='尚未启动');ttk.Label(frame,textvariable=state).pack(anchor='w',pady=8)
    busy=False
    buttons=[]
    def async_action(fn):
        nonlocal busy
        if busy:return
        busy=True
        for button in buttons:button.configure(state='disabled')
        def run():
            try:fn()
            except SetupError as e:events.put(('error',str(e)))
            except Exception:events.put(('error','操作未完成。请点击连接检查，并确认程序位于可写入的文件夹。'))
            finally:events.put(('done',''))
        threading.Thread(target=run,daemon=True).start()
    def add_button(parent,label,fn):
        b=ttk.Button(parent,text=label,command=lambda:async_action(fn));b.pack(side='left',padx=(0,8));buttons.append(b);return b
    row=ttk.Frame(frame);row.pack(fill='x',pady=8)
    add_button(row,'启动全部',controller.start);add_button(row,'停止',controller.stop);add_button(row,'连接检查',controller.diagnose)
    connection=ttk.LabelFrame(frame,text='手机连接',padding=14);connection.pack(fill='x',pady=8)
    address=tk.StringVar(value='启动后自动显示 HTTPS 地址');ttk.Entry(connection,textvariable=address,state='readonly').pack(fill='x')
    tunnel=tk.StringVar(value='手机通道未启动');ttk.Label(connection,textvariable=tunnel).pack(anchor='w',pady=5)
    row=ttk.Frame(connection);row.pack(fill='x')
    def copy(value):window.clipboard_clear();window.clipboard_append(value);window.update();state.set('已复制，请只填入你自己的手机 App')
    ttk.Button(row,text='复制地址',command=lambda:copy(controller.address) if controller.address else None).pack(side='left',padx=(0,8))
    def copy_token():
        try:copy((root/'data/api-token').read_text().strip())
        except OSError:messagebox.showinfo('连接口令','请先启动服务')
    ttk.Button(row,text='复制连接口令',command=copy_token).pack(side='left',padx=(0,8))
    add_button(row,'写入手机测试备忘',controller.write_test)
    claw=ttk.LabelFrame(frame,text='微信助手 · DeepSeek',padding=14);claw.pack(fill='x',pady=8)
    ttk.Label(claw,text='在这里填写 DeepSeek API Key，应用到全部助手和子助手。').pack(anchor='w')
    key=ttk.Entry(claw,show='•');key.pack(fill='x',pady=8)
    def save_key():
        value=key.get().strip();key.delete(0,'end')
        if not value:messagebox.showinfo('DeepSeek','请先填写 API Key');return
        async_action(lambda:controller.deepseek(value))
    row=ttk.Frame(claw);row.pack(fill='x')
    b=ttk.Button(row,text='验证并应用 DeepSeek',command=save_key);b.pack(side='left',padx=(0,8));buttons.append(b)
    add_button(row,'安装 / 修复保存工具',controller.install_tools);add_button(row,'重启微信助手',controller.restart_gateway)
    wechat=tk.StringVar(value='首次配置：启动全部 → 填 Key → 安装工具 → 重启助手 → 微信发 /new')
    ttk.Label(claw,textvariable=wechat,wraplength=660).pack(anchor='w',pady=(8,0))
    info=tk.Text(frame,height=7,bg='#ffffff',fg='#243c3b',relief='flat',font=('Microsoft YaHei UI',10),wrap='word');info.pack(fill='both',expand=True,pady=8)
    info.insert('end','手机 App 和 HITA 使用同一地址及口令。临时地址在通道重启后变化。\n关闭此程序会停止它启动的服务。无需保持终端窗口。')
    def install_cloudflare():
        from tools.openclaw_config import refreshed_path
        winget=find_command('winget')
        r=subprocess.run(command_args(winget,['install','--id','Cloudflare.cloudflared','--exact','--accept-package-agreements','--accept-source-agreements']),capture_output=True,creationflags=0x08000000 if os.name=='nt' else 0,env={**os.environ,'PATH':refreshed_path()},timeout=300)
        if r.returncode and not find_command('cloudflared'):raise SetupError('连接组件安装未完成，请通过 winget 安装 Cloudflare.cloudflared。')
        events.put(('state','连接组件已安装，请点击启动全部'))
    row=ttk.Frame(frame);row.pack(fill='x')
    add_button(row,'安装连接组件',install_cloudflare)
    ttk.Button(row,text='打开使用说明',command=lambda:__import__('webbrowser').open('https://github.com/linyubo0614/vibecoding_test_01/blob/main/docs/windows.md')).pack(side='left')
    def tick():
        nonlocal busy
        try:
            while True:
                kind,value=events.get_nowait()
                if kind=='done':
                    busy=False
                    for b in buttons:b.configure(state='normal')
                elif kind=='state':state.set(value)
                elif kind=='address':address.set(value)
                elif kind=='tunnel':tunnel.set(value)
                elif kind=='wechat':wechat.set(value)
                elif kind in ('error','diagnosis'):info.delete('1.0','end');info.insert('end',value)
        except queue.Empty:pass
        window.after(100,tick)
    def check_quit():
        if getattr(controller,'closed',False):window.destroy();return
        window.after(100,check_quit)
    def close():
        if busy:messagebox.showinfo('操作进行中','请等待当前操作完成后再关闭。');return
        if controller.running and not messagebox.askyesno('关闭对话计划','关闭后手机暂时无法接收新记录。确定停止并退出吗？'):return
        def stop():controller.stop();controller.closed=True
        async_action(stop)
    window.protocol('WM_DELETE_WINDOW',close);tick();check_quit()
    if check_ui:window.after(1000,window.destroy)
    window.mainloop()


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--root');parser.add_argument('--serve',action='store_true');parser.add_argument('--check-ui',action='store_true');parser.add_argument('--install-openclaw',action='store_true');args=parser.parse_args()
    root=Path(args.root).resolve() if args.root else Path(sys.executable).resolve().parent if getattr(sys,'frozen',False) else Path(__file__).resolve().parents[1]
    if args.serve:serve(root)
    elif args.install_openclaw:
        from tools.openclaw_config import OpenClaw
        OpenClaw(root).install()
    else:gui(root,args.check_ui)
if __name__=='__main__':main()
