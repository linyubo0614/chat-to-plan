import os
from dataclasses import dataclass
from pathlib import Path
from zoneinfo import ZoneInfo


@dataclass(frozen=True)
class Settings:
    api_token: str
    db_path: Path
    deepseek_key: str = ""
    model: str = "deepseek-chat"
    timezone: str = "Asia/Shanghai"
    owner: str = "owner"
    corp_id: str = ""
    kf_secret: str = ""
    callback_token: str = ""
    encoding_key: str = ""
    allowed_users: tuple[str, ...] = ()

    def __post_init__(self):
        if len(self.api_token) < 24:
            raise ValueError("ASSISTANT_API_TOKEN must contain at least 24 characters")
        ZoneInfo(self.timezone)

    @property
    def wechat_enabled(self) -> bool:
        return bool(self.corp_id and self.kf_secret and self.callback_token and self.encoding_key)

    @classmethod
    def from_env(cls):
        return cls(
            api_token=os.getenv("ASSISTANT_API_TOKEN", ""),
            db_path=Path(os.getenv("ASSISTANT_DB", "data/assistant.db")),
            deepseek_key=os.getenv("DEEPSEEK_API_KEY", ""),
            model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
            timezone=os.getenv("ASSISTANT_TIMEZONE", "Asia/Shanghai"),
            owner=os.getenv("ASSISTANT_OWNER", "owner"),
            corp_id=os.getenv("WECHAT_CORP_ID", ""),
            kf_secret=os.getenv("WECHAT_KF_SECRET", ""),
            callback_token=os.getenv("WECHAT_CALLBACK_TOKEN", ""),
            encoding_key=os.getenv("WECHAT_ENCODING_AES_KEY", ""),
            allowed_users=tuple(x.strip() for x in os.getenv("WECHAT_ALLOWED_USERS", "").split(",") if x.strip()),
        )
