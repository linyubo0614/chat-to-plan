from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Incoming(StrictModel):
    message_id: str = Field(min_length=1, max_length=128)
    text: str = Field(min_length=1, max_length=8000)

    @field_validator("text")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("Message cannot be blank")
        return value


class Item(StrictModel):
    kind: Literal["memo", "todo", "schedule", "course"]
    title: str = Field(min_length=1, max_length=200)
    body: str = Field(default="", max_length=8000)
    category: Literal["学习", "工作", "生活", "其他"] = "其他"
    starts_at: datetime | None = None
    ends_at: datetime | None = None
    due_at: datetime | None = None
    location: str = Field(default="", max_length=500)
    reminder_minutes: int | None = Field(default=None, ge=0, le=10080)
    # Preserve recurrence intent, but never execute it without a confirmed adapter.
    recurrence_description: str = Field(default="", max_length=1000)
    clarification: str | None = Field(default=None, min_length=1, max_length=1000)

    @field_validator("starts_at", "ends_at", "due_at")
    @classmethod
    def aware(cls, value):
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("Dates must include timezone offsets")
        return value

    @model_validator(mode="after")
    def check_schedule(self):
        if self.ends_at and not self.starts_at:
            raise ValueError("End time requires a start time")
        if self.starts_at and self.ends_at and self.ends_at <= self.starts_at:
            raise ValueError("End time must follow start time")
        if self.kind in {"schedule", "course"} and not self.starts_at and not self.clarification:
            self.clarification = "请补充具体日期和开始时间；重复课程还需要学期起始日期及周次。"
        elif self.kind in {"schedule", "course"} and not self.ends_at and not self.clarification:
            self.clarification = "请补充结束时间，便于生成准确的课表记录。"
        return self

    @property
    def target(self):
        return "hita" if self.kind in {"schedule", "course"} else "tomato"


class Interpretation(StrictModel):
    items: list[Item] = Field(default_factory=list, max_length=20)
    question: str | None = Field(default=None, min_length=1, max_length=2000)

    @model_validator(mode="after")
    def nonempty(self):
        if not self.items and not self.question:
            raise ValueError("At least one item or clarification question is required")
        return self
