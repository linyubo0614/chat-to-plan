import copy
import unittest

from tools.tomato_protocol import TomatoUnverified,request,write_verified


class Website:
    def __init__(self):
        self.data={key:[] for key in request() if key!="optId"}
        self.data.update(lastOptId=0,differenceData=False)
        self.data["listInfoList"]=[{"inventoryId":"existing-user-record","title":"不能修改的原记录","sortNum":10}]
        self.writes=[]
        self.conflict=False
        self.lose_reply=False
    def sync(self,payload):
        if payload["optId"]!=-1:
            self.writes.append(copy.deepcopy(payload))
            if self.conflict: return {"code":200,"data":{"differenceData":True}}
            for key in ("listInfoList","listCategoryList"):
                self.data[key].extend(copy.deepcopy(payload[key]))
            self.data["lastOptId"]+=1
            if self.lose_reply:
                self.lose_reply=False;raise TimeoutError("response lost after commit")
        return {"code":200,"data":copy.deepcopy(self.data)}


class TomatoTests(unittest.TestCase):
    def setUp(self):
        self.site=Website()
        self.item={"kind":"memo","title":"测试笔记","body":"正文","category":"学习"}
    def test_delta_creation_classification_and_readback(self):
        original=copy.deepcopy(self.site.data["listInfoList"][0])
        key=write_verified(self.site.sync,"account-id","task-id",self.item)
        self.assertEqual(self.site.data["listInfoList"][0],original)
        self.assertEqual(len(self.site.writes),1)
        self.assertEqual(self.site.writes[0]["listDeleteList"],[])
        self.assertEqual(self.site.writes[0]["listClockList"],[])
        record=self.site.data["listInfoList"][1]
        self.assertEqual(record["inventoryId"],key)
        self.assertEqual(record["categoryTitle"],"学习")
        self.assertEqual(record["notes"],"正文")
        self.assertEqual(record["categoryId"],self.site.data["listCategoryList"][0]["categoryId"])
    def test_lost_reply_recovery_does_not_duplicate(self):
        self.site.lose_reply=True
        with self.assertRaises(TimeoutError): write_verified(self.site.sync,"account-id","task-id",self.item)
        write_verified(self.site.sync,"account-id","task-id",self.item)
        self.assertEqual(len(self.site.writes),1)
        self.assertEqual(len(self.site.data["listInfoList"]),2)
    def test_conflict_and_local_edit_are_not_overwritten(self):
        self.site.conflict=True
        with self.assertRaises(TomatoUnverified): write_verified(self.site.sync,"account-id","task-id",self.item)
        self.assertEqual(len(self.site.data["listInfoList"]),1)
        self.site.conflict=False
        write_verified(self.site.sync,"account-id","task-id",self.item)
        self.site.data["listInfoList"][1]["notes"]="用户修改后的内容"
        with self.assertRaises(TomatoUnverified): write_verified(self.site.sync,"account-id","task-id",self.item)
        self.assertEqual(self.site.data["listInfoList"][1]["notes"],"用户修改后的内容")
    def test_due_time_and_unsupported_recurrence(self):
        self.item.update(kind="todo",due_at="2026-10-14T07:30:00Z")
        write_verified(self.site.sync,"account-id","task-id",self.item)
        record=self.site.data["listInfoList"][1]
        self.assertEqual((record["startDate"],record["startTime"]),("2026-10-14",930))
        self.item["recurrence_description"]="每周三"
        with self.assertRaises(TomatoUnverified): write_verified(self.site.sync,"account-id","other-id",self.item)


if __name__=="__main__": unittest.main()
