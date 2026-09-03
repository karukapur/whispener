package com.listener.app.audio

enum class RecordingState { IDLE, STARTING, RECORDING, PAUSED, INTERRUPTED, STOPPING, ERROR }
sealed interface RecordingEvent { data object Start : RecordingEvent; data object Started : RecordingEvent; data object Pause : RecordingEvent; data object Resume : RecordingEvent; data object Interrupt : RecordingEvent; data object Stop : RecordingEvent; data object Stopped : RecordingEvent; data object Failure : RecordingEvent }

fun reduce(state: RecordingState, event: RecordingEvent): RecordingState = when (state to event) {
    RecordingState.IDLE to RecordingEvent.Start -> RecordingState.STARTING
    RecordingState.STARTING to RecordingEvent.Started -> RecordingState.RECORDING
    RecordingState.RECORDING to RecordingEvent.Pause -> RecordingState.PAUSED
    RecordingState.PAUSED to RecordingEvent.Resume -> RecordingState.RECORDING
    RecordingState.RECORDING to RecordingEvent.Interrupt -> RecordingState.INTERRUPTED
    RecordingState.INTERRUPTED to RecordingEvent.Resume -> RecordingState.RECORDING
    RecordingState.RECORDING to RecordingEvent.Stop,
    RecordingState.PAUSED to RecordingEvent.Stop,
    RecordingState.INTERRUPTED to RecordingEvent.Stop,
    RecordingState.STARTING to RecordingEvent.Stop -> RecordingState.STOPPING
    RecordingState.STOPPING to RecordingEvent.Stopped -> RecordingState.IDLE
    else -> if (event == RecordingEvent.Failure) RecordingState.ERROR else state
}
