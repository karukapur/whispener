package com.listener.app.context

import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenRouterClient(private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()) {
    /** Catalog is fetched instead of assuming a future model name; the user must confirm a free model. */
    fun fetchFreeModels(onResult: (Result<List<String>>) -> Unit) {
        http.newCall(Request.Builder().url("https://openrouter.ai/api/v1/models").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onResult(Result.failure(e))
            override fun onResponse(call: Call, response: Response) = response.use {
                if (!it.isSuccessful) return@use onResult(Result.failure(IOException("catalog HTTP ${it.code}")))
                val data = Json.parseToJsonElement(it.body!!.string()).jsonObject["data"]!!.jsonArray
                val ids = data.mapNotNull { model -> model.jsonObject.takeIf { obj -> obj["pricing"]?.jsonObject?.get("prompt")?.jsonPrimitive?.content == "0" }?.get("id")?.jsonPrimitive?.content }
                onResult(Result.success(ids))
            }
        })
    }
}

class SummaryCoordinator(initial: ListeningContext? = null) {
    var lastValid: ListeningContext? = initial; private set
    var state = SummaryState(); private set
    fun finalizedDelta(text: String) { state = state.append(text) }
    fun acceptResponse(body: String): Boolean { val valid = StructuredContextValidator.parse(body) ?: return false; lastValid = valid; state = state.compact(valid.globalContext); return true }
}
