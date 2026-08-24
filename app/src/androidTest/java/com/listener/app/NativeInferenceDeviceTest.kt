package com.listener.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.listener.app.speech.InferenceBackend
import com.listener.app.speech.JniWhisperEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeInferenceDeviceTest {
    @Test
    fun adrenoFallsBackBeforeInference() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "models/binaries/base.bin")
        assumeTrue("Base model is not installed on this device", model.isFile)

        val engine = JniWhisperEngine()
        try {
            assertEquals(InferenceBackend.CPU_FALLBACK, engine.load(model.path, preferGpu = true))
            assertNotNull(
                engine.transcribe(
                    samples16Khz = ShortArray(16_000 * 2),
                    windowStartMs = 0,
                    prompt = "以下是臺灣繁體中文對話逐字稿。",
                    language = "zh",
                )
            )
        } finally {
            engine.close()
        }
    }
}
