import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from desktop.controller import Controller
from tools.openclaw_config import deepseek_agents,enable_tools,MODEL

class DesktopConfigTests(unittest.TestCase):
    def test_all_assistants_and_subagents_use_deepseek_without_chatgpt_fallback(self):
        original={'defaults':{'model':{'primary':'openai/gpt-6','fallbacks':['openai/gpt-5']},'models':{'openai/gpt-6':{'alias':'GPT'}},'subagents':{'maxConcurrent':3,'model':'openai/gpt-6'},'heartbeat':{'model':'openai/gpt-6'}},'list':[{'id':'main','model':'openai/gpt-6','tools':{'allow':['read']}},{'id':'helper','model':{'primary':'anthropic/claude','fallbacks':['openai/gpt-6']},'workspace':'D:/my workspace','subagents':{'model':'openai/gpt-6'}}]}
        before=copy.deepcopy(original);changed=deepseek_agents(original)
        self.assertEqual(before,original)
        for agent in [changed['defaults'],*changed['list']]:
            self.assertEqual(agent['model'],{'primary':MODEL,'fallbacks':[]})
            self.assertEqual(agent['subagents']['model'],{'primary':MODEL,'fallbacks':[]})
        self.assertEqual(changed['list'][1]['workspace'],'D:/my workspace')
        self.assertEqual(changed['defaults']['subagents']['maxConcurrent'],3)
        self.assertEqual(changed['defaults']['heartbeat']['model'],MODEL)
        self.assertEqual(set(changed['defaults']['models']),{MODEL})
        self.assertEqual(deepseek_agents(changed),changed)
    def test_current_openclaw_entries_schema_updates_every_agent(self):
        source={'entries':{'main':{'model':'openai/gpt-6'},'helper':{'subagents':{'model':'openai/gpt-6'}}}}
        updated=deepseek_agents(source)
        self.assertNotIn('list',updated)
        for agent in updated['entries'].values():
            self.assertEqual(agent['model']['primary'],MODEL)
            self.assertEqual(agent['subagents']['model']['primary'],MODEL)

    def test_agent_restrictions_are_fixed_without_removing_other_permissions(self):
        policy={'allow':['read'],'deny':['chat_planner_record','exec'],'profile':'messaging'}
        configured=enable_tools(policy)
        self.assertIn('chat_planner_record',configured['allow']);self.assertIn('chat_planner_status',configured['allow'])
        self.assertIn('read',configured['allow']);self.assertEqual(configured['deny'],['exec']);self.assertEqual(configured['profile'],'messaging')
        self.assertEqual(policy['deny'],['chat_planner_record','exec'])
        # A broad user deny remains a reported blocker, not permission for other plugins.
        self.assertEqual(enable_tools({'deny':['group:plugins']}),{'deny':['group:plugins']})
    def test_desktop_preserves_token_and_only_stops_its_own_processes(self):
        with tempfile.TemporaryDirectory() as d:
            c=Controller(Path(d));c.prepare();token=(Path(d)/'data/api-token').read_text()
            c.prepare();self.assertEqual((Path(d)/'data/api-token').read_text(),token)
            with patch('desktop.controller.subprocess.run') as run:
                c.stop();run.assert_not_called()
