import copy
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from backend.app import create_app
from backend.config import Settings


class NoSecondModel:
    def parse(self, *args):
        raise AssertionError("OpenClaw plans must not call another model")


class OpenClawTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.settings = Settings(api_token="a"*32,db_path=Path(self.tmp.name)/"db")
        self.app = create_app(self.settings,NoSecondModel(),run_worker=False)
        self.client = TestClient(self.app)
        self.headers = {"Authorization":"Bearer "+self.settings.api_token}
        self.store = self.app.state.store
        self.plan = {"message_id":"operation-one","text":"记录明天15点到16点的高数，记下买纸",
                     "interpretation":{"items":[
                         {"kind":"course","title":"高数","category":"学习",
                          "starts_at":"2026-10-12T15:00:00+08:00","ends_at":"2026-10-12T16:00:00+08:00"},
                         {"kind":"memo","title":"买纸","category":"生活"}]}}

    def tearDown(self):
        self.client.close()
        self.tmp.cleanup()

    def submit(self, plan=None):
        return self.client.post("/v1/openclaw/plans",headers=self.headers,json=plan or self.plan)

    def test_authentication_validation_and_pause_have_no_effect(self):
        self.assertEqual(self.client.post("/v1/openclaw/plans",json=self.plan).status_code,401)
        bad = copy.deepcopy(self.plan)
        bad["interpretation"]["items"][0]["starts_at"] = "2026-10-12T15:00:00"
        self.assertEqual(self.submit(bad).status_code,422)
        bad = copy.deepcopy(self.plan)
        bad["interpretation"]["items"][0]["unexpected"] = "ignored?"
        self.assertEqual(self.submit(bad).status_code,422)
        self.client.put("/v1/settings",headers=self.headers,json={"enabled":False})
        self.assertEqual(self.submit().status_code,423)
        self.assertEqual(self.store.list_messages("owner")["messages"],[])
        self.client.put("/v1/settings",headers=self.headers,json={"enabled":True})
        self.assertEqual(self.submit().status_code,201)

    def test_validated_ingress_no_second_model_and_restart(self):
        result = self.submit().json()
        self.assertEqual(result["message"]["source"],"openclaw")
        self.assertEqual(result["message"]["status"],"parsed")
        self.assertEqual(len(result["message"]["tasks"]),2)
        self.assertFalse(self.app.state.worker.run_once())
        restored = create_app(self.settings,NoSecondModel(),run_worker=False)
        self.assertEqual(restored.state.store.get_message(result["id"],"owner")["status"],"parsed")
        self.assertIn("+08:00",self.client.get("/v1/status",headers=self.headers).json()["server_time"])

    def test_concurrent_retry_and_conflicting_contents(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            responses = list(pool.map(lambda _:self.submit(),range(12)))
        self.assertEqual(sum(r.status_code==201 for r in responses),1)
        self.assertEqual(len({r.json()["id"] for r in responses}),1)
        self.assertEqual(sum(not r.json()["duplicate"] for r in responses),1)
        changed = copy.deepcopy(self.plan)
        changed["interpretation"]["items"][0]["title"] = "物理"
        self.assertEqual(self.submit(changed).status_code,409)
        changed = copy.deepcopy(self.plan)
        changed["text"] = "different source"
        self.assertEqual(self.submit(changed).status_code,409)
        self.assertEqual(len(responses[0].json()["message"]["tasks"]),2)

    def test_duplicate_after_edit_and_cancel_preserves_user_changes(self):
        first = self.submit().json()
        task = first["message"]["tasks"][0]
        edited = dict(task["payload"],title="高数复习")
        self.client.put(f'/v1/tasks/{task["id"]}',headers=self.headers,json={"revision":0,"item":edited})
        self.client.delete(f'/v1/tasks/{task["id"]}?revision=1',headers=self.headers)
        retry = self.submit().json()
        self.assertTrue(retry["duplicate"])
        self.assertEqual(retry["message"]["tasks"][0]["status"],"cancelled")
        self.assertEqual(retry["message"]["tasks"][0]["payload"]["title"],"高数复习")
        self.assertEqual(len(retry["message"]["tasks"]),2)

    def test_hita_delivery_and_own_planner_save(self):
        result = self.submit().json()
        self.assertEqual([t["status"] for t in result["message"]["tasks"]],["blocked","written"])
        record = self.client.get("/v1/planner/records/"+result["message"]["tasks"][1]["id"],headers=self.headers).json()["record"]
        self.assertEqual(record["title"],"买纸")
        hita = self.client.post("/v1/delivery/hita/claim",headers=self.headers,json={"client_id":"phone"}).json()
        self.assertEqual(hita["task"]["payload"]["title"],"高数")
        self.assertEqual(hita["task"]["payload"]["starts_at"],"2026-10-12T15:00:00+08:00")
        self.assertEqual(self.store.get_task(hita["task"]["id"],"owner")["status"],"delivering")
        receipt = {"client_id":"phone","lease_token":hita["lease_token"],"revision":hita["task"]["revision"],"external_id":"simulated-phone-readback-id"}
        self.assertEqual(self.client.post(f'/v1/delivery/{hita["task"]["id"]}/ack',headers=self.headers,json=receipt).status_code,200)
        self.assertEqual(self.client.get(f'/v1/messages/{result["id"]}',headers=self.headers).json()["tasks"][0]["status"],"written")

    def test_missing_end_and_question_never_claimed(self):
        bad = copy.deepcopy(self.plan)
        del bad["interpretation"]["items"][0]["ends_at"]
        result = self.submit(bad).json()
        self.assertEqual(result["message"]["tasks"][0]["status"],"needs_clarification")
        self.assertIsNone(self.client.post("/v1/delivery/hita/claim",headers=self.headers,json={"client_id":"phone"}).json()["task"])
        only_question = {"message_id":"question","text":"下周有课","interpretation":{"question":"具体哪天？"}}
        result = self.submit(only_question).json()
        self.assertEqual(result["message"]["tasks"],[])
        self.assertEqual(result["message"]["question"],"具体哪天？")

    def test_partial_task_failure_rolls_back_message_and_tasks(self):
        # A failure after the first task must not leave an accepted message or
        # half a batch that becomes duplicated when the tool retries.
        with self.store.connection() as db:
            db.execute("CREATE TRIGGER reject_planner BEFORE INSERT ON tasks WHEN NEW.target='planner' BEGIN SELECT RAISE(ABORT,'simulated database failure'); END")
        with self.assertRaises(Exception):
            self.submit()
        with self.store.connection() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM messages").fetchone()[0],0)
            self.assertEqual(db.execute("SELECT count(*) FROM tasks").fetchone()[0],0)
            db.execute("DROP TRIGGER reject_planner")
        self.assertEqual(self.submit().status_code,201)


if __name__ == "__main__":
    unittest.main()
