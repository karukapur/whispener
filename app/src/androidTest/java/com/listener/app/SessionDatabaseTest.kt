package com.listener.app

import android.content.Context
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
}
