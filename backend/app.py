import hmac
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .config import Settings
from .calendar import export_ics
from .models import Incoming, Item, TaskUpdate
from .parser import DeepSeekParser
from .runtime import Runtime, SettingsUpdate
from .store import Conflict, Store
from .wechat import WeChatAPI, WeChatCrypto
from .worker import Worker


def create_app(settings=None, parser=None, run_worker=True):
    settings = settings or Settings.from_env()
    store = Store(settings.db_path)
    runtime = Runtime(store,settings)
    wechat = WeChatAPI(settings) if settings.wechat_enabled else None
    crypto = WeChatCrypto(settings) if settings.wechat_enabled else None
    worker = Worker(store, parser or DeepSeekParser(settings,runtime), wechat,runtime)

    @asynccontextmanager
    async def lifespan(app):
        if run_worker:
            worker.start()
        yield
        worker.stop()

    app = FastAPI(title="对话计划助手", version="0.2.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store, app.state.worker, app.state.runtime = store, worker, runtime
    bearer = HTTPBearer(auto_error=False)

    def authorize(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if not credentials or not hmac.compare_digest(credentials.credentials, settings.api_token):
            raise HTTPException(401, "Invalid access token")
        return settings.owner

    @app.get("/healthz")
    def health():
        # Liveness only: does not claim that integrations work.
        store.counts()
        return {"status":"ok"}

    @app.get("/v1/status")
    def status(owner=Depends(authorize)):
        return {"deepseek_configured":runtime.public()["deepseek_key_configured"], "enabled":runtime.public()["enabled"], "wechat_configured":settings.wechat_enabled,
                "wechat_allowlist_configured":bool(settings.allowed_users),
                "integrations":{"tomato":"unverified", "hita":"manual_ics_only", "qq":"not_implemented"},
                "queues":store.counts()}

    @app.get("/v1/settings")
    def get_settings(owner=Depends(authorize)):
        return runtime.public()

    @app.put("/v1/settings")
    def update_settings(patch: SettingsUpdate,owner=Depends(authorize)):
        return runtime.update(patch)

    @app.post("/v1/messages", status_code=202)
    def submit(message: Incoming, owner=Depends(authorize)):
        try:
            row, created = store.ingest("api", message.message_id, owner, message.text)
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc
        return {"id":row["id"], "status":row["status"], "duplicate":not created}

    @app.get("/v1/messages/{message_id}")
    def result(message_id: str, owner=Depends(authorize)):
        row = store.get_message(message_id, owner)
        if row is None:
            raise HTTPException(404, "Message not found")
        return row

    @app.get("/v1/messages")
    def messages(limit: int = Query(default=20,ge=1,le=50), before: int | None = Query(default=None,ge=1), owner=Depends(authorize)):
        return store.list_messages(owner,limit,before)

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
