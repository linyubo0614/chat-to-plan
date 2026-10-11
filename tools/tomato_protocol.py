"""Website-derived delta protocol. No login secrets or signing constants here."""
import time
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo

LISTS = ("listInfoList","listClockList","listCategoryList","listChildList","listTimeLineList","listDeleteList")


class TomatoUnverified(Exception):
    pass


def request(opt_id=-1, records=None, categories=None):
    result = {"optId":opt_id,**{key:[] for key in LISTS}}
    result["listInfoList"] = records or []
    result["listCategoryList"] = categories or []
    return result


def validate_snapshot(reply):
    if reply.get("code") != 200 or not isinstance(reply.get("data"),dict):
        raise TomatoUnverified("session_or_response_unavailable")
    data = reply["data"]
    if data.get("differenceData"):
        raise TomatoUnverified("sync_conflict")
    if not isinstance(data.get("lastOptId"),int) or not all(isinstance(data.get(key),list) for key in LISTS[:-1]):
        raise TomatoUnverified("website_protocol_changed")
    return data


def prepare_record(snapshot,user_id,key,item):
    if item["kind"] not in {"memo","todo"} or item.get("clarification") or item.get("recurrence_description") or item.get("reminder_minutes") is not None:
        raise TomatoUnverified("unsupported_item")
    record_id = str(uuid.uuid5(uuid.NAMESPACE_URL,f"chat-planner:tomato:{user_id}:{key}"))
    category_title = item["category"]
    categories = snapshot["listCategoryList"]
    matching = [row for row in categories if row.get("title")==category_title and not row.get("deleteState")]
    if len(matching)>1:
        raise TomatoUnverified("ambiguous_category")
    category = matching[0] if matching else {
        "categoryId":str(uuid.uuid5(uuid.NAMESPACE_URL,f"chat-planner:tomato-category:{user_id}:{category_title}")),
        "userId":user_id,"title":category_title,"sortNum":max([row.get("sortNum",0) for row in categories]+[0])+10,
        "createTimeStamp":int(time.time()*1000),"dataAction":"add"}
    start_date,start_time = "",-1
    if item.get("due_at"):
        due = datetime.fromisoformat(item["due_at"]).astimezone(ZoneInfo("Asia/Shanghai"))
        start_date,start_time = due.date().isoformat(),due.hour*60+due.minute
    record = {
        "inventoryId":record_id,"userId":user_id,"title":item["title"],"notes":item.get("body",""),
        "categoryId":category["categoryId"],"categoryTitle":category_title,"color":4284513675,
        "createTimeStamp":int(time.time()*1000),"crossDays":0,"crossEndDate":"","dataAction":"add",
        "deleteState":0,"endDate":"","endTime":-1,"hasRepeatBranch":False,"notesImageUrls":"",
        "postponeDays":0,"priorityLevel":4,"remindTimes":"","repeatCycle":1,"repeatDeleteDates":"",
        "repeatEndInfo":"","repeatEndType":"","repeatInfo":"","repeatType":"","skipHolidaysState":False,
        "sortNum":max([row.get("sortNum",0) for row in snapshot["listInfoList"]]+[0])+10,
        "startDate":start_date,"startTime":start_time,"timeOutAction":"","topState":False}
    return record,[] if matching else [category]


def verify_record(snapshot,expected):
    records = [row for row in snapshot["listInfoList"] if row.get("inventoryId")==expected["inventoryId"] and not row.get("deleteState")]
    if len(records)!=1:
        return False
    for key in ("title","notes","categoryId","categoryTitle","startDate","startTime"):
        if records[0].get(key)!=expected[key]:
            raise TomatoUnverified("target_record_differs")
    categories = [row for row in snapshot["listCategoryList"] if row.get("categoryId")==expected["categoryId"]]
    if len(categories)!=1 or categories[0].get("title")!=expected["categoryTitle"]:
        raise TomatoUnverified("category_readback_failed")
    return True


def write_verified(sync,user_id,key,item):
    # Read before each attempt: a timeout may have occurred after a successful write.
    snapshot = validate_snapshot(sync(request()))
    record,categories = prepare_record(snapshot,user_id,key,item)
    if verify_record(snapshot,record):
        return record["inventoryId"]
    if any(row.get("inventoryId")==record["inventoryId"] for row in snapshot["listInfoList"]):
        raise TomatoUnverified("target_record_deleted_or_conflicting")
    reply = sync(request(snapshot["lastOptId"],[record],categories))
    if reply.get("code") != 200 or not isinstance(reply.get("data"),dict) or reply["data"].get("differenceData"):
        raise TomatoUnverified("write_not_confirmed")
    readback = validate_snapshot(sync(request()))
    if not verify_record(readback,record):
        raise TomatoUnverified("record_not_found_after_write")
    return record["inventoryId"]
