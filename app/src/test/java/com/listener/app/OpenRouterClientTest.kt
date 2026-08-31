package com.listener.app

import com.listener.app.context.*
import com.listener.app.data.OPENROUTER_FREE_ROUTER_MODEL_ID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenRouterClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenRouterClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OpenRouterClient(baseUrl = server.url("api/v1/").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun catalogKeepsOnlyFreeTextStructuredModels() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[
            {"id":"free/model","name":"Free","pricing":{"prompt":"0","completion":"0.000000"},"architecture":{"output_modalities":["text"]},"supported_parameters":["structured_outputs"]},
            {"id":"paid/model","name":"Paid","pricing":{"prompt":"0.1","completion":"0"},"architecture":{"output_modalities":["text"]},"supported_parameters":["structured_outputs"]},
            {"id":"free/image","name":"Image","pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["image"]},"supported_parameters":["structured_outputs"]}
        ]}"""))
        val result = client.fetchFreeModels("secret") as RemoteResult.Success<List<OpenRouterModel>>
        assertEquals(listOf("free/model"), result.value.map { it.id })
        val request = server.takeRequest()
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertTrue(request.path.orEmpty().contains("sort=latency-low-to-high"))
        assertTrue(request.path.orEmpty().contains("supported_parameters=structured_outputs"))
    }

    @Test fun summaryParsesRequiredEnglishContextShape() = runTest {
        val drafts = mutableListOf<ListeningContext>()
        server.enqueue(sse(
            "{\"globalContext\":\"A work meeting\"",
            ",\"details\":[\"A deadline is discussed\"",
            ",\"Someone asks a question\"]}",
        ))
        val result = client.summarize("secret", "free/model", "", "中文", "中文") { drafts += it } as RemoteResult.Success<ListeningContext>
        assertEquals("A work meeting", result.value.globalContext)
        assertEquals(listOf("A deadline is discussed", "Someone asks a question"), result.value.details)
        assertTrue(drafts.isNotEmpty())
        assertEquals("A work meeting", drafts.first().globalContext)
        assertTrue(drafts.any { it.details == listOf("A deadline is discussed") })
        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains(""""stream":true"""))
        assertTrue(requestBody.contains("json_schema"))
        assertTrue(requestBody.contains("require_parameters"))
        assertTrue(requestBody.contains("max_price"))
    }

    @Test fun summaryAcceptsLooseHeadingAndBulletsFromModel() = runTest {
        server.enqueue(sse("Global context: Lunch planning\nDetails:\n- They are choosing food\n- Noodles are mentioned"))
        val result = client.summarize("secret", "openrouter/free", "", "中文", "中文") as RemoteResult.Success<ListeningContext>
        assertEquals("Lunch planning", result.value.globalContext)
        assertEquals(listOf("They are choosing food", "Noodles are mentioned"), result.value.details)
    }

    @Test fun summarySupportsOpenRouterFreeRouterModel() = runTest {
        server.enqueue(sse("""{"globalContext":"Live context","details":["A useful detail"]}"""))
        val result = client.summarize("secret", OPENROUTER_FREE_ROUTER_MODEL_ID, "", "中文", "中文") as RemoteResult.Success<ListeningContext>

        assertEquals("Live context", result.value.globalContext)
        assertTrue(server.takeRequest().body.readUtf8().contains(""""model":"openrouter/free""""))
    }

    @Test fun groqGptOssUsesStrictNonStreamingLowReasoningRequest() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":"{\"globalContext\":\"Fast context\",\"details\":[\"One detail\"]}"},"finish_reason":"stop"}]}""",
        ))
        val groq = GroqClient(baseUrl = server.url("openai/v1/").toString())

        val result = groq.summarize("gsk-secret", "", "中文", "新的中文") as RemoteResult.Success<ListeningContext>

        assertEquals("Fast context", result.value.globalContext)
        val request = server.takeRequest()
        assertEquals("Bearer gsk-secret", request.getHeader("Authorization"))
        assertEquals("/openai/v1/chat/completions", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"openai/gpt-oss-20b\""))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"strict\":true"))
        assertTrue(body.contains("\"max_completion_tokens\":360"))
        assertTrue(body.contains("\"reasoning_effort\":\"low\""))
        assertFalse(body.contains("\"provider\""))
    }

    @Test fun summaryPromptUsesDeltaTailAndPreviousJson() = runTest {
        server.enqueue(sse("""{"globalContext":"Planning lunch","details":["They are choosing a restaurant","One person prefers noodles"]}"""))
        val continuityTail = "尾".repeat(800)
        val delta = "新".repeat(2_000)
        client.summarize(
            apiKey = "secret",
            model = "free/model",
            priorEnglishContext = """{"globalContext":"Old project discussion","details":["A prior decision exists"]}""",
            continuityChineseTail = continuityTail,
            newChineseText = delta,
        )

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("one short English heading"))
        assertTrue(requestBody.contains("4 to 6 concise English bullet-style details"))
        assertTrue(requestBody.contains("new finalized Chinese delta as the main source of truth"))
        assertTrue(requestBody.contains("Small Chinese continuity tail"))
        assertTrue(requestBody.contains("New finalized Chinese delta"))
        assertTrue(requestBody.contains("topic, preference, decision, constraint, disagreement, uncertainty, named entity, plan, or direction"))
        assertTrue(requestBody.contains("return the previous JSON unchanged"))
        assertTrue(requestBody.contains("keep the same globalContext"))
        assertTrue(requestBody.contains("update details in place"))
        assertTrue(requestBody.contains("reset both globalContext and details"))
        assertTrue(requestBody.contains("Do not translate line by line"))
        assertTrue(requestBody.contains("Do not invent names, facts, decisions, or action items"))
        assertTrue(requestBody.contains("Old project discussion"))
        assertTrue(requestBody.contains(continuityTail))
        assertTrue(requestBody.contains(delta))
        assertFalse(requestBody.contains("Recent Chinese transcript"))
    }

    @Test fun rateLimitIsTypedAndNonThrowing() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("retry-after", "12")
                .setHeader("x-ratelimit-limit-requests", "1000")
                .setHeader("x-ratelimit-remaining-requests", "0")
                .setHeader("x-ratelimit-reset-requests", "2m59.56s")
                .setHeader("x-ratelimit-limit-tokens", "8000")
                .setHeader("x-ratelimit-remaining-tokens", "42")
                .setHeader("x-ratelimit-reset-tokens", "7.66s")
                .setBody("""{"error":{"message":"Rate limit reached"}}"""),
        )
        val result = client.summarize("secret", "free/model", "old", "中文", "中文") as RemoteResult.Failure
        assertEquals(RemoteStatus.RateLimited, result.status)
        assertEquals("OpenRouter rate limit reached; waiting before retrying.", result.message)
        assertEquals("12", result.diagnostics?.retryAfterSeconds)
        assertEquals("1000", result.diagnostics?.rateLimitLimitRequests)
        assertEquals("0", result.diagnostics?.rateLimitRemainingRequests)
        assertEquals("2m59.56s", result.diagnostics?.rateLimitResetRequests)
        assertEquals("8000", result.diagnostics?.rateLimitLimitTokens)
        assertEquals("42", result.diagnostics?.rateLimitRemainingTokens)
        assertEquals("7.66s", result.diagnostics?.rateLimitResetTokens)
        assertNotNull(result.diagnostics?.responseHash)
        assertTrue(result.diagnostics?.safeResponseExcerpt.orEmpty().contains("Rate limit reached"))
    }

    @Test fun malformedSummaryIsRejected() = runTest {
        server.enqueue(sse("not-json"))
        val result = client.summarize("secret", "free/model", "", "中文", "中文") as RemoteResult.Failure
        assertEquals(RemoteStatus.InvalidResponse, result.status)
        assertEquals("stream_final_model_output", result.diagnostics?.parseStage)
        assertEquals(8, result.diagnostics?.streamDeltaChars)
        assertTrue(result.diagnostics?.doneSeen == true)
    }

    @Test fun invalidFinalStreamCanEmitDraftButStillFails() = runTest {
        val drafts = mutableListOf<ListeningContext>()
        server.enqueue(sse("""{"globalContext":"Draft topic","details":["Draft detail""""))

        val result = client.summarize("secret", "free/model", "", "中文", "中文") { drafts += it } as RemoteResult.Failure

        assertEquals(listOf(ListeningContext("Draft topic", listOf("Draft detail"))), drafts)
        assertEquals(RemoteStatus.InvalidResponse, result.status)
        assertEquals("stream_final_model_output", result.diagnostics?.parseStage)
        assertEquals(false, result.diagnostics?.safeResponseExcerpt?.contains("secret"))
    }

    @Test fun streamedSummaryIgnoresDoneEmptyAndNonContentEvents() = runTest {
        server.enqueue(sseRaw(
            "data:\n\n",
            """data: {"choices":[{"delta":{}}]}""" + "\n\n",
            sseData("""{"globalContext":"Travel","details":["A station is mentioned"]}"""),
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""" + "\n\n",
            "data: [DONE]\n\n",
        ))
        val result = client.summarize("secret", "free/model", "", "中文", "中文") as RemoteResult.Success<ListeningContext>
        assertEquals("Travel", result.value.globalContext)
        assertEquals(listOf("A station is mentioned"), result.value.details)
    }

    @Test fun sseErrorEventIsTypedAndIncludesSafeDiagnostics() = runTest {
        server.enqueue(sseRaw(
            "event: error\n",
            """data: {"error":{"type":"invalid_request_error","message":"No endpoints found that support structured outputs."}}""" + "\n\n",
        ))

        val result = client.summarize("secret", "free/model", "", "中文", "中文") as RemoteResult.Failure

        assertEquals(RemoteStatus.ModelUnavailable, result.status)
        assertEquals("No endpoints found that support structured outputs.", result.message)
        assertEquals("stream_sse_error", result.diagnostics?.parseStage)
        assertEquals("invalid_request_error", result.diagnostics?.sseErrorType)
        assertEquals("No endpoints found that support structured outputs.", result.diagnostics?.sseErrorMessage)
        assertNotNull(result.diagnostics?.responseHash)
    }

    @Test fun nonStreamingSummaryParsesMessageContentAndRejectsMalformedJson() = runTest {
        server.enqueue(chatCompletion("""{"globalContext":"Workshop","details":["A setup issue is discussed"]}"""))
        server.enqueue(chatCompletion("""{"globalContext":"Broken","details":["missing close""""))

        val success = client.summarize(
            apiKey = "secret",
            model = "free/model",
            priorEnglishContext = "",
            continuityChineseTail = "中文",
            newChineseText = "中文",
            transportMode = SummaryTransportMode.NON_STREAMING,
        ) as RemoteResult.Success<ListeningContext>
        val failure = client.summarize(
            apiKey = "secret",
            model = "free/model",
            priorEnglishContext = "",
            continuityChineseTail = "中文",
            newChineseText = "中文",
            transportMode = SummaryTransportMode.NON_STREAMING,
        ) as RemoteResult.Failure

        assertEquals("Workshop", success.value.globalContext)
        assertTrue(server.takeRequest().body.readUtf8().contains(""""stream":false"""))
        assertEquals(RemoteStatus.InvalidResponse, failure.status)
        assertEquals("non_streaming_final_model_output", failure.diagnostics?.parseStage)
        assertEquals(0, failure.diagnostics?.streamDeltaChars)
        assertTrue(server.takeRequest().body.readUtf8().contains(""""stream":false"""))
    }

    @Test fun unavailableModelIsTypedAndKeepsOpenRouterMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"No endpoints found that support structured outputs."}}"""))
        val result = client.summarize("secret", "missing/model", "", "中文", "中文") as RemoteResult.Failure
        assertEquals(RemoteStatus.ModelUnavailable, result.status)
        assertEquals("No endpoints found that support structured outputs.", result.message)
    }

    @Test fun invalidKeyIsTyped() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.fetchFreeModels("bad") as RemoteResult.Failure
        assertEquals(RemoteStatus.InvalidKey, result.status)
    }

    @Test fun timeoutIsTyped() = runTest {
        val fastClient = OpenRouterClient(
            OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build(),
            server.url("api/v1/").toString(),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val result = fastClient.fetchFreeModels("secret") as RemoteResult.Failure
        assertEquals(RemoteStatus.TimedOut, result.status)
    }

    @Test fun networkFailureIsTypedOffline() = runTest {
        val unreachable = OpenRouterClient(baseUrl = "http://127.0.0.1:1/api/v1/")
        val result = unreachable.fetchFreeModels("secret") as RemoteResult.Failure
        assertEquals(RemoteStatus.Offline, result.status)
    }

    private fun sse(vararg chunks: String): MockResponse =
        sseRaw(*(chunks.map(::sseData) + "data: [DONE]\n\n").toTypedArray())

    private fun sseRaw(vararg events: String): MockResponse =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString(""))

    private fun sseData(content: String): String =
        """data: {"choices":[{"delta":{"content":${JsonPrimitive(content)}}}]}""" + "\n\n"

    private fun chatCompletion(content: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":${JsonPrimitive(content)}},"finish_reason":"stop"}]}""")
}
