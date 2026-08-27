package com.listener.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.listener.app.data.session.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class SessionDatabaseTest {
    private lateinit var db: SessionDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SessionDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()
    @Test fun editingPreservesOriginalSegmentsAndConfirmedDeleteCascades() = runBlocking {
        val dao = db.sessions(); val id = dao.insert(SessionEntity(title = "Original", startedAt = 1))
        dao.insertSegment(TranscriptSegmentEntity(sessionId = id, startMs = 0, endMs = 2, originalText = "原文", final = true))
        dao.insertSummary(SummaryEntity(sessionId = id, createdAt = 2, globalContext = "Meeting", detailsJson = "[\"Detail one\",\"Detail two\"]"))
        dao.edit(id, "Edited", "編輯文字")
        Assert.assertEquals("原文", dao.segments(id).single().originalText)
        Assert.assertEquals("編輯文字", SessionRepository(dao).export(id))
        SessionRepository(dao).deleteConfirmed(id, true)
        Assert.assertTrue(dao.segments(id).isEmpty()); Assert.assertTrue(dao.summaries(id).isEmpty())
    }
    @Test fun retentionDeletesOnlyCompletedOldSessions() = runBlocking {
        val dao = db.sessions()
        val old = dao.insert(SessionEntity(title = "Old", startedAt = 1, endedAt = 2))
        val active = dao.insert(SessionEntity(title = "Active", startedAt = 1))
        dao.deleteEndedBefore(3)
        Assert.assertNull(dao.session(old))
        Assert.assertNotNull(dao.session(active))
    }

    @Test fun summaryTraceExportsPersistedSummaries() = runBlocking {
        val dao = db.sessions()
        val id = dao.insert(SessionEntity(title = "Traceable", startedAt = 1, endedAt = 3, updateIntervalSeconds = 3))
        dao.insertSummary(SummaryEntity(sessionId = id, createdAt = 2, globalContext = "Lunch planning", detailsJson = "[\"Noodles are preferred\",\"The decision is open\"]"))

        val trace = SessionRepository(dao).summaryTrace(id)

        Assert.assertTrue(trace.contains("Listener summary trace"))
        Assert.assertTrue(trace.contains("Traceable"))
        Assert.assertTrue(trace.contains("Stored interval: 3s"))
        Assert.assertTrue(trace.contains("Lunch planning"))
        Assert.assertTrue(trace.contains("- Noodles are preferred"))
        Assert.assertTrue(trace.contains("- The decision is open"))
    }

    @Test fun traceShareIntentGrantsReadAccessToTraceUri() {
        val uri = Uri.parse("content://com.listener.app.fileprovider/summary_trace_shares/listener-summary-trace-1.txt")

        val intent = buildTraceShareIntent(uri, "listener-summary-trace-1.txt")

        Assert.assertEquals(Intent.ACTION_SEND, intent.action)
        Assert.assertEquals("text/plain", intent.type)
        Assert.assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        Assert.assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
        Assert.assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
    }
}
