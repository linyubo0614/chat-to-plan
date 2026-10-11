import copy
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi.testclient import TestClient

from backend.app import create_app
from backend.config import Settings
from backend.models import Item, Interpretation, PlannerCreate
from backend.planner import Planner
from backend.store import Store


class PlannerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.settings = Settings(api_token="a"*32, db_path=Path(self.temp.name)/"db")
        self.app = create_app(self.settings,run_worker=False)
        self.client = TestClient(self.app)
        self.headers = {"Authorization":"Bearer "+self.settings.api_token}
        self.store = self.app.state.store
        self.planner = Planner(self.store)
        self.counter = 0

    def tearDown(self):
        self.client.close(); self.temp.cleanup()

    def create(self,**kwargs):
        self.counter += 1
        body = dict(record_id=f"{self.counter:032x}",kind="todo",title="作业",category="高数",task_date="2026-10-12")
        body.update(kwargs)
        result = self.client.post("/v1/planner/records",headers=self.headers,json=body)
        self.assertEqual(result.status_code,201,result.text)
        return body,result.json()["record"]

    def get(self,path):
        response = self.client.get(path,headers=self.headers)
        self.assertEqual(response.status_code,200,response.text)
        return response.json()

    def patch(self,record,**kwargs):
        keys = ("kind","title","body","category","important","urgent","task_date","completed","revision")
        body = {key:record[key] for key in keys}; body.update(kwargs)
        return self.client.put("/v1/planner/records/"+record["id"],headers=self.headers,json=body)

    def test_calendar_all_quadrants_and_completion_persist(self):
        for important in (False,True):
            for urgent in (False,True):
                self.create(important=important,urgent=urgent)
        self.create(task_date="2026-11-01")
        _,memo = self.create(kind="memo",category="女朋友",body="喜欢茉莉花茶")
        self.assertIsNone(memo["task_date"])
        day = self.get("/v1/planner/records?kind=todo&day=2026-10-12")["records"]
        self.assertEqual({(r["important"],r["urgent"]) for r in day},{(False,False),(False,True),(True,False),(True,True)})
        self.assertEqual(self.get("/v1/planner/calendar?month=2026-10")["days"],[dict(day="2026-10-12",total=4,completed=0)])
        self.assertEqual(self.patch(day[0],completed=True).status_code,200)
        self.assertEqual(self.get("/v1/planner/calendar?month=2026-10")["days"][0]["completed"],1)
        restarted = Planner(Store(self.settings.db_path))
        self.assertTrue(restarted.get(day[0]["id"],"owner")["completed"])
        self.assertEqual(self.get("/v1/planner/calendar?month=2026-11")["days"][0]["total"],1)

    def test_manual_retry_preserves_edit_and_deleted_tombstone(self):
        body,record = self.create(task_date=None)
        changed = self.patch(record,title="已经修改",completed=True).json()["record"]
        retried = self.client.post("/v1/planner/records",headers=self.headers,json=body)
        self.assertEqual(retried.status_code,200)
        self.assertTrue(retried.json()["duplicate"])
        self.assertEqual(retried.json()["record"]["title"],"已经修改")
        self.assertEqual(self.client.delete(f'/v1/planner/records/{record["id"]}?revision={changed["revision"]}',headers=self.headers).status_code,200)
        retry = self.client.post("/v1/planner/records",headers=self.headers,json=body).json()
        self.assertTrue(retry["deleted"])
        self.assertEqual(self.get("/v1/planner/records?kind=todo")["records"],[])
        changed_body = dict(body,title="different")
        self.assertEqual(self.client.post("/v1/planner/records",headers=self.headers,json=changed_body).status_code,409)

    def test_concurrent_versioned_edits_cannot_overwrite(self):
        _,record = self.create()
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda i:self.patch(record,title=f"编辑{i}"),range(8)))
        self.assertEqual(sum(r.status_code==200 for r in results),1)
        self.assertEqual(sum(r.status_code==409 for r in results),7)
        self.assertEqual(self.client.delete(f'/v1/planner/records/{record["id"]}?revision=0',headers=self.headers).status_code,409)
        updated = self.get("/v1/planner/records/"+record["id"])["record"]
        self.assertEqual(self.patch(updated,task_date="2026-11-01").status_code,200)
        self.assertEqual(self.get("/v1/planner/calendar?month=2026-10")["days"],[])

    def test_custom_categories_literal_search_and_pagination(self):
        for i in range(5): self.create(kind="memo",title=f"笔记{i}",body="得分 100%_",category="英语")
        self.create(kind="memo",title="喜好",body="奶茶",category="女朋友")
        first = self.get("/v1/planner/records?kind=memo&category=英语&limit=2")
        second = self.get(f'/v1/planner/records?kind=memo&category=英语&limit=2&before={first["next_cursor"]}')
        last = self.get(f'/v1/planner/records?kind=memo&category=英语&limit=2&before={second["next_cursor"]}')
        all_records = first["records"]+second["records"]+last["records"]
        self.assertEqual(len({r["id"] for r in all_records}),5)
        self.assertIsNone(last["next_cursor"])
        self.assertEqual(len(self.get("/v1/planner/records?kind=memo&q=%25_")["records"]),5)
        self.assertEqual(len(self.get("/v1/planner/categories?kind=memo")["categories"]),2)
        self.assertEqual(self.get("/v1/planner/records?kind=memo&category=高数")["records"],[])

    def test_owner_isolation_auth_and_validation(self):
        _,record = self.create()
        self.assertEqual(self.client.get("/v1/planner/records?kind=todo").status_code,401)
        self.assertIsNone(self.planner.get(record["id"],"other"))
        self.assertEqual(self.planner.list("other","todo")["records"],[])
        self.assertEqual(self.planner.categories("other","todo")["categories"],[])
        self.assertEqual(self.planner.calendar("other","2026-10")["days"],[])
        self.assertIsNone(self.planner.update(record["id"],"other",0,delete=True))
        for month in ["2026-13","0000-01","9999-12"]:
            self.assertEqual(self.client.get("/v1/planner/calendar?month="+month,headers=self.headers).status_code,422)
        for patch in [dict(title="  "),dict(category="  "),dict(task_date="yesterday"),dict(kind="course"),dict(extra="x")]:
            body=dict(record_id="f"*32,kind="todo",title="test");body.update(patch)
            self.assertEqual(self.client.post("/v1/planner/records",headers=self.headers,json=body).status_code,422)

    def test_openclaw_saves_immediately_and_retry_preserves_app_edits(self):
        plan=dict(message_id="wechat-once",text="今天交英语作业，重要紧急。她喜欢茉莉花茶。",interpretation=dict(items=[
            dict(kind="todo",title="英语作业",category="英语",task_date="2026-10-12",important=True,urgent=True),
            dict(kind="memo",title="喜欢的茶",category="女朋友",body="茉莉花茶")]))
        response=self.client.post("/v1/openclaw/plans",headers=self.headers,json=plan).json()
        tasks=response["message"]["tasks"]
        self.assertTrue(all(t["status"]=="written" and t["target"]=="planner" for t in tasks))
        record=self.get("/v1/planner/records/"+tasks[0]["id"])["record"]
        self.assertEqual(self.patch(record,title="英语作文",completed=True).status_code,200)
        retry=self.client.post("/v1/openclaw/plans",headers=self.headers,json=plan).json()
        self.assertTrue(retry["duplicate"])
        self.assertEqual(retry["message"]["tasks"][0]["payload"]["title"],"英语作文")
        self.assertEqual(len(self.get("/v1/planner/records?kind=todo")["records"]),1)
        self.assertTrue(self.get("/v1/planner/records/"+record["id"])["record"]["completed"])

    def test_migration_of_legacy_queue_is_once_and_preserves_edits(self):
        message,_=self.store.ingest("api","legacy","owner","旧记录")
        with self.store.connection() as db:
            db.execute("INSERT INTO tasks(id,message_id,target,payload,status,reason) VALUES(?,?,'tomato',?,'blocked','old')",
                       ("e"*32,message["id"],Item(kind="memo",title="旧笔记",category="高数").model_dump_json()))
        migrated=Planner(Store(self.settings.db_path))
        record=migrated.get("e"*32,"owner")
        self.assertEqual(record["title"],"旧笔记")
        changed=dict(kind="memo",title="新笔记",category="高数",revision=0)
        from backend.models import PlannerUpdate
        migrated.update(record["id"],"owner",0,PlannerUpdate(**changed))
        restarted=Planner(Store(self.settings.db_path))
        self.assertEqual(restarted.get(record["id"],"owner")["title"],"新笔记")
        self.assertEqual(len(restarted.list("owner","memo")["records"]),1)


if __name__=="__main__": unittest.main()
