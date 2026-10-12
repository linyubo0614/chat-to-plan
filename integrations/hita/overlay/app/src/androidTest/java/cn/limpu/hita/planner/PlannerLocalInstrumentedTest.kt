package cn.limpu.hita.planner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android JSON and Room. CRUD never touches the user's persistent database. */
@RunWith(AndroidJUnit4::class)
class PlannerLocalInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlannerLocalRepository
    private lateinit var access: PlannerAccess
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = PlannerLocalRepository(context, db); access = PlannerAccess(context); access.revoke()
    }
    @After fun close() { access.revoke(); db.close() }
    private fun create() = JSONObject("""{"operation":"create","record_id":"local-course-1","record":{"kind":"course","title":"高数","location":"A101","starts_at":"2026-10-14T15:00:00+08:00","ends_at":"2026-10-14T16:00:00+08:00"}}""")
    private fun grant() = access.grant(setOf(PlannerAccess.defaultTableId))
    private fun record() = repository.query(JSONObject().put("record_id", "local-course-1")).getJSONArray("records").getJSONObject(0)

    @Test fun noConsentWritesNothingAndRevocationTakesEffect() {
        assertEquals("authorization_required", repository.apply(create()).getString("status"))
        assertTrue(db.eventItemDao().getEventsDuringSync(0, Long.MAX_VALUE).isEmpty())
        grant(); assertEquals("ok", repository.apply(create()).getString("status")); access.revoke()
        assertEquals("authorization_required", repository.query(JSONObject()).getString("status"))
        assertEquals("authorization_required", repository.apply(create()).getString("status"))
        assertEquals(1, db.eventItemDao().getEventsDuringSync(0, Long.MAX_VALUE).size)
    }
    @Test fun courseCrudAndCreateRetryUseRealRoom() {
        grant(); assertTrue(repository.apply(create()).getBoolean("written"))
        assertTrue(repository.apply(create()).getBoolean("duplicate"))
        val first = record()
        assertEquals("A101", first.getString("location"))
        val event = db.eventItemDao().getEventInIdsSync(listOf("local-course-1")).single()
        assertEquals(EventItem.TYPE.CLASS, event.type)
        assertEquals(Instant.parse("2026-10-14T07:00:00Z").toEpochMilli(), event.from.time)
        assertEquals(1, db.eventItemDao().countEventsOfTimetableSync(event.timetableId))
        val update = JSONObject().put("operation", "update").put("record_id", event.id).put("fingerprint", first.getString("fingerprint"))
            .put("record", JSONObject().put("title", "高数答疑").put("location", "B202"))
        assertEquals("ok", repository.apply(update).getString("status"))
        assertEquals("高数答疑", record().getString("title"))
        assertEquals("conflict", repository.apply(update).getString("status"))
        val deletion = JSONObject().put("operation", "delete").put("record_id", event.id).put("fingerprint", record().getString("fingerprint"))
        assertTrue(repository.apply(deletion).getBoolean("written"))
        assertTrue(db.eventItemDao().getEventInIdsSync(listOf(event.id)).isEmpty())
    }
    @Test fun directHitaEditPreventsStaleUpdateAndDelete() {
        grant(); repository.apply(create()); val first = record()
        val event = db.eventItemDao().getEventInIdsSync(listOf("local-course-1")).single()
        event.teacher = "手动调整老师"; db.eventItemDao().insertEventSync(event)
        for (operation in listOf("update", "delete")) {
            val action = JSONObject().put("operation", operation).put("record_id", event.id).put("fingerprint", first.getString("fingerprint"))
                .put("record", JSONObject().put("title", "不应写入"))
            assertEquals("conflict", repository.apply(action).getString("status"))
        }
        assertEquals("高数", record().getString("title"))
    }
    @Test fun existingTimetableAuthorizationIsScopedAndDoesNotMoveSemesterStart() {
        val table = Timetable().apply { id = "existing-table"; name = "大一课表"; startTime = Timestamp(1000); endTime = Timestamp(9000000000000) }
        db.timetableDao().saveTimetableSync(table)
        val event = EventItem().apply {
            id = "existing-event"; timetableId = table.id; name = "班会"; type = EventItem.TYPE.OTHER; source = EventItem.SOURCE_MANUAL
            from = Timestamp(Instant.parse("2026-10-14T07:00:00Z").toEpochMilli()); to = Timestamp(from.time + 3600000); teacher = "辅导员"
        }
        db.eventItemDao().insertEventSync(event); grant()
        assertEquals(0, repository.query(JSONObject().put("record_id", event.id)).getJSONArray("records").length())
        assertEquals("forbidden", repository.apply(JSONObject().put("operation", "delete").put("record_id", event.id).put("fingerprint", repository.fingerprint(event))).getString("status"))
        access.grant(setOf(table.id))
        val original = repository.query(JSONObject().put("record_id", event.id)).getJSONArray("records").getJSONObject(0)
        assertEquals("ok", repository.apply(JSONObject().put("operation", "update").put("record_id", event.id).put("fingerprint", original.getString("fingerprint"))
            .put("record", JSONObject().put("title", "改期班会"))).getString("status"))
        assertEquals(1000, db.timetableDao().getTimetableByIdSync(table.id)!!.startTime.time)
        assertEquals(EventItem.SOURCE_MANUAL, db.eventItemDao().getEventInIdsSync(listOf(event.id)).single().source)
        assertEquals("辅导员", db.eventItemDao().getEventInIdsSync(listOf(event.id)).single().teacher)
    }
    @Test fun unsupportedReminderAndRecurrenceDoNotCreateEvents() {
        grant()
        for (field in listOf("reminder_minutes", "recurrence_description", "body")) {
            val action = create(); action.getJSONObject("record").put(field, if (field == "reminder_minutes") 10 else "不能丢失的信息")
            assertEquals("unsupported", repository.apply(action).getString("status"))
        }
        assertTrue(db.eventItemDao().getEventsDuringSync(0, Long.MAX_VALUE).isEmpty())
    }
    @Test fun courseEditsKeepSubjectMetadataAndClearOldPeriodNumbersWhenMoved() {
        grant(); repository.apply(create())
        val event = db.eventItemDao().getEventInIdsSync(listOf("local-course-1")).single()
        event.fromNumber = 1; event.lastNumber = 2; db.eventItemDao().insertEventSync(event)
        val subject = db.subjectDao().getSubjectsInIdsSync(listOf(event.subjectId)).single()
        subject.credit = 5f; subject.color = 12345; subject.code = "MATH001"; db.subjectDao().saveSubjectSync(subject)
        val update = JSONObject().put("operation", "update").put("record_id", event.id).put("fingerprint", repository.fingerprint(event))
            .put("record", JSONObject().put("title", "高数答疑").put("starts_at", "2026-10-14T17:00:00+08:00").put("ends_at", "2026-10-14T18:00:00+08:00"))
        assertEquals("ok", repository.apply(update).getString("status"))
        val changed = db.eventItemDao().getEventInIdsSync(listOf(event.id)).single()
        assertEquals(event.subjectId, changed.subjectId); assertEquals(0, changed.fromNumber); assertEquals(0, changed.lastNumber)
        val unchangedSubject = db.subjectDao().getSubjectsInIdsSync(listOf(changed.subjectId)).single()
        assertEquals(5f, unchangedSubject.credit, 0f); assertEquals(12345, unchangedSubject.color); assertEquals("MATH001", unchangedSubject.code)
    }
    @Test fun realBoundServiceReportsMissingAuthorization() {
        val ready = CountDownLatch(1); var endpoint: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { endpoint = service; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { endpoint = null }
        }
        assertTrue(context.bindService(Intent(context, PlannerLocalService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                input.writeInterfaceToken(PlannerAccess.descriptor); input.writeString("{}")
                assertTrue(endpoint!!.transact(PlannerAccess.queryTransaction, input, output, 0)); output.readException()
                assertEquals("authorization_required", JSONObject(output.readString()!!).getString("status"))
            } finally { input.recycle(); output.recycle() }
        } finally { context.unbindService(connection) }
        val info = context.packageManager.getServiceInfo(ComponentName(context, PlannerLocalService::class.java), 0)
        assertTrue(info.exported); assertEquals(PlannerAccess.permission, info.permission)
    }
}
