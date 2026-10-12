import copy
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

from backend.app import create_app
from backend.config import Settings
from harness.server import PlannerTools, dispatch
from tools.harness_config import install_patch


class HarnessPairingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root/'data').mkdir()
        (self.root/'data/api-token').write_text('a'*32)
        self.settings = Settings(api_token='a'*32,db_path=self.root/'data/assistant.db')
        class NoSecondModel:
            def parse(self, *args): raise AssertionError('Must not call a second model')
        self.app = create_app(self.settings,NoSecondModel(),run_worker=False)
        self.client = TestClient(self.app)
        self.tools = PlannerTools(self.root,self.client)
        self.headers = {'Authorization':'Bearer '+'a'*32}
        self.args = {'operation_id':'test-operation-12345678','text':'记下作业和高数课','interpretation':{'items':[
            {'kind':'todo','title':'交作业','task_date':'2026-10-12'},
            {'kind':'course','title':'高数','starts_at':'2026-10-12T15:00:00+08:00','ends_at':'2026-10-12T16:00:00+08:00'}]}}

    def tearDown(self):
        self.client.close();self.tmp.cleanup()

    def ticket(self):
        response = self.client.post('/v1/pairings',headers=self.headers,json={})
        self.assertEqual(response.status_code,201)
        self.assertEqual(response.headers['cache-control'],'no-store')
        return response.json()['code']

    def test_ticket_is_authenticated_expiring_and_single_use(self):
        self.assertEqual(self.client.post('/v1/pairings',json={}).status_code,401)
        code=self.ticket()
        with ThreadPoolExecutor(max_workers=6) as pool:
            replies=list(pool.map(lambda _:self.client.post('/v1/pairings/claim',json={'code':code}),range(6)))
        self.assertEqual(sum(r.status_code==200 for r in replies),1)
        self.assertEqual(sum(r.status_code==410 for r in replies),5)
        code=self.ticket()
        with patch('backend.pairing.time.time',return_value=10**12):
            self.assertEqual(self.client.post('/v1/pairings/claim',json={'code':code}).status_code,410)

    def test_device_token_survives_restart_can_pair_hita_and_can_be_revoked(self):
        first=self.client.post('/v1/pairings/claim',json={'code':self.ticket(),'label':'phone'}).json()
        headers={'Authorization':'Bearer '+first['token']}
        self.assertNotEqual(first['token'],self.settings.api_token)
        with TestClient(create_app(self.settings,run_worker=False)) as restarted:
            self.assertEqual(restarted.get('/v1/status',headers=headers).status_code,200)
            code=restarted.post('/v1/pairings',headers=headers,json={'label':'HITA'}).json()['code']
            hita=restarted.post('/v1/pairings/claim',json={'code':code,'label':'HITA'}).json()
        self.client.delete('/v1/devices/'+first['device_id'],headers=self.headers)
        self.assertEqual(self.client.get('/v1/status',headers=headers).status_code,401)
        self.assertEqual(self.client.get('/v1/status',headers={'Authorization':'Bearer '+hita['token']}).status_code,200)
        listed=self.client.get('/v1/devices',headers=self.headers).json()
        self.assertNotIn(hita['token'],json.dumps(listed))
        with self.app.state.store.connection() as db:
            rows=str([tuple(r) for r in db.execute('SELECT * FROM devices')])
            self.assertNotIn(hita['token'],rows)

    def test_refresh_invalidates_old_code(self):
        old=self.ticket();new=self.ticket()
        self.assertEqual(self.client.post('/v1/pairings/claim',json={'code':old}).status_code,410)
        self.assertEqual(self.client.post('/v1/pairings/claim',json={'code':new}).status_code,200)

    def test_mcp_save_retry_hita_receipt_without_second_model(self):
        result=self.tools.call('chat_planner_record',self.args)
        self.assertFalse(result['isError']);data=result['structuredContent']
        self.assertEqual([t['status'] for t in data['tasks']],['written','blocked'])
        self.assertEqual(self.app.state.store.get_message(data['id'],'owner')['source'],'harness')
        retry=self.tools.call('chat_planner_record',self.args)['structuredContent']
        self.assertTrue(retry['duplicate']);self.assertEqual(data['id'],retry['id'])
        claimed=self.client.post('/v1/delivery/hita/claim',headers=self.headers,json={'client_id':'test-phone'}).json()
        self.client.post('/v1/delivery/'+claimed['task']['id']+'/ack',headers=self.headers,json={
            'client_id':'test-phone','lease_token':claimed['lease_token'],'revision':claimed['task']['revision'],'external_id':'test-readback'})
        status=self.tools.call('chat_planner_status',{'message_id':data['id']})['structuredContent']
        self.assertEqual(status['tasks'][1]['status'],'written')
        self.assertIn('harness',self.tools.call('chat_planner_status',{})['structuredContent']['connections'])
        self.assertFalse(self.app.state.worker.run_once())

    def test_invalid_conflicting_paused_and_timeout_are_not_success(self):
        for args in ({},dict(self.args,message_id='override'),dict(self.args,operation_id='short')):
            self.assertTrue(self.tools.call('chat_planner_record',args)['isError'])
        self.tools.call('chat_planner_record',self.args)
        changed=copy.deepcopy(self.args);changed['text']='different'
        response=self.tools.call('chat_planner_record',changed)
        self.assertTrue(response['isError']);self.assertEqual(response['structuredContent']['http_status'],409)
        self.client.put('/v1/settings',headers=self.headers,json={'enabled':False})
        self.assertEqual(self.tools.call('chat_planner_record',self.args)['structuredContent']['http_status'],423)
        with patch.object(self.tools,'request',side_effect=httpx.ReadTimeout('sensitive internal detail')):
            response=self.tools.call('chat_planner_record',self.args)
        self.assertTrue(response['isError']);self.assertEqual(response['structuredContent']['operation_id'],self.args['operation_id'])
        self.assertNotIn('sensitive',json.dumps(response))

    def test_protocol_negotiation_and_notifications_do_not_write(self):
        value=dispatch({'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2026-07-28'}},self.tools)
        self.assertEqual(value['result']['protocolVersion'],'2025-11-25')
        with patch.object(self.tools,'call') as call:
            self.assertIsNone(dispatch({'jsonrpc':'2.0','method':'tools/call','params':{'name':'chat_planner_record','arguments':self.args}},self.tools))
            call.assert_not_called()
        self.assertEqual(len(dispatch({'jsonrpc':'2.0','id':2,'method':'tools/list'},self.tools)['result']['tools']),2)

    def test_install_patch_preserves_existing_js_and_is_repeatable(self):
        target=self.root/'profile/cordis.patch.yml';target.parent.mkdir()
        original="- insert:\n    - id: dsh-im\n      name: '@xmanrui/dsh-im'\n      config:\n        value: !!js process.env.EXAMPLE\n"
        target.write_text(original)
        install_patch(self.root,target)
        first=target.read_text();self.assertTrue(first.startswith(original))
        install_patch(self.root,target)
        self.assertEqual(first,target.read_text())
        self.assertEqual(first.count('mcp-chat-planner'),1)
        self.assertNotIn('a'*32,first)
        self.assertEqual(next((self.root/'data').glob('harness-patch-backup*')).read_text(),original)


if __name__=='__main__':unittest.main()
