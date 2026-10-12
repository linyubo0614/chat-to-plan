import hmac
from contextlib import asynccontextmanager
from datetime import datetime
from datetime import date
from typing import Literal
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .config import Settings
from .delivery import Delivery
from .calendar import export_ics
from .models import Incoming, Item, TaskUpdate, DeliveryClaim, DeliveryReceipt, OpenClawPlan, PlannerCreate, PlannerUpdate
from .planner import Planner
from .parser import DeepSeekParser
from .runtime import Runtime, SettingsUpdate
from .store import Conflict, Store
from .wechat import WeChatAPI, WeChatCrypto
from .worker import Worker
from .pairing import Pairing, PairCreate, PairClaim


def create_app(settings=None, parser=None, run_worker=True):
    settings = settings or Settings.from_env()
    store = Store(settings.db_path,settings.timezone)
    planner = Planner(store)
    delivery = Delivery(store)
    runtime = Runtime(store,settings)
    pairing = Pairing(store)
    wechat = WeChatAPI(settings) if settings.wechat_enabled else None
    crypto = WeChatCrypto(settings) if settings.wechat_enabled else None
    worker = Worker(store, parser or DeepSeekParser(settings,runtime), wechat,runtime)

    @asynccontextmanager
    async def lifespan(app):
        if run_worker:
            worker.start()
        yield
        worker.stop()

    app = FastAPI(title="对话计划助手", version="0.7.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store, app.state.worker, app.state.runtime = store, worker, runtime
    bearer = HTTPBearer(auto_error=False)

    def authorize(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if not credentials:
            raise HTTPException(401, "Invalid access token")
        if hmac.compare_digest(credentials.credentials, settings.api_token):
            return settings.owner
        owner = pairing.owner(credentials.credentials)
        if owner is None:
            raise HTTPException(401, "Invalid access token")
        return owner

    @app.post("/v1/pairings", status_code=201)
    def create_pairing(body: PairCreate,response: Response,owner=Depends(authorize)):
        response.headers["Cache-Control"] = "no-store"
        return pairing.create(owner,body.label)

    @app.post("/v1/pairings/claim")
    def claim_pairing(body: PairClaim,response: Response):
        response.headers["Cache-Control"] = "no-store"
        claimed = pairing.claim(body.code,body.label)
        if claimed is None:
            raise HTTPException(410,"Pairing code expired or already used")
        return claimed

    @app.get("/v1/devices")
    def devices(owner=Depends(authorize)):
        return {"devices":pairing.devices(owner)}

    @app.delete("/v1/devices/{device}")
    def revoke_device(device: str,owner=Depends(authorize)):
        pairing.revoke(owner,device)
        return {"revoked":True}

    @app.get("/healthz")
    def health():
        # Liveness only: does not claim that integrations work.
        store.counts()
        return {"status":"ok"}

    @app.get("/v1/status")
    def status(owner=Depends(authorize)):
        return {"version":"0.7.0","deepseek_configured":runtime.public()["deepseek_key_configured"], "enabled":runtime.public()["enabled"], "wechat_configured":settings.wechat_enabled,
                "wechat_allowlist_configured":bool(settings.allowed_users),
                "server_time":datetime.now(ZoneInfo(settings.timezone)).isoformat(),
                "integrations":{"planner":"calendar_quadrants_and_memos", "hita":"compatible_bridge_unverified", "openclaw":"structured_tools", "harness":"structured_tools", "qq":"via_dsh_im"},
                "queues":store.counts(),"connections":store.connections(owner)}

    @app.get("/v1/settings")
    def get_settings(owner=Depends(authorize)):
        return runtime.public()

    @app.put("/v1/settings")
    def update_settings(patch: SettingsUpdate,owner=Depends(authorize)):
        result = runtime.update(patch)
        delivery.signal()
        return result

    @app.post("/v1/messages", status_code=202)
    def submit(message: Incoming, owner=Depends(authorize)):
        try:
            row, created = store.ingest("api", message.message_id, owner, message.text)
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc
        return {"id":row["id"], "status":row["status"], "duplicate":not created}

    def save_plan(plan, response, owner, source):
        if not runtime.public()["enabled"]:
            raise HTTPException(423,"Assistant is paused; no plan was accepted")
        try:
            key,created = store.ingest_plan(plan.message_id,owner,plan.text,plan.interpretation,source=source)
        except Conflict as exc:
            raise HTTPException(409,str(exc)) from exc
        if not created:
            response.status_code = 200
        store.touch_connection(owner,source)
        delivery.signal()
        return {"id":key,"duplicate":not created,"message":store.get_message(key,owner),
                "delivery_notice":"备忘和待办已保存到对话计划，手机联网后可查看；HITA 须等待目标端写入并读回确认。"}

    @app.post("/v1/openclaw/plans", status_code=201)
    def submit_plan(plan: OpenClawPlan,response: Response,owner=Depends(authorize)):
        return save_plan(plan,response,owner,"openclaw")

    @app.post("/v1/harness/plans", status_code=201)
    def submit_harness_plan(plan: OpenClawPlan,response: Response,owner=Depends(authorize)):
        return save_plan(plan,response,owner,"harness")

    @app.post("/v1/planner/records",status_code=201)
    def create_record(body: PlannerCreate,response: Response,owner=Depends(authorize)):
        try:
            result = planner.create(owner,body)
        except Conflict as exc:
            raise HTTPException(409,str(exc)) from exc
        if result["duplicate"]:
            response.status_code=200
        return result

    @app.get("/v1/planner/records")
    def records(kind: Literal["todo","memo"],day: date | None = None,
                category: str | None = Query(default=None,max_length=80),q: str = Query(default="",max_length=200),
                limit: int = Query(default=50,ge=1,le=200),before: int | None = Query(default=None,ge=1),dates_only: bool = False,owner=Depends(authorize)):
        return planner.list(owner,kind,day,category,q,limit,before,dates_only)

    @app.get("/v1/planner/calendar")
    def calendar_overview(month: str = Query(pattern=r"^\d{4}-(0[1-9]|1[0-2])$"),owner=Depends(authorize)):
        try:
            return planner.calendar(owner,month)
        except ValueError as exc:
            raise HTTPException(422,"Invalid month") from exc

    @app.get("/v1/planner/date-markers")
    def date_markers(month: str = Query(pattern=r"^\d{4}-(0[1-9]|1[0-2])$"),owner=Depends(authorize)):
        try:
            first = date.fromisoformat(month+"-01")
            next_month = date(first.year+1,1,1) if first.month == 12 else date(first.year,first.month+1,1)
        except ValueError as exc:
            raise HTTPException(422,"Invalid month") from exc
        with store.connection() as db:
            rows = db.execute("SELECT task_date AS day,count(*) AS count FROM planner_records WHERE owner=? AND kind='todo' AND record_type!='task' AND completed=0 AND deleted=0 AND task_date>=? AND task_date<? GROUP BY task_date",(owner,first.isoformat(),next_month.isoformat())).fetchall()
            return {"days":[dict(row) for row in rows]}

    @app.get("/v1/planner/categories")
    def categories(kind: Literal["todo","memo"]="memo",owner=Depends(authorize)):
        return planner.categories(owner,kind)

    @app.get("/v1/planner/records/{key}")
    def get_record(key: str,owner=Depends(authorize)):
        row=planner.get(key,owner)
        if row is None:
            raise HTTPException(404,"Record not found")
        return {"record":row}

    @app.put("/v1/planner/records/{key}")
    def update_record(key: str,body: PlannerUpdate,owner=Depends(authorize)):
        try:
            result=planner.update(key,owner,body.revision,body)
        except Conflict as exc:
            raise HTTPException(409,str(exc)) from exc
        if result is None:
            raise HTTPException(404,"Record not found")
        return result

    @app.delete("/v1/planner/records/{key}")
    def delete_record(key: str,revision: int = Query(ge=0),owner=Depends(authorize)):
        try:
            result=planner.update(key,owner,revision,delete=True)
        except Conflict as exc:
            raise HTTPException(409,str(exc)) from exc
        if result is None:
            raise HTTPException(404,"Record not found")
        return result

    @app.get("/v1/messages/{message_id}")
    def result(message_id: str, owner=Depends(authorize)):
        row = store.get_message(message_id, owner)
        if row is None:
            raise HTTPException(404, "Message not found")
        return row

    @app.get("/v1/messages")
    def messages(limit: int = Query(default=20,ge=1,le=50), before: int | None = Query(default=None,ge=1), owner=Depends(authorize)):
        return store.list_messages(owner,limit,before)

    @app.post("/v1/delivery/{target}/claim")
    def claim_delivery(target: str, body: DeliveryClaim,wait: int = Query(default=0,ge=0,le=25),owner=Depends(authorize)):
        if target != "hita":
            raise HTTPException(404,"Unknown target")
        store.touch_connection(owner,"hita")
        if not runtime.public()["enabled"]:
            return {"task":None,"paused":True}
        return delivery.wait_claim(target,owner,body.client_id,wait,lambda:runtime.public()["enabled"])

    @app.post("/v1/delivery/{task_id}/ack")
    def acknowledge_delivery(task_id: str,body: DeliveryReceipt,owner=Depends(authorize)):
        try:
            result = delivery.acknowledge(task_id,owner,body)
        except Conflict as exc:
            raise HTTPException(409,str(exc)) from exc
        if result is None:
            raise HTTPException(404,"Delivery not found")
        delivery.signal()
        return result

    @app.post("/v1/delivery/{task_id}/retry")
    def retry_delivery(task_id: str,owner=Depends(authorize)):
        try:
            result = delivery.retry(task_id,owner)
        except Conflict as exc:
            raise HTTPException(409,str(exc)) from exc
        if result is None:
            raise HTTPException(404,"Delivery not found")
        delivery.signal()
        return result

    @app.put("/v1/tasks/{task_id}")
    def edit_task(task_id: str, patch: TaskUpdate, owner=Depends(authorize)):
        try:
            message_id = store.update_task(task_id,owner,patch.revision,patch.item)
        except Conflict as exc:
            raise HTTPException(409,str(exc)) from exc
        if message_id is None:
            raise HTTPException(404,"Task not found")
        return store.get_message(message_id,owner)

    @app.delete("/v1/tasks/{task_id}")
    def cancel_task(task_id: str, revision: int = Query(ge=0), owner=Depends(authorize)):
        try:
            message_id = store.update_task(task_id,owner,revision,cancel=True)
        except Conflict as exc:
            raise HTTPException(409,str(exc)) from exc
        if message_id is None:
            raise HTTPException(404,"Task not found")
        return store.get_message(message_id,owner)

    @app.post("/v1/messages/{message_id}/retry", status_code=202)
    def retry(message_id: str, owner=Depends(authorize)):
        try:
            row = store.retry_message(message_id,owner)
        except Conflict as exc:
            raise HTTPException(409,str(exc)) from exc
        if row is None:
            raise HTTPException(404,"Message not found")
        return row

    @app.get("/v1/tasks/{task_id}/calendar.ics")
    def calendar(task_id: str, owner=Depends(authorize)):
        task = store.get_task(task_id,owner)
        if task is None:
            raise HTTPException(404,"Task not found")
        if task["status"] == "cancelled":
            raise HTTPException(409,"Cancelled records cannot be exported")
        try:
            content = export_ics(task_id,Item.model_validate_json(task["payload"]),task["created"])
        except ValueError as exc:
            raise HTTPException(409,str(exc)) from exc
        return Response(content,media_type="text/calendar",headers={"Content-Disposition":'attachment; filename="schedule.ics"'})

    @app.get("/wechat/callback")
    def verify(msg_signature: str, timestamp: str, nonce: str, echostr: str):
        if not crypto:
            raise HTTPException(503, "WeChat is not configured")
        try:
            return Response(crypto.decrypt(msg_signature,timestamp,nonce,echostr), media_type="text/plain")
        except ValueError as exc:
            raise HTTPException(403, "Callback verification failed") from exc

    @app.post("/wechat/callback")
    async def callback(request: Request, msg_signature: str, timestamp: str, nonce: str):
        if not crypto:
            raise HTTPException(503, "WeChat is not configured")
        # Bound streamed bodies, including requests without Content-Length.
        chunks, total = [], 0
        async for chunk in request.stream():
            total += len(chunk)
            if total > 65536:
                raise HTTPException(413, "Callback too large")
            chunks.append(chunk)
        try:
            key, event = crypto.event(msg_signature,timestamp,nonce,b"".join(chunks))
        except Exception as exc:
            raise HTTPException(403, "Callback verification failed") from exc
        store.enqueue_event(key,event)
        return Response("success",media_type="text/plain")

    return app
