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
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertModel(model: ModelMetadataEntity)
    @Query("SELECT * FROM models ORDER BY installedAt DESC") fun models(): Flow<List<ModelMetadataEntity>>
    @Query("DELETE FROM models WHERE modelId=:id") suspend fun deleteModel(id: String)
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC") fun history(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id=:id") suspend fun session(id: Long): SessionEntity?
    @Query("SELECT * FROM segments WHERE sessionId=:sessionId ORDER BY startMs") suspend fun segments(sessionId: Long): List<TranscriptSegmentEntity>
    @Query("SELECT * FROM summaries WHERE sessionId=:sessionId ORDER BY createdAt") suspend fun summaries(sessionId: Long): List<SummaryEntity>
    @Query("UPDATE sessions SET title=:title, editedTranscript=:text WHERE id=:id") suspend fun edit(id: Long, title: String, text: String?)
    @Query("UPDATE sessions SET endedAt=:endedAt WHERE id=:id") suspend fun finish(id: Long, endedAt: Long)
    @Query("UPDATE sessions SET endedAt=:endedAt WHERE endedAt IS NULL") suspend fun finishInterrupted(endedAt: Long)
    @Query("DELETE FROM sessions WHERE id=:id") suspend fun delete(id: Long)
    @Query("DELETE FROM sessions WHERE endedAt IS NOT NULL AND endedAt < :cutoff") suspend fun deleteEndedBefore(cutoff: Long)
}

@Database(entities = [SessionEntity::class, TranscriptSegmentEntity::class, SummaryEntity::class, ModelMetadataEntity::class], version = 1, exportSchema = true)
abstract class SessionDatabase : RoomDatabase() { abstract fun sessions(): SessionDao }

class SessionRepository(private val dao: SessionDao) {
    fun history() = dao.history()
    suspend fun create(title: String, interval: Int) = dao.insert(SessionEntity(title = title, startedAt = System.currentTimeMillis(), updateIntervalSeconds = interval))
    suspend fun edit(id: Long, title: String, displayText: String?) = dao.edit(id, title, displayText) // original segment rows remain immutable
    suspend fun appendSegment(sessionId: Long, result: com.listener.app.speech.TranscriptResult) = dao.insertSegment(
        TranscriptSegmentEntity(sessionId = sessionId, startMs = result.startMs, endMs = result.endMs, originalText = result.text, final = result.isFinal)
    )
    suspend fun appendSummary(sessionId: Long, context: com.listener.app.context.ListeningContext) = dao.insertSummary(
        SummaryEntity(sessionId = sessionId, createdAt = System.currentTimeMillis(), globalContext = context.globalContext, detailsJson = kotlinx.serialization.json.JsonArray(context.details.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString())
    )
    suspend fun finish(id: Long) = dao.finish(id, System.currentTimeMillis())
    suspend fun deleteConfirmed(id: Long, confirmed: Boolean) { if (confirmed) dao.delete(id) }
    suspend fun export(id: Long): String {
        val edited = dao.session(id)?.editedTranscript
        if (!edited.isNullOrBlank()) return edited
        return dao.segments(id).joinToString("\n") { "[${it.startMs}-${it.endMs}] ${it.displayText ?: it.originalText}" }
    }
}
