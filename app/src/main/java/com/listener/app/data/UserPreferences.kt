package com.listener.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.listenerDataStore by preferencesDataStore("listener_preferences")

const val OPENROUTER_FREE_ROUTER_MODEL_ID = "openrouter/free"
const val DEFAULT_OPENROUTER_MODEL_ID = OPENROUTER_FREE_ROUTER_MODEL_ID
const val GROQ_GPT_OSS_20B_REMOTE_MODEL_ID = "groq/openai/gpt-oss-20b"
const val DEFAULT_REMOTE_MODEL_ID = GROQ_GPT_OSS_20B_REMOTE_MODEL_ID
const val GROQ_MIN_SUMMARY_CADENCE_MILLIS = 2_000
const val MIN_SUMMARY_CADENCE_MILLIS = 500
const val MAX_SUMMARY_CADENCE_MILLIS = 10_000
const val SUMMARY_CADENCE_STEP_MILLIS = 500
const val DEFAULT_SUMMARY_CADENCE_MILLIS = 5_000

data class ListenerPreferences(
    val onboardingComplete: Boolean = false,
    val remoteEnabled: Boolean = true,
    val retentionDays: Int = 30,
    val selectedModel: String? = DEFAULT_REMOTE_MODEL_ID,
    val selectedLocalModelId: String? = null,
    val transcriptionEngine: TranscriptionEngine = TranscriptionEngine.WHISPER_CPP,
    val whisperWorkProfile: WhisperWorkProfile = WhisperWorkProfile.RESPONSIVE,
    val summaryCadenceMillis: Int = DEFAULT_SUMMARY_CADENCE_MILLIS,
) {
    val summaryCadenceSeconds: Int
        get() = summaryCadenceMillis.toSummaryIntervalSeconds()
}

enum class TranscriptionEngine(val id: String) {
    WHISPER_CPP("whisper_cpp"),
    ANDROID_ON_DEVICE("android_on_device"),
    SHERPA_ONNX("sherpa_onnx");

    companion object {
        fun fromId(id: String?): TranscriptionEngine = entries.firstOrNull { it.id == id } ?: WHISPER_CPP
    }
}

enum class WhisperWorkProfile(val id: String) {
    RESPONSIVE("responsive"),
    CONSERVATIVE("conservative");

    companion object {
        fun fromId(id: String?): WhisperWorkProfile = entries.firstOrNull { it.id == id } ?: RESPONSIVE
    }
}

class UserPreferences(private val context: Context) {
    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val remote = booleanPreferencesKey("remote_enabled")
        val remoteUserSet = booleanPreferencesKey("remote_enabled_user_set")
        val retention = intPreferencesKey("retention_days")
        val model = stringPreferencesKey("openrouter_model")
        val localModel = stringPreferencesKey("local_whisper_model")
        val transcriptionEngine = stringPreferencesKey("transcription_engine")
        val whisperWorkProfile = stringPreferencesKey("whisper_work_profile")
        val cadenceSeconds = intPreferencesKey("summary_cadence_seconds")
        val cadenceMillis = intPreferencesKey("summary_cadence_millis")
    }

    val values: Flow<ListenerPreferences> = context.listenerDataStore.data.map { prefs ->
        val remoteUserSet = prefs[Keys.remoteUserSet] ?: false
        ListenerPreferences(
            onboardingComplete = prefs[Keys.onboarding] ?: false,
            remoteEnabled = if (remoteUserSet) prefs[Keys.remote] ?: true else true,
            retentionDays = prefs[Keys.retention] ?: 30,
            selectedModel = prefs[Keys.model] ?: DEFAULT_REMOTE_MODEL_ID,
            selectedLocalModelId = prefs[Keys.localModel],
            transcriptionEngine = TranscriptionEngine.fromId(prefs[Keys.transcriptionEngine]),
            whisperWorkProfile = WhisperWorkProfile.fromId(prefs[Keys.whisperWorkProfile]),
            summaryCadenceMillis = cadenceMillisPreference(
                storedMillis = prefs[Keys.cadenceMillis],
                legacySeconds = prefs[Keys.cadenceSeconds],
            ),
        )
    }

    suspend fun completeOnboarding(remoteEnabled: Boolean, retentionDays: Int) = context.listenerDataStore.edit {
        it[Keys.onboarding] = true
        it[Keys.remote] = remoteEnabled
        it[Keys.remoteUserSet] = true
        it[Keys.retention] = retentionDays.coerceIn(0, 90)
        it[Keys.localModel] = "base"
    }

    suspend fun setRemoteEnabled(enabled: Boolean) = context.listenerDataStore.edit {
        it[Keys.remote] = enabled
        it[Keys.remoteUserSet] = true
    }
    suspend fun setRetentionDays(days: Int) = context.listenerDataStore.edit { it[Keys.retention] = days.coerceIn(0, 90) }
    suspend fun setSelectedModel(id: String, minimumCadenceMillis: Int = MIN_SUMMARY_CADENCE_MILLIS) = context.listenerDataStore.edit {
        it[Keys.model] = id
        val currentCadence = cadenceMillisPreference(it[Keys.cadenceMillis], it[Keys.cadenceSeconds])
        if (currentCadence < minimumCadenceMillis) {
            it[Keys.cadenceMillis] = minimumCadenceMillis.snapSummaryCadenceMillis()
        }
    }
    suspend fun clearSelectedModel() = context.listenerDataStore.edit { it.remove(Keys.model) }
    suspend fun setSelectedLocalModel(id: String) = context.listenerDataStore.edit { it[Keys.localModel] = id }
    suspend fun clearSelectedLocalModel() = context.listenerDataStore.edit { it.remove(Keys.localModel) }
    suspend fun setTranscriptionEngine(engine: TranscriptionEngine) = context.listenerDataStore.edit { it[Keys.transcriptionEngine] = engine.id }
    suspend fun setWhisperWorkProfile(profile: WhisperWorkProfile) = context.listenerDataStore.edit { it[Keys.whisperWorkProfile] = profile.id }
    suspend fun setCadence(seconds: Int) = setCadenceMillis(seconds * 1_000)
    suspend fun setCadenceMillis(millis: Int) = context.listenerDataStore.edit { it[Keys.cadenceMillis] = millis.snapSummaryCadenceMillis() }
}

fun cadenceMillisPreference(storedMillis: Int?, legacySeconds: Int?): Int =
    (storedMillis ?: legacySeconds?.times(1_000) ?: DEFAULT_SUMMARY_CADENCE_MILLIS).snapSummaryCadenceMillis()

fun Int.snapSummaryCadenceMillis(): Int {
    val clamped = coerceIn(MIN_SUMMARY_CADENCE_MILLIS, MAX_SUMMARY_CADENCE_MILLIS)
    val offset = clamped - MIN_SUMMARY_CADENCE_MILLIS
    val snappedOffset = ((offset + SUMMARY_CADENCE_STEP_MILLIS / 2) / SUMMARY_CADENCE_STEP_MILLIS) * SUMMARY_CADENCE_STEP_MILLIS
    return (MIN_SUMMARY_CADENCE_MILLIS + snappedOffset).coerceIn(MIN_SUMMARY_CADENCE_MILLIS, MAX_SUMMARY_CADENCE_MILLIS)
}

fun Int.toSummaryIntervalSeconds(): Int =
    (snapSummaryCadenceMillis() + 999) / 1_000

fun minimumSummaryCadenceMillis(remoteModelId: String?): Int =
    if (remoteModelId == GROQ_GPT_OSS_20B_REMOTE_MODEL_ID) GROQ_MIN_SUMMARY_CADENCE_MILLIS else MIN_SUMMARY_CADENCE_MILLIS
