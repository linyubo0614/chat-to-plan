"""Build a windowed standalone executable on Windows; no runtime Python required."""
from pathlib import Path
import subprocess,sys,os
root=Path(__file__).resolve().parents[1]
subprocess.run([sys.executable,'-m','PyInstaller','--noconfirm','--clean','--onefile','--windowed','--name','ChatPlanner','--distpath',str(root/'dist'), '--workpath',str(root/'.build-cache/pyinstaller'),'--specpath',str(root/'.build-cache'),'--add-data',str(root/'openclaw')+os.pathsep+'openclaw','--paths',str(root),'--collect-submodules','backend','--collect-submodules','uvicorn','--collect-submodules','desktop','--collect-data','tzdata',str(root/'desktop/app.py')],cwd=root,check=True)
