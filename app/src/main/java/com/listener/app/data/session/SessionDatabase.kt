package com.listener.app.data.session

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions") data class SessionEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val editedTranscript: String? = null, val startedAt: Long, val endedAt: Long? = null, val updateIntervalSeconds: Int = 5)
@Entity(tableName = "segments", foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sessionId")])
data class TranscriptSegmentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: Long, val startMs: Long, val endMs: Long, val originalText: String, val displayText: String? = null, val final: Boolean)
@Entity(tableName = "summaries", foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sessionId")])
data class SummaryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: Long, val createdAt: Long, val globalContext: String, val detailsJson: String)
@Entity(tableName = "models") data class ModelMetadataEntity(@PrimaryKey val modelId: String, val version: String, val manifestPath: String, val binaryPath: String, val sha256: String, val bytes: Long, val installedAt: Long)

@Dao interface SessionDao {
    @Insert suspend fun insert(session: SessionEntity): Long
    @Insert suspend fun insertSegment(segment: TranscriptSegmentEntity): Long
    @Insert suspend fun insertSummary(summary: SummaryEntity): Long
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC") fun history(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM segments WHERE sessionId=:sessionId ORDER BY startMs") suspend fun segments(sessionId: Long): List<TranscriptSegmentEntity>
    @Query("UPDATE sessions SET title=:title, editedTranscript=:text WHERE id=:id") suspend fun edit(id: Long, title: String, text: String?)
    @Query("DELETE FROM sessions WHERE id=:id") suspend fun delete(id: Long)
}

@Database(entities = [SessionEntity::class, TranscriptSegmentEntity::class, SummaryEntity::class, ModelMetadataEntity::class], version = 1, exportSchema = true)
abstract class SessionDatabase : RoomDatabase() { abstract fun sessions(): SessionDao }

class SessionRepository(private val dao: SessionDao) {
    fun history() = dao.history()
    suspend fun create(title: String, interval: Int) = dao.insert(SessionEntity(title = title, startedAt = System.currentTimeMillis(), updateIntervalSeconds = interval))
    suspend fun edit(id: Long, title: String, displayText: String?) = dao.edit(id, title, displayText) // original segment rows remain immutable
    suspend fun deleteConfirmed(id: Long, confirmed: Boolean) { if (confirmed) dao.delete(id) }
    suspend fun export(id: Long): String = dao.segments(id).joinToString("\n") { "[${it.startMs}-${it.endMs}] ${it.displayText ?: it.originalText}" }
}
