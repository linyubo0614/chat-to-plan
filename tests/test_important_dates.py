"""Dates share the task store; migration, ordering and IM retries must be safe."""
import json
import unittest
from backend.models import Item, Interpretation, PlannerCreate
from backend.planner import Planner
from backend.store import Store
import test_planner


class ImportantDateTests(unittest.TestCase):
    setUp = test_planner.PlannerTests.setUp
    tearDown = test_planner.PlannerTests.tearDown
    create = test_planner.PlannerTests.create
    get = test_planner.PlannerTests.get
    patch = test_planner.PlannerTests.patch

    def test_dates_survive_restart_and_page_in_calendar_order(self):
        self.create(title='晚考试',record_type='exam',task_date='2026-11-30')
        _,deadline=self.create(title='先交作业',record_type='ddl',task_date='2026-10-14',due_at='2026-10-14T23:59:00+08:00')
        self.create(title='生日',record_type='important_date',task_date='2026-10-14')
        self.create(title='普通任务',task_date='2026-10-12')
        first=self.get('/v1/planner/records?kind=todo&dates_only=true&limit=1')
        second=self.get('/v1/planner/records?kind=todo&dates_only=true&limit=1&before='+str(first['next_cursor']))
        third=self.get('/v1/planner/records?kind=todo&dates_only=true&limit=1&before='+str(second['next_cursor']))
        self.assertEqual([first['records'][0]['title'],second['records'][0]['title'],third['records'][0]['title']],['生日','先交作业','晚考试'])
        self.assertIsNone(third['next_cursor'])
        self.assertEqual(self.get('/v1/planner/date-markers?month=2026-10')['days'],[dict(day='2026-10-14',count=2)])
        restarted=Planner(Store(self.settings.db_path))
        record=restarted.get(deadline['id'],'owner')
        self.assertEqual(record['record_type'],'ddl');self.assertEqual(record['due_at'],'2026-10-14T23:59:00+08:00')
        updated=self.patch(record,record_type=record['record_type'],due_at=record['due_at'],completed=True).json()['record']
        self.assertEqual(updated['due_at'],record['due_at'])
        self.assertEqual(self.get('/v1/planner/date-markers?month=2026-10')['days'][0]['count'],1)
        self.assertEqual(self.client.delete('/v1/planner/records/'+deadline['id']+'?revision=1',headers=self.headers).status_code,200)
        self.assertEqual(len(restarted.list('other','todo',dates_only=True)['records']),0)
        self.assertEqual(self.client.get('/v1/planner/date-markers?month=0000-01',headers=self.headers).status_code,422)


    def test_date_validation_and_timezone(self):
        for changes in [dict(record_type='ddl',task_date=None),dict(record_type='exam',due_at='2026-10-12T09:00:00'),dict(kind='memo',record_type='exam'),dict(record_type='unknown'),dict(due_at='2026-10-13T09:00:00+08:00')]:
            body=dict(record_id='f'*32,kind='todo',title='日期',task_date='2026-10-12');body.update(changes)
            response=self.client.post('/v1/planner/records',headers=self.headers,json=body)
            self.assertEqual(response.status_code,422,response.text)
        _,record=self.create(record_type='exam',task_date='2026-10-12',due_at='2026-10-12T23:00:00+00:00')
        self.assertEqual(record['task_date'],'2026-10-13')
        self.assertEqual(record['due_at'],'2026-10-13T07:00:00+08:00')
        self.assertEqual(self.patch(record,record_type='exam',due_at=record['due_at'],completed=True).status_code,200)
        item=Item(kind='todo',record_type='exam',title='考试')
        self.assertIsNotNone(item.clarification)


    def test_legacy_schema_hash_and_im_retry_after_upgrade(self):
        body,record=self.create()
        with self.store.connection() as db:
            db.execute('ALTER TABLE planner_records DROP COLUMN record_type')
            db.execute('ALTER TABLE planner_records DROP COLUMN due_at')
        restarted=Planner(Store(self.settings.db_path))
        self.assertEqual(restarted.get(record['id'],'owner')['record_type'],'task')
        self.assertIsNone(restarted.get(record['id'],'owner')['due_at'])
        self.assertTrue(restarted.create('owner',PlannerCreate(**body))['duplicate'])
        plan=Interpretation(items=[Item(kind='todo',title='旧计划')])
        message,_=self.store.ingest_plan('legacy-harness','owner','记任务',plan,source='harness')
        with self.store.connection() as db:
            context=json.loads(db.execute('SELECT context FROM messages WHERE id=?',(message,)).fetchone()['context'])
            for item in context['interpretation']['items']: item.pop('record_type')
            db.execute('UPDATE messages SET context=? WHERE id=?',(json.dumps(context),message))
        self.assertEqual(self.store.ingest_plan('legacy-harness','owner','记任务',plan,source='harness'),(message,False))


    def test_harness_dates_saved_once_with_metadata(self):
        body=dict(message_id='important-date-operation',text='高数考试和作业截止',interpretation=dict(items=[dict(kind='todo',record_type='exam',title='高数考试',task_date='2026-10-20',due_at='2026-10-20T09:00:00+08:00'),dict(kind='todo',record_type='ddl',title='交作业',task_date='2026-10-19')]))
        response=self.client.post('/v1/harness/plans',headers=self.headers,json=body)
        self.assertEqual(response.status_code,201,response.text)
        self.assertTrue(self.client.post('/v1/harness/plans',headers=self.headers,json=body).json()['duplicate'])
        dates=self.get('/v1/planner/records?kind=todo&dates_only=true')['records']
        self.assertEqual([r['record_type'] for r in dates],['ddl','exam'])
        self.assertEqual(dates[1]['due_at'],'2026-10-20T09:00:00+08:00')
