import json
from pathlib import Path
import tempfile
import unittest
from desktop.controller import Controller
from desktop.relay import relay_url
from tools.openclaw_config import SetupError

class CustomRelayTests(unittest.TestCase):
    def test_https_domain_validation(self):
        self.assertEqual(relay_url('https://planner.example.com/'),'https://planner.example.com')
        self.assertEqual(relay_url('https://test.example.workers.dev'),'https://test.example.workers.dev')
        for bad in ['http://planner.example.com','https://user:secret@example.com','https://example.com/healthz','https://example.com/?token=x','https://example.com:8443','https://example.com/#x']:
            with self.assertRaises(ValueError):relay_url(bad)

    def test_domain_switch_preserves_identity_and_can_restore_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'data').mkdir()
            old={'name':'chat-planner-aaaaaaaaaaaa','secret':'fake-secret','address':'https://old.example.workers.dev'}
            (root/'data/relay.json').write_text(json.dumps(old))
            controller=Controller(root)
            controller.set_custom_relay('https://planner.example.com')
            saved=json.loads((root/'data/relay.json').read_text(encoding='utf-8'))
            for key in old:self.assertEqual(old[key],saved[key])
            self.assertEqual(saved['custom_address'],'https://planner.example.com')
            self.assertEqual(controller.address,saved['custom_address'])
            controller.running=True
            with self.assertRaises(SetupError):controller.set_custom_relay('')
            controller.running=False;controller.set_custom_relay('')
            self.assertEqual(controller.address,old['address'])
