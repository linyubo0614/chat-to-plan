from datetime import date, datetime
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
    category: str = Field(default="其他",min_length=1,max_length=80)
    important: bool = False
    urgent: bool = False
    record_type: Literal["task", "ddl", "exam", "important_date"] = "task"
    task_date: date | None = None
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
        if self.record_type != "task" and self.kind != "todo":
            raise ValueError("Important dates use todo kind")
        if self.record_type != "task" and not self.task_date and not self.due_at and not self.clarification:
            self.clarification = "请补充截止日期或重要日期。"
        if self.due_at and self.task_date and self.due_at.date() != self.task_date:
            raise ValueError("Date and time must refer to the same day")
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
        return "hita" if self.kind in {"schedule", "course"} else "planner"

    @field_validator("category", "title")
    @classmethod
    def trimmed_nonblank(cls,value):
        if not value.strip():
            raise ValueError("Field cannot be blank")
        return value.strip()


class Interpretation(StrictModel):
    items: list[Item] = Field(default_factory=list, max_length=20)
    question: str | None = Field(default=None, min_length=1, max_length=2000)

    @model_validator(mode="after")
    def nonempty(self):
        if not self.items and not self.question:
            raise ValueError("At least one item or clarification question is required")
        return self


class OpenClawPlan(Incoming):
    """Already interpreted by the owner's OpenClaw model; do not parse twice."""
    interpretation: Interpretation


class PlannerRecord(StrictModel):
    kind: Literal["todo","memo"]
    title: str = Field(min_length=1,max_length=200)
    body: str = Field(default="",max_length=8000)
    category: str = Field(default="其他",min_length=1,max_length=80)
    important: bool = False
    urgent: bool = False
    record_type: Literal["task", "ddl", "exam", "important_date"] = "task"
    due_at: datetime | None = None
    task_date: date | None = None
    completed: bool = False

    @field_validator("due_at")
    @classmethod
    def aware_due(cls, value):
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("Dates must include timezone offsets")
        return value

    @model_validator(mode="after")
    def check_date(self):
        if self.kind == "memo" and (self.record_type != "task" or self.due_at is not None):
            raise ValueError("Memos cannot have deadline metadata")
        if self.record_type != "task" and self.task_date is None and self.due_at is None:
            raise ValueError("Important dates require a date")
        if self.due_at and self.task_date and self.due_at.date() != self.task_date:
            raise ValueError("Date and time must refer to the same day")
        return self

    @field_validator("title","category")
    @classmethod
    def trimmed_nonblank(cls,value):
        if not value.strip():
            raise ValueError("Field cannot be blank")
        return value.strip()


class PlannerCreate(PlannerRecord):
    record_id: str = Field(pattern=r"^[a-f0-9]{32}$")


class PlannerUpdate(PlannerRecord):
    revision: int = Field(ge=0)


class TaskUpdate(StrictModel):
    revision: int = Field(ge=0)
    item: Item


class DeliveryClaim(StrictModel):
    client_id: str = Field(min_length=1,max_length=128)


class DeliveryReceipt(DeliveryClaim):
    lease_token: str = Field(min_length=32,max_length=128)
    revision: int = Field(ge=0)
    external_id: str = Field(min_length=1,max_length=200)
