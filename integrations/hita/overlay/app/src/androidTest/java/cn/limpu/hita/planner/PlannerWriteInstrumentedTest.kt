package cn.limpu.hita.planner

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Uses real Android JSON and Room; never touches the user's persistent database. */
@RunWith(AndroidJUnit4::class)
class PlannerWriteInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var sync: PlannerSync
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        sync=PlannerSync(context,db)
    }
    @After fun close() { db.close() }
    private fun task()=JSONObject("""{"id":"calendar-task-1","payload":{"kind":"course","title":"高数","location":"A101","starts_at":"2026-10-14T15:00:00+08:00","ends_at":"2026-10-14T16:00:00+08:00","clarification":null,"recurrence_description":"","reminder_minutes":null}}""")

    @Test fun courseIsVisibleInRoomAndRetryDoesNotDuplicate() {
        val id=sync.writeAndReadback("https://planner.example",task())
        assertEquals(id,sync.writeAndReadback("https://planner.example",task()))
        val event=db.eventItemDao().getEventInIdsSync(listOf(id)).single()
        assertEquals("高数",event.name); assertEquals("A101",event.place)
        assertEquals(EventItem.TYPE.CLASS,event.type)
        assertEquals(Instant.parse("2026-10-14T07:00:00Z").toEpochMilli(),event.from.time)
        assertEquals(Instant.parse("2026-10-14T08:00:00Z").toEpochMilli(),event.to.time)
        assertEquals(1,db.eventItemDao().countEventsOfTimetableSync(event.timetableId))
        assertEquals("对话计划",db.timetableDao().getTimetableByIdSync(event.timetableId)?.name)
        assertEquals("高数",db.subjectDao().getSubjectsInIdsSync(listOf(event.subjectId)).single().name)
    }
    @Test fun retryNeverOverwritesAnEditedLocalEvent() {
        val id=sync.writeAndReadback("https://planner.example",task())
        val event=db.eventItemDao().getEventInIdsSync(listOf(id)).single()
        event.name="手机上手动修改"
        db.eventItemDao().insertEventSync(event)
        assertThrows(IllegalStateException::class.java) { sync.writeAndReadback("https://planner.example",task()) }
        assertEquals("手机上手动修改",db.eventItemDao().getEventInIdsSync(listOf(id)).single().name)
    }
    @Test fun unsupportedReminderProducesNoEvent() {
        val value=task(); value.getJSONObject("payload").put("reminder_minutes",10)
        assertThrows(IllegalArgumentException::class.java) { sync.writeAndReadback("https://planner.example",value) }
        assertTrue(db.eventItemDao().getEventsDuringSync(0,Long.MAX_VALUE).isEmpty())
    }
}
