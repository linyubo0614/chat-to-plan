from datetime import datetime, timezone

from .models import Item


def escape(value):
    return value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")


def fold(line):
    """RFC 5545: fold at 75 octets without splitting a UTF-8 character."""
    parts, current, size = [], "", 0
    for char in line:
        length = len(char.encode("utf-8"))
        if size + length > 75:
            parts.append(current)
            current, size = " ", 1
        current += char
        size += length
    parts.append(current)
    return "\r\n".join(parts)


def export_ics(task_id: str, item: Item, created: float):
    if item.kind not in {"course", "schedule"}:
        raise ValueError("Only courses or schedules can be exported")
    if item.clarification or not item.starts_at or not item.ends_at:
        raise ValueError("Export requires confirmed start and end times")
    if item.recurrence_description:
        raise ValueError("Recurring courses require validated semester/week expansion before export")

    def stamp(value):
        return value.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Chat Planner//ZH", "CALSCALE:GREGORIAN",
             "BEGIN:VEVENT", f"UID:{task_id}@chat-planner", f"DTSTAMP:{stamp(datetime.fromtimestamp(created, timezone.utc))}",
             f"DTSTART:{stamp(item.starts_at)}", f"DTEND:{stamp(item.ends_at)}",
             f"SUMMARY:{escape(item.title)}", f"DESCRIPTION:{escape(item.body)}", f"LOCATION:{escape(item.location)}",
             f"X-HITA-TYPE:{'class' if item.kind == 'course' else 'other'}"]
    # HITA import's reminder support is unverified; do not claim alarms are applied.
    lines.extend(["END:VEVENT", "END:VCALENDAR"])
    return ("\r\n".join(fold(line) for line in lines)+"\r\n").encode("utf-8")
