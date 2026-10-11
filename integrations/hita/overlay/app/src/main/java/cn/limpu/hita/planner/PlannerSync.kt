package cn.limpu.hita.planner

import android.content.Context
import cn.limpu.hita.R
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.sql.Timestamp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

class PlannerUnauthorized: Exception()

class PlannerSync(private val context: Context, private val database: AppDatabase? = null) {
    private val http = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(35,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    fun runOnce(waitSeconds: Int = 0) {
        val config = PlannerConfig(context)
        if (!config.enabled) return
        val server = PlannerConfig.validateServer(config.server)
        val token = config.token
        val clientId = config.clientId
        repeat(10) {
            if (!PlannerConfig(context).enabled) return
            val claim = call(server,token,"/v1/delivery/hita/claim?wait=$waitSeconds",JSONObject().put("client_id",clientId))
            if (claim.isNull("task") || !PlannerConfig(context).enabled) return
            val task = claim.getJSONObject("task")
            val eventId = writeAndReadback(server,task)
            call(server,token,"/v1/delivery/${task.getString("id")}/ack",JSONObject()
                .put("client_id",clientId).put("lease_token",claim.getString("lease_token"))
                .put("revision",task.getInt("revision")).put("external_id",eventId))
        }
    }

    private fun call(server: String, token: String, path: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url(server+path).header("Authorization","Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw PlannerUnauthorized()
            check(response.isSuccessful)
            val raw = response.body?.string() ?: error("Empty response")
            check(raw.length <= 262144)
            return JSONObject(raw)
        }
    }
    private fun stable(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    internal fun writeAndReadback(server: String, task: JSONObject): String {
        val item = task.getJSONObject("payload")
        require(item.optString("kind") in setOf("course","schedule"))
        require(item.isNull("clarification") && (item.isNull("recurrence_description") || item.optString("recurrence_description").isBlank()) && item.isNull("reminder_minutes"))
        val from = OffsetDateTime.parse(item.getString("starts_at")).toInstant().toEpochMilli()
        val to = OffsetDateTime.parse(item.getString("ends_at")).toInstant().toEpochMilli()
        require(to > from)
        val eventId = stable("chat-planner:$server:${task.getString("id")}")
        val tableId = stable("chat-planner-table:$server")
        val event = EventItem().apply {
            id=eventId; timetableId=tableId; name=item.getString("title"); place=if(item.isNull("location")) "" else item.getString("location")
            type=if(item.getString("kind")=="course") EventItem.TYPE.CLASS else EventItem.TYPE.OTHER
            source=EventItem.SOURCE_AGENT; this.from=Timestamp(from); this.to=Timestamp(to)
            fromNumber=0; lastNumber=0
            subjectId=if(type==EventItem.TYPE.CLASS) stable("chat-planner-subject:$tableId:$name") else ""
        }
        val db = database ?: AppDatabase.getDatabase(context)
        db.runInTransaction {
            val existing = db.eventItemDao().getEventInIdsSync(listOf(eventId)).firstOrNull()
            if (existing != null) {
                // Never overwrite a user's local edits after an uncertain network acknowledgment.
                check(matches(existing,event))
            } else {
                val zone=ZoneId.systemDefault()
                val day=java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
                val monday=day.minusDays((day.dayOfWeek.value-1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
                val table=db.timetableDao().getTimetableByIdSync(tableId) ?: Timetable().apply {
                    id=tableId; name=context.getString(R.string.planner_timetable); startTime=Timestamp(monday); endTime=Timestamp(to)
                }
                table.startTime=Timestamp(minOf(table.startTime.time,monday))
                table.endTime=Timestamp(maxOf(table.endTime.time,to))
                db.timetableDao().saveTimetableSync(table)
                if (event.type==EventItem.TYPE.CLASS && db.subjectDao().getSubjectsInIdsSync(listOf(event.subjectId)).isEmpty()) {
                    db.subjectDao().saveSubjectSync(TermSubject().apply { id=event.subjectId; name=event.name; timetableId=tableId })
                }
                db.eventItemDao().insertEventSync(event)
            }
            val readback=db.eventItemDao().getEventInIdsSync(listOf(eventId)).single()
            check(matches(readback,event))
        }
        return eventId
    }
    private fun matches(a: EventItem,b: EventItem) = a.id==b.id && a.source==b.source && a.name==b.name && a.place==b.place &&
        a.type==b.type && a.from==b.from && a.to==b.to && a.timetableId==b.timetableId && a.subjectId==b.subjectId
}
