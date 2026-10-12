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
    from tkinter import ttk,messagebox,filedialog
    import time
    from desktop.controller import Controller
    from tools.openclaw_config import SetupError
    events=queue.Queue();controller=Controller(root,lambda k,v:events.put((k,v)))
    window=tk.Tk();window.title('对话计划 · 电脑连接中心');window.geometry('820x800');window.minsize(720,690)
    style=ttk.Style();style.theme_use('clam');style.configure('TButton',font=('Microsoft YaHei UI',10),padding=(10,8));style.configure('TLabel',font=('Microsoft YaHei UI',10));style.configure('Title.TLabel',font=('Microsoft YaHei UI',22,'bold'))
    window.configure(background='#f5f6f2')
    style.configure('TFrame',background='#f5f6f2')
    style.configure('TLabel',background='#f5f6f2',foreground='#243c3b')
    style.configure('TButton',background='#e8efea',foreground='#29796b',borderwidth=0,relief='flat')
    style.map('TButton',background=[('active','#dce8df'),('disabled','#eef0eb')],foreground=[('disabled','#8c9b94')])
    style.configure('TLabelframe',background='#f5f6f2',bordercolor='#dfe5dc',relief='solid')
    style.configure('TLabelframe.Label',background='#f5f6f2',foreground='#243c3b',font=('Microsoft YaHei UI',11,'bold'))
    style.configure('TCheckbutton',background='#f5f6f2',foreground='#243c3b')
    frame=ttk.Frame(window,padding=20);frame.pack(fill='both',expand=True)
    ttk.Label(frame,text='对话计划',style='Title.TLabel').pack(anchor='w')
    ttk.Label(frame,text='微信 / QQ → DeepSeek Harness → 任务、备忘与 HITA').pack(anchor='w',pady=6)
    state=tk.StringVar(value='准备就绪');ttk.Label(frame,textvariable=state).pack(anchor='w',pady=6)
    buttons=[];busy=False;pair_window=None
    def async_action(fn):
        nonlocal busy
        if busy:return
        busy=True
        for b in buttons:b.configure(state='disabled')
        def run():
            try:fn()
            except SetupError as e:events.put(('error',str(e)))
            except Exception:events.put(('error','操作未完成，请检查网络、程序文件和 data 文件夹是否可写。'))
            finally:events.put(('done',''))
        threading.Thread(target=run,daemon=True).start()
    def button(parent,label,fn):
        b=ttk.Button(parent,text=label,command=lambda:async_action(fn));b.pack(side='left',padx=(0,8));buttons.append(b);return b
    def section(title):
        box=ttk.LabelFrame(frame,text=title,padding=12);box.pack(fill='x',pady=7);return box
    connection=section('1 · 固定手机连接（首次配置）')
    ttk.Label(connection,text='无需域名。首次在浏览器登录 Cloudflare，部署你自己的免费中转。').pack(anchor='w')
    address=tk.StringVar(value='尚未配置固定地址')
    ttk.Entry(connection,textvariable=address,state='readonly').pack(fill='x',pady=7)
    row=ttk.Frame(connection);row.pack(fill='x')
    button(row,'配置 / 更新免费中转',controller.setup_relay)
    button(row,'启动服务',controller.start);button(row,'停止服务',controller.stop)
    tunnel=tk.StringVar(value='首次先配置中转，再启动服务。之后打开本软件会自动启动。')
    ttk.Label(connection,textvariable=tunnel,wraplength=730).pack(anchor='w',pady=(7,0))
    phone=section('2 · 手机扫码（只需一次）')
    ttk.Label(phone,text='手机打开“对话计划 → 设置 → 扫码连接电脑”，配对后自动保存。').pack(anchor='w')
    ttk.Label(phone,text='再点手机里的“一键配置 HITA 连接”。电脑重启无需重新配对。').pack(anchor='w')
    row=ttk.Frame(phone);row.pack(fill='x',pady=(8,0))
    button(row,'显示手机配对二维码',controller.pairing)
    button(row,'连接检查',controller.diagnose)
    button(row,'写入手机测试备忘',controller.write_test)
    harness=section('3 · 接入已有 DeepSeek Harness')
    ttk.Label(harness,text='保留现有 dsh-im、微信、QQ 和模型；只追加计划工具。',wraplength=730).pack(anchor='w')
    row=ttk.Frame(harness);row.pack(fill='x',pady=7)
    button(row,'生成 Harness 接入配置',controller.export_harness)
    def install_harness():
        default=Path(os.getenv('DSH_HOME',str(Path.home()/'.dsh')))/'profiles/web/cordis.patch.yml'
        path=filedialog.asksaveasfilename(title='选择现有 Harness profile 的 cordis.patch.yml（保留原内容）',
            initialdir=str(default.parent),initialfile='cordis.patch.yml',defaultextension='.yml',confirmoverwrite=False)
        if not path:return
        def install():
            controller.prepare()
            from tools.harness_config import install_patch
            install_patch(root,path)
            events.put(('diagnosis','计划工具已加入所选 Harness profile。请重新启动原来的 Harness，再在微信或 QQ 测试记录。旧配置备份在 data 中。'))
        async_action(install)
    b=ttk.Button(row,text='加入 Harness 配置',command=install_harness);b.pack(side='left');buttons.append(b)
    info=tk.Text(frame,height=6,wrap='word',font=('Microsoft YaHei UI',10),background='white',foreground='#243c3b',relief='flat',padx=12,pady=12);info.pack(fill='both',expand=True,pady=8)
    info.insert('end','首次：配置中转 → 启动服务 → 手机扫码 → 加入 Harness 配置。\n日常：保持电脑联网、此软件和原 Harness 运行；断线会自动重连。\n保留整个 data 文件夹，升级无需重新扫码。')
    def show_pair(data):
        nonlocal pair_window
        import qrcode
        if pair_window is not None and pair_window.winfo_exists():pair_window.destroy()
        pair_window=tk.Toplevel(window);dialog=pair_window;dialog.title('手机配对');dialog.resizable(False,False)
        qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,border=4);qr.add_data(data['link']);qr.make(fit=True)
        matrix=qr.get_matrix();scale=max(3,min(7,440//len(matrix)));size=len(matrix)*scale
        canvas=tk.Canvas(dialog,width=size,height=size,bg='white',highlightthickness=0);canvas.pack(padx=16,pady=16)
        for y,line in enumerate(matrix):
            for x,dark in enumerate(line):
                if dark:canvas.create_rectangle(x*scale,y*scale,(x+1)*scale,(y+1)*scale,fill='black',outline='')
        label=ttk.Label(dialog,text='');label.pack(pady=6)
        def copy_link():window.clipboard_clear();window.clipboard_append(data['link']);window.update()
        copy=ttk.Button(dialog,text='复制配对链接（手机也可粘贴）',command=copy_link);copy.pack(pady=8)
        def countdown():
            if not dialog.winfo_exists():return
            remaining=max(0,int(data['expires_at']-time.time()))
            label.configure(text=f'仅首次配对需要扫描，{remaining} 秒内有效。' if remaining else '二维码已过期，请关闭后重新生成。')
            if remaining:dialog.after(1000,countdown)
            else:canvas.delete('all');copy.configure(state='disabled')
        countdown()
    row=ttk.Frame(frame);row.pack(fill='x')
    ttk.Button(row,text='使用说明',command=lambda:__import__('webbrowser').open('https://github.com/linyubo0614/vibecoding_test_01/blob/main/docs/windows.md')).pack(side='left')
    if os.name=='nt' and getattr(sys,'frozen',False):
        auto=tk.BooleanVar(value=False)
        import winreg
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER,r'Software\Microsoft\Windows\CurrentVersion\Run') as key:
            try:auto.set(bool(winreg.QueryValueEx(key,'ChatPlanner')[0]))
            except FileNotFoundError:pass
        def startup():
            import subprocess
            with winreg.CreateKey(winreg.HKEY_CURRENT_USER,r'Software\Microsoft\Windows\CurrentVersion\Run') as key:
                if auto.get():winreg.SetValueEx(key,'ChatPlanner',0,winreg.REG_SZ,subprocess.list2cmdline([sys.executable,'--root',str(root)]))
                else:
                    try:winreg.DeleteValue(key,'ChatPlanner')
                    except FileNotFoundError:pass
        ttk.Checkbutton(row,text='Windows 登录后自动启动',variable=auto,command=startup).pack(side='left',padx=12)
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
                elif kind=='pairing':show_pair(value)
                elif kind in ('error','diagnosis','wechat'):info.delete('1.0','end');info.insert('end',value)
        except queue.Empty:pass
        if getattr(controller,'closed',False):window.destroy();return
        window.after(100,tick)
    def close():
        if busy:messagebox.showinfo('操作进行中','请等待当前操作完成。');return
        if controller.running and not messagebox.askyesno('停止并退出','退出后手机暂停连接，重新打开会自动恢复。确定退出吗？'):return
        def stop():controller.stop();controller.closed=True
        async_action(stop)
    window.protocol('WM_DELETE_WINDOW',close);tick()
    if check_ui:window.after(1000,window.destroy)
    elif (root/'data/relay.json').exists():window.after(200,lambda:async_action(controller.start))
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
