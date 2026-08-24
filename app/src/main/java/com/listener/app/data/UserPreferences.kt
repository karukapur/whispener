package com.listener.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.listenerDataStore by preferencesDataStore("listener_preferences")

const val DEFAULT_OPENROUTER_MODEL_ID = "nvidia/nemotron-nano-9b-v2:free"

data class ListenerPreferences(
    val onboardingComplete: Boolean = false,
    val remoteEnabled: Boolean = false,
    val retentionDays: Int = 30,
    val selectedModel: String? = DEFAULT_OPENROUTER_MODEL_ID,
    val selectedLocalModelId: String? = null,
    val transcriptionEngine: TranscriptionEngine = TranscriptionEngine.WHISPER_CPP,
    val whisperWorkProfile: WhisperWorkProfile = WhisperWorkProfile.RESPONSIVE,
    val summaryCadenceSeconds: Int = 10,
)

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
        val retention = intPreferencesKey("retention_days")
        val model = stringPreferencesKey("openrouter_model")
        val localModel = stringPreferencesKey("local_whisper_model")
        val transcriptionEngine = stringPreferencesKey("transcription_engine")
        val whisperWorkProfile = stringPreferencesKey("whisper_work_profile")
        val cadence = intPreferencesKey("summary_cadence_seconds")
    }

    val values: Flow<ListenerPreferences> = context.listenerDataStore.data.map { prefs ->
        ListenerPreferences(
            onboardingComplete = prefs[Keys.onboarding] ?: false,
            remoteEnabled = prefs[Keys.remote] ?: false,
            retentionDays = prefs[Keys.retention] ?: 30,
            selectedModel = prefs[Keys.model] ?: DEFAULT_OPENROUTER_MODEL_ID,
            selectedLocalModelId = prefs[Keys.localModel],
            transcriptionEngine = TranscriptionEngine.fromId(prefs[Keys.transcriptionEngine]),
            whisperWorkProfile = WhisperWorkProfile.fromId(prefs[Keys.whisperWorkProfile]),
            summaryCadenceSeconds = prefs[Keys.cadence] ?: 10,
        )
    }

    suspend fun completeOnboarding(remoteEnabled: Boolean, retentionDays: Int) = context.listenerDataStore.edit {
        it[Keys.onboarding] = true
        it[Keys.remote] = remoteEnabled
        it[Keys.retention] = retentionDays.coerceIn(0, 90)
        it[Keys.localModel] = "base"
    }

    suspend fun setRemoteEnabled(enabled: Boolean) = context.listenerDataStore.edit { it[Keys.remote] = enabled }
    suspend fun setRetentionDays(days: Int) = context.listenerDataStore.edit { it[Keys.retention] = days.coerceIn(0, 90) }
    suspend fun setSelectedModel(id: String) = context.listenerDataStore.edit { it[Keys.model] = id }
    suspend fun clearSelectedModel() = context.listenerDataStore.edit { it.remove(Keys.model) }
    suspend fun setSelectedLocalModel(id: String) = context.listenerDataStore.edit { it[Keys.localModel] = id }
    suspend fun clearSelectedLocalModel() = context.listenerDataStore.edit { it.remove(Keys.localModel) }
    suspend fun setTranscriptionEngine(engine: TranscriptionEngine) = context.listenerDataStore.edit { it[Keys.transcriptionEngine] = engine.id }
    suspend fun setWhisperWorkProfile(profile: WhisperWorkProfile) = context.listenerDataStore.edit { it[Keys.whisperWorkProfile] = profile.id }
    suspend fun setCadence(seconds: Int) = context.listenerDataStore.edit { it[Keys.cadence] = if (seconds == 5) 5 else 10 }
}
