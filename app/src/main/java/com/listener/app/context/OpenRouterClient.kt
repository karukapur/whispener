package com.listener.app.context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class OpenRouterModel(val id: String, val name: String)

sealed interface RemoteResult<out T> {
    data class Success<T>(val value: T) : RemoteResult<T>
    data class Failure(val status: RemoteStatus, val message: String) : RemoteResult<Nothing>
}

class OpenRouterClient(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://openrouter.ai/api/v1/",
) {
    suspend fun fetchFreeModels(apiKey: String): RemoteResult<List<OpenRouterModel>> = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("${baseUrl}models?sort=latency-low-to-high&supported_parameters=structured_outputs").bearer(apiKey).build()) { body ->
            val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return@execute emptyList()
            data.mapNotNull { element ->
                val model = element.jsonObject
                val pricing = model["pricing"]?.jsonObject ?: return@mapNotNull null
                val promptFree = pricing["prompt"]?.jsonPrimitive?.contentOrNull.isZeroPrice()
                val completionFree = pricing["completion"]?.jsonPrimitive?.contentOrNull.isZeroPrice()
                val outputs = model["architecture"]?.jsonObject?.get("output_modalities")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val parameters = model["supported_parameters"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val structured = parameters.any { it == "structured_outputs" }
                val id = model["id"]?.jsonPrimitive?.contentOrNull
                if (promptFree && completionFree && "text" in outputs && structured && id != null) {
                    OpenRouterModel(id, model["name"]?.jsonPrimitive?.contentOrNull ?: id)
                } else null
            }
        }
    }

    suspend fun summarize(
        apiKey: String,
        model: String,
        priorEnglishContext: String,
        recentChineseTranscript: String,
        newChineseText: String,
        onDraft: suspend (ListeningContext) -> Unit = {},
    ): RemoteResult<ListeningContext> = withContext(Dispatchers.IO) {
        val prompt = summaryPrompt(priorEnglishContext, recentChineseTranscript, newChineseText)
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", "Return concise English conversation context only as a JSON object. No markdown. No extra text.") }
                addJsonObject { put("role", "user"); put("content", prompt) }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "listening_context")
                    put("strict", true)
                    putJsonObject("schema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("globalContext") {
                                put("type", "string")
                                put("description", "One short English heading for the current main topic.")
                            }
                            putJsonObject("details") {
                                put("type", "array")
                                put("description", "Concise English bullet-style details that help the listener follow what is happening now.")
                                putJsonObject("items") { put("type", "string") }
                                put("minItems", 1)
                                put("maxItems", 6)
                            }
                        }
                        putJsonArray("required") {
                            add("globalContext")
                            add("details")
                        }
                        put("additionalProperties", false)
                    }
                }
            }
            put("temperature", 0)
            put("max_tokens", 360)
            putJsonObject("provider") {
                put("require_parameters", true)
                putJsonObject("max_price") { put("prompt", 0); put("completion", 0) }
            }
        }.toString()
        val request = Request.Builder().url("${baseUrl}chat/completions").bearer(apiKey)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
        executeStreaming(request, onDraft)
    }

    private fun Request.Builder.bearer(apiKey: String) = header("Authorization", "Bearer ${apiKey.trim()}")

    private fun String?.isZeroPrice(): Boolean = this?.toBigDecimalOrNull()?.compareTo(java.math.BigDecimal.ZERO) == 0

    private fun <T> execute(request: Request, parse: (String) -> T): RemoteResult<T> = try {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when (response.code) {
                401 -> RemoteResult.Failure(RemoteStatus.InvalidKey, "OpenRouter rejected this API key.")
                408 -> RemoteResult.Failure(RemoteStatus.TimedOut, "OpenRouter timed out.")
                404 -> RemoteResult.Failure(RemoteStatus.ModelUnavailable, openRouterError(body) ?: "The selected OpenRouter model is unavailable for this request.")
                429 -> RemoteResult.Failure(RemoteStatus.RateLimited, "The free OpenRouter quota is currently unavailable.")
                in 200..299 -> try { RemoteResult.Success(parse(body)) } catch (_: Throwable) {
                    RemoteResult.Failure(RemoteStatus.InvalidResponse, "OpenRouter returned a response that could not be used; keeping the last context.")
                }
                else -> RemoteResult.Failure(RemoteStatus.InvalidResponse, "OpenRouter returned HTTP ${response.code}.")
            }
        }
    } catch (_: SocketTimeoutException) {
        RemoteResult.Failure(RemoteStatus.TimedOut, "OpenRouter timed out.")
    } catch (_: InterruptedIOException) {
        RemoteResult.Failure(RemoteStatus.TimedOut, "OpenRouter timed out.")
    } catch (_: IOException) {
        RemoteResult.Failure(RemoteStatus.Offline, "OpenRouter is unavailable; local transcription is still active.")
    }

    private suspend fun executeStreaming(request: Request, onDraft: suspend (ListeningContext) -> Unit): RemoteResult<ListeningContext> = try {
        http.newCall(request).execute().use { response ->
            if (response.code !in 200..299) return httpFailure(response.code, response.body?.string().orEmpty())
            val source = response.body?.source() ?: return RemoteResult.Failure(RemoteStatus.InvalidResponse, "OpenRouter returned a response that could not be used; keeping the last context.")
            val builder = StringBuilder()
            var lastDraft: ListeningContext? = null
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val data = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") && it.isNotBlank() } ?: continue
                if (data == "[DONE]") break
                val delta = streamDelta(data) ?: continue
                if (delta.isBlank()) continue
                builder.append(delta)
                val draft = StructuredContextValidator.parsePartialModelOutput(builder.toString()) ?: continue
                if (draft != lastDraft) {
                    lastDraft = draft
                    onDraft(draft)
                }
            }
            val finalContext = StructuredContextValidator.parseModelOutput(builder.toString())
                ?: return RemoteResult.Failure(RemoteStatus.InvalidResponse, "OpenRouter returned a response that could not be used; keeping the last context.")
            RemoteResult.Success(finalContext)
        }
    } catch (_: SocketTimeoutException) {
        RemoteResult.Failure(RemoteStatus.TimedOut, "OpenRouter timed out.")
    } catch (_: InterruptedIOException) {
        RemoteResult.Failure(RemoteStatus.TimedOut, "OpenRouter timed out.")
    } catch (_: IOException) {
        RemoteResult.Failure(RemoteStatus.Offline, "OpenRouter is unavailable; local transcription is still active.")
    }

    private fun httpFailure(code: Int, body: String): RemoteResult.Failure = when (code) {
        401 -> RemoteResult.Failure(RemoteStatus.InvalidKey, "OpenRouter rejected this API key.")
        408 -> RemoteResult.Failure(RemoteStatus.TimedOut, "OpenRouter timed out.")
        404 -> RemoteResult.Failure(RemoteStatus.ModelUnavailable, openRouterError(body) ?: "The selected OpenRouter model is unavailable for this request.")
        429 -> RemoteResult.Failure(RemoteStatus.RateLimited, "The free OpenRouter quota is currently unavailable.")
        else -> RemoteResult.Failure(RemoteStatus.InvalidResponse, "OpenRouter returned HTTP $code.")
    }

    private fun streamDelta(data: String): String? = runCatching {
        Json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun summaryPrompt(priorEnglishContext: String, recentChineseTranscript: String, newChineseText: String): String = buildString {
        appendLine("You are a live context updater for an English-speaking listener who cannot understand the Chinese conversation.")
        appendLine()
        appendLine("Return only this JSON object, with no markdown and no extra text:")
        appendLine("""{"globalContext":"Stable English topic heading","details":["Current useful detail 1","Current useful detail 2","Current useful detail 3","Current useful detail 4"]}""")
        appendLine()
        appendLine("Your job is to maintain a stable live understanding panel, not produce meeting notes.")
        appendLine()
        appendLine("Output rules:")
        appendLine("1. globalContext: one short English heading for the current main topic.")
        appendLine("2. details: 4 to 6 concise English bullet-style details that help the listener follow what is happening now.")
        appendLine()
        appendLine("Update behavior:")
        appendLine("- If the new text continues the same topic, keep the same globalContext or make only a small refinement.")
        appendLine("- If the same topic continues, update details in place: keep still-relevant details, replace stale details, and add important new context.")
        appendLine("- If the conversation clearly changes topic, reset both globalContext and details for the new topic.")
        appendLine("- Prefer information useful for live comprehension: topic, positions, choices, constraints, uncertainties, decisions, and next conversational direction.")
        appendLine("- Avoid low-value filler such as 'they are talking' or 'conversation continues'.")
        appendLine("- Do not translate line by line.")
        appendLine("- Preserve uncertainty when the transcript is unclear.")
        appendLine("- Do not invent names, facts, decisions, or action items that are not supported by the transcript.")
        appendLine("- Keep all output in English.")
        appendLine("- If there is not enough material for 4 details, provide 2 or 3 strong details instead.")
        appendLine()
        appendLine("Previous English context:")
        appendLine(priorEnglishContext.ifBlank { "None yet" })
        appendLine()
        appendLine("Recent Chinese transcript:")
        appendLine(recentChineseTranscript)
        appendLine()
        appendLine("New finalized Chinese text:")
        appendLine(newChineseText)
    }

    private fun openRouterError(body: String): String? = runCatching {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: Json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private class InvalidRemoteResponse : IllegalArgumentException()

    companion object { private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType() }
}

class SummaryCoordinator(initial: ListeningContext? = null) {
    var lastValid: ListeningContext? = initial; private set
    var state = SummaryState(); private set
    fun finalizedDelta(text: String) { state = state.append(text) }
    fun acceptResponse(body: String): Boolean {
        val valid = StructuredContextValidator.parse(body) ?: return false
        lastValid = valid; state = state.compact(valid.globalContext); return true
    }
}
