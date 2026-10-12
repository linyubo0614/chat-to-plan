"""Real CLI pipe behaviour, timeout/cancel and secret-safe diagnostics."""
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from tools.openclaw_config import SetupError
from tools.relay_setup import deploy, run_command, sanitize, npx_args
from tools.openclaw_config import find_command


class RelaySetupTests(unittest.TestCase):
    def run_cli(self,script,**kwargs):
        with tempfile.TemporaryDirectory() as root:
            reports=[];events=[]
            result=run_command([sys.executable,'-u','-c',script],root,os.environ.copy(),'部署',reports.append,lambda *event:events.append(event),kwargs.pop('cancel_event',threading.Event()),**kwargs)
            return result,reports

    def test_native_npx_launches_npm_cli(self):
        with tempfile.TemporaryDirectory() as folder:
            result=run_command(npx_args(find_command('npx'),['--version']),folder,os.environ.copy(),'npx',lambda line:None,lambda *event:None,threading.Event(),timeout=20,idle_timeout=15)
            self.assertRegex(result.strip(),r'^\d+\.\d+\.\d+$')

    def test_stdout_stderr_and_secret_stdin_are_drained_without_console(self):
        result,reports=self.run_cli("import sys; print('uploaded',flush=True); print('warning',file=sys.stderr,flush=True); print('input length',len(sys.stdin.read()))",input='fake-secret\n')
        self.assertIn('uploaded',result);self.assertIn('warning',result);self.assertIn('input length 12',result)
        self.assertTrue(any('uploaded' in line for line in reports))
        result,_=self.run_cli("import sys; print('stdin EOF',repr(sys.stdin.read()))")
        self.assertIn("stdin EOF ''",result)

    def test_timeout_and_cancel_terminate_a_real_sleeping_process(self):
        start=time.monotonic()
        with self.assertRaisesRegex(SetupError,'超时'):
            self.run_cli('import time; time.sleep(60)',timeout=2,idle_timeout=0.3)
        self.assertLess(time.monotonic()-start,12)
        cancelled=threading.Event();timer=threading.Timer(0.2,cancelled.set);timer.start()
        try:
            with self.assertRaisesRegex(SetupError,'已取消'):
                self.run_cli('import time; time.sleep(60)',cancel_event=cancelled)
        finally:timer.join()

    def test_cloudflare_errors_are_specific_and_never_report_fake_credentials(self):
        for message,hint in [('More than one account available but unable to select one','多个'),('You need to register a workers.dev subdomain','子域名'),('fetch failed: ECONNRESET','网络')]:
            script="import sys; print("+repr(message)+"); print('Authorization: Bearer fake-sensitive-token'); sys.exit(2)"
            with self.assertRaises(SetupError) as result:self.run_cli(script)
            self.assertIn(hint,str(result.exception));self.assertNotIn('fake-sensitive-token',str(result.exception))
        value=sanitize('https://dash.cloudflare.com/oauth2/auth?code=private-code&state=private-state\n{"access_token":"short-secret"}\nbridge-secret-private',secret='bridge-secret-private')
        for private in ['private-code','private-state','short-secret','bridge-secret-private']:self.assertNotIn(private,value)

    def test_redeploy_retains_custom_domain(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'data').mkdir();(root/'relay').mkdir()
            (root/'relay/worker.mjs').write_text('test');(root/'relay/wrangler.toml').write_text('name = "chat-planner-relay"\nworkers_dev = true')
            old={'name':'chat-planner-'+'a'*12,'secret':'fake-known-secret','address':'https://old.example.workers.dev','custom_address':'https://planner.example.com'}
            (root/'data/relay.json').write_text(json.dumps(old))
            with patch('tools.relay_setup.find_command',return_value='npx'),patch('tools.relay_setup.run_command',return_value='https://new.example.workers.dev'):
                self.assertEqual(deploy(root,lambda *e:None),'https://planner.example.com')
            saved=json.loads((root/'data/relay.json').read_text(encoding='utf-8'))
            self.assertEqual(saved['custom_address'],old['custom_address']);self.assertEqual(saved['secret'],old['secret'])
            self.assertIn('custom_domain = true',(root/'data/relay-worker/wrangler.toml').read_text(encoding='utf-8'))
            self.assertIn('planner.example.com',(root/'data/relay-worker/wrangler.toml').read_text(encoding='utf-8'))

    def test_steps_log_progress_and_retain_original_worker_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'data').mkdir();(root/'relay').mkdir()
            (root/'relay/worker.mjs').write_text('test')
            (root/'relay/wrangler.toml').write_text('name = "chat-planner-relay"')
            original={'name':'chat-planner-'+'a'*12,'secret':'fake-known-secret','address':'https://old.example.workers.dev'}
            (root/'data/relay.json').write_text(json.dumps(original))
            events=[];commands=[]
            def cli(command,folder,env,stage,report,emit,cancel_event,**kwargs):
                commands.append(command);report('Authorization: Bearer private-diagnostic-token');report('fake-known-secret')
                if 'deploy' in command:return 'Uploaded worker\nhttps://new.example.workers.dev\n'
                return 'ok'
            with patch('tools.relay_setup.find_command',return_value='npx'),patch('tools.relay_setup.run_command',side_effect=cli):
                self.assertEqual(deploy(root,lambda *e:events.append(e)),'https://new.example.workers.dev')
            saved=json.loads((root/'data/relay.json').read_text(encoding="utf-8"))
            self.assertEqual(saved['name'],original['name']);self.assertEqual(saved['secret'],original['secret'])
            self.assertEqual(len(commands),4)
            log=(root/'data/relay-setup.log').read_text(encoding="utf-8")
            self.assertNotIn('private-diagnostic-token',log);self.assertNotIn('fake-known-secret',log)
            self.assertTrue(any(e[0]=='setup_log' for e in events))
            def fail(*args,**kwargs):raise SetupError('test deployment failure')
            with patch('tools.relay_setup.find_command',return_value='npx'),patch('tools.relay_setup.run_command',side_effect=fail):
                with self.assertRaisesRegex(SetupError,'relay-setup.log'):deploy(root,lambda *e:None)
            self.assertEqual(json.loads((root/'data/relay.json').read_text(encoding="utf-8")),saved)
