package cn.limpu.hita.planner

import android.content.Context
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

class PlannerLocalRepository(private val context: Context, private val injectedDatabase: AppDatabase? = null) {
    private val access = PlannerAccess(context)
    private val db get() = injectedDatabase ?: AppDatabase.getDatabase(context)
    private fun response(status: String, message: String) = JSONObject().put("status", status).put("message", message)
    private fun offset(milliseconds: Long) = Instant.ofEpochMilli(milliseconds).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
    private fun text(value: JSONObject, key: String, fallback: String = ""): String = if (value.isNull(key)) fallback else value.optString(key, fallback)
    private fun time(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()
    internal fun fingerprint(event: EventItem): String {
        val raw = JSONObject().put("id", event.id).put("type", event.type.name).put("source", event.source)
            .put("name", event.name).put("place", event.place ?: JSONObject.NULL).put("teacher", event.teacher ?: JSONObject.NULL)
            .put("subject", event.subjectId).put("table", event.timetableId).put("from", event.from.time).put("to", event.to.time)
            .put("fromNumber", event.fromNumber).put("lastNumber", event.lastNumber).put("created", event.createdAt.time).toString()
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun public(event: EventItem): JSONObject = JSONObject().put("record_id", event.id).put("id", event.id)
        .put("fingerprint", fingerprint(event)).put("kind", if (event.type == EventItem.TYPE.CLASS) "course" else "schedule")
        .put("title", event.name).put("location", event.place.orEmpty()).put("starts_at", offset(event.from.time)).put("ends_at", offset(event.to.time))
        .put("timetable_id", event.timetableId).put("timetable_name", db.timetableDao().getTimetableByIdSync(event.timetableId)?.name.orEmpty())
        .put("source", event.source).put("event_type", event.type.name)

    fun query(filter: JSONObject): JSONObject = synchronized(PlannerAccess.lock) {
        val allowed = access.tables()
        if (allowed.isEmpty()) return@synchronized response("authorization_required", "请在 HITA 兼容版中选择允许编辑的课表。").put("records", JSONArray())
        try {
            val tables = JSONArray()
            allowed.forEach { id ->
                val table = db.timetableDao().getTimetableByIdSync(id)
                if (table != null || id == PlannerAccess.defaultTableId) tables.put(JSONObject().put("id", id).put("name", table?.name ?: "随手记事"))
            }
            val id = text(filter, "record_id", text(filter, "id"))
            val now = System.currentTimeMillis()
            val from = text(filter, "starts_at", text(filter, "from")).let { if (it.isBlank()) now - 30L*86400000 else time(it) }
            val to = text(filter, "ends_at", text(filter, "to")).let { if (it.isBlank()) now + 366L*86400000 else time(it) }
            require(to > from && to - from <= 5L*366*86400000)
            val keyword = text(filter, "keyword", text(filter, "title")).trim()
            val selectedTable = text(filter, "timetable_id")
            if (selectedTable.isNotBlank() && selectedTable !in allowed) return@synchronized response("forbidden", "该课表尚未授权。").put("records", JSONArray())
            val candidates = if (id.isNotBlank()) db.eventItemDao().getEventInIdsSync(listOf(id)) else allowed
                .filter { selectedTable.isBlank() || it == selectedTable }.flatMap { db.eventItemDao().getEventsOfTimetableDuringSync(it, from, to) }
            val filtered = candidates.filter { it.timetableId in allowed && it.type != EventItem.TYPE.TAG &&
                (selectedTable.isBlank() || it.timetableId == selectedTable) && (keyword.isBlank() || it.name.contains(keyword, ignoreCase = true)) }.sortedBy { it.from.time }
            val records = JSONArray(); filtered.take(200).forEach { records.put(public(it)) }
            response("ok", "已查询 HITA 本地日程。").put("records", records).put("timetables", tables).put("truncated", filtered.size > 200)
        } catch (_: IllegalArgumentException) { response("invalid", "请提供有效的查询日期范围。").put("records", JSONArray()) }
        catch (_: java.time.format.DateTimeParseException) { response("invalid", "查询时间需要包含日期、时间和时区。").put("records", JSONArray()) }
    }

    fun apply(action: JSONObject): JSONObject = synchronized(PlannerAccess.lock) {
        val allowed = access.tables()
        if (allowed.isEmpty()) return@synchronized response("authorization_required", "请先在 HITA 兼容版中授权课表。").put("written", false)
        val operation = text(action, "operation")
        if (operation !in setOf("create", "update", "delete")) return@synchronized response("invalid", "不支持的日程操作。").put("written", false)
        try {
            var result = response("error", "日程操作未完成。")
            db.runInTransaction {
                val id = text(action, "record_id", text(action, "id"))
                if (id.isBlank() || id.length > 128) { result = response("invalid", "日程操作需要稳定的记录编号。").put("written", false); return@runInTransaction }
                val existing = db.eventItemDao().getEventInIdsSync(listOf(id)).firstOrNull()
                val oldTableId = existing?.timetableId
                val oldSubjectId = existing?.subjectId
                val oldFrom = existing?.from?.time
                val oldTo = existing?.to?.time
                if (existing != null && existing.timetableId !in allowed) { result = response("forbidden", "该日程所属课表尚未授权。").put("written", false); return@runInTransaction }
                if (existing?.type == EventItem.TYPE.TAG) { result = response("unsupported", "课表标记需在 HITA 内编辑。").put("written", false); return@runInTransaction }
                if (operation != "create") {
                    if (existing == null) { result = response("not_found", "日程已被删除或不存在，请重新查询。").put("written", false); return@runInTransaction }
                    val expected = text(action, "fingerprint", text(action, "expected_fingerprint"))
                    if (expected.isBlank() || expected != fingerprint(existing)) {
                        result = response("conflict", "日程在 HITA 中发生了变化，请重新查询后确认修改。").put("written", false).put("record", public(existing)); return@runInTransaction
                    }
                    if (operation == "delete") {
                        db.eventItemDao().deleteEventsInIdsSync(listOf(id)); check(db.eventItemDao().getEventInIdsSync(listOf(id)).isEmpty())
                        result = response("ok", "已从 HITA 删除日程。").put("written", true).put("record", public(existing)); return@runInTransaction
                    }
                }
                val item = action.optJSONObject("record") ?: throw IllegalArgumentException("Missing record")
                val kind = text(item, "kind", if (existing?.type == EventItem.TYPE.CLASS) "course" else "schedule")
                require(kind in setOf("course", "schedule"))
                require(text(item, "recurrence_description").isBlank() && text(item, "recurrence").isBlank())
                require(item.isNull("reminder_minutes") && item.isNull("reminders")); require(text(item, "body").isBlank())
                val title = text(item, "title", existing?.name.orEmpty()).trim(); require(title.isNotBlank() && title.length <= 200)
                val place = text(item, "location", existing?.place.orEmpty()).trim(); require(place.length <= 500)
                val from = text(item, "starts_at").let { if (it.isBlank() && existing != null) existing.from.time else time(it) }
                val to = text(item, "ends_at").let { if (it.isBlank() && existing != null) existing.to.time else time(it) }; require(to > from)
                val tableId = text(item, "timetable_id", existing?.timetableId ?: PlannerAccess.defaultTableId)
                if (tableId !in allowed) { result = response("forbidden", "目标课表尚未授权，请选择已授权的课表。").put("written", false); return@runInTransaction }
                if (operation == "create" && existing != null) {
                    if (existing.name == title && existing.place.orEmpty() == place && existing.from.time == from && existing.to.time == to &&
                        existing.timetableId == tableId && existing.type == (if (kind == "course") EventItem.TYPE.CLASS else EventItem.TYPE.OTHER))
                        result = response("ok", "该日程已写入 HITA。").put("written", true).put("duplicate", true).put("record", public(existing))
                    else result = response("conflict", "同编号日程已存在且内容不同，请重新查询确认。").put("written", false).put("record", public(existing))
                    return@runInTransaction
                }
                val table = db.timetableDao().getTimetableByIdSync(tableId)
                if (table == null && tableId != PlannerAccess.defaultTableId) { result = response("not_found", "目标课表已被删除，请重新授权。").put("written", false); return@runInTransaction }
                val day = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).toLocalDate()
                val monday = day.minusDays((day.dayOfWeek.value - 1).toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val writableTable = table ?: Timetable().apply { this.id = tableId; name = "随手记事"; startTime = Timestamp(monday); endTime = Timestamp(to) }
                // Imported timetable dates define academic weeks; never shift those while editing one event.
                if (tableId == PlannerAccess.defaultTableId) {
                    writableTable.startTime = Timestamp(minOf(writableTable.startTime.time, monday))
                    writableTable.endTime = Timestamp(maxOf(writableTable.endTime.time, to))
                    db.timetableDao().saveTimetableSync(writableTable)
                }
                val event = existing ?: EventItem().apply { this.id = id; source = EventItem.SOURCE_AGENT }
                val oldType = event.type
                event.timetableId = tableId; event.name = title; event.place = place; event.from = Timestamp(from); event.to = Timestamp(to)
                // Keep EXAM on ordinary edits; schedule describes a non-course event.
                if (operation == "create" || kind == "course" || oldType == EventItem.TYPE.CLASS)
                    event.type = if (kind == "course") EventItem.TYPE.CLASS else EventItem.TYPE.OTHER
                if (event.type == EventItem.TYPE.CLASS) {
                    if (existing == null || event.subjectId.isBlank())
                        event.subjectId = UUID.nameUUIDFromBytes("chat-to-plan:$tableId:$title".toByteArray(Charsets.UTF_8)).toString()
                    else if (oldTableId != tableId)
                        event.subjectId = UUID.nameUUIDFromBytes("chat-to-plan:move-subject:$tableId:$oldSubjectId".toByteArray(Charsets.UTF_8)).toString()
                    if (db.subjectDao().getSubjectsInIdsSync(listOf(event.subjectId)).isEmpty()) {
                        val source = oldSubjectId?.let { db.subjectDao().getSubjectsInIdsSync(listOf(it)).firstOrNull() }
                        db.subjectDao().saveSubjectSync(TermSubject().apply {
                            this.id = event.subjectId; this.name = source?.name ?: title; timetableId = tableId
                            if (source != null) {
                                type = source.type; field = source.field; selectCategory = source.selectCategory; nature = source.nature
                                credit = source.credit; school = source.school; countInSPA = source.countInSPA
                                code = source.code; key = source.key; createdAt = source.createdAt; color = source.color
                            }
                        })
                    }
                } else event.subjectId = ""
                if (existing == null || oldTableId != tableId || oldFrom != from || oldTo != to || oldType != event.type) {
                    event.fromNumber = 0; event.lastNumber = 0
                }
                db.eventItemDao().insertEventSync(event)
                val readback = db.eventItemDao().getEventInIdsSync(listOf(id)).single(); check(fingerprint(readback) == fingerprint(event))
                result = response("ok", if (operation == "create") "已写入 HITA 日程。" else "已修改 HITA 日程。").put("written", true).put("record", public(readback))
            }
            result
        } catch (_: IllegalArgumentException) {
            response("unsupported", "请补充标题、有效起止时间；当前 HITA 接口仅支持单次日程，不支持正文、重复规则或额外提醒。").put("written", false)
        } catch (_: java.time.format.DateTimeParseException) { response("invalid", "起止时间需要包含具体日期、时间和时区。").put("written", false) }
    }
}
