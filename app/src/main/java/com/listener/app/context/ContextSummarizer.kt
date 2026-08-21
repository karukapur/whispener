package com.listener.app.context

import kotlinx.serialization.json.*

data class ListeningContext(val globalContext: String, val details: List<String>)
sealed interface RemoteStatus { data object Ready : RemoteStatus; data object Offline : RemoteStatus; data object RateLimited : RemoteStatus; data object TimedOut : RemoteStatus; data object InvalidKey : RemoteStatus; data object InvalidResponse : RemoteStatus }

object StructuredContextValidator {
    fun parse(body: String): ListeningContext? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val global = root["globalContext"]?.jsonPrimitive?.content?.trim().orEmpty()
        val details = root["details"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }.orEmpty()
        if (global.isBlank() || details.size !in 2..3 || details.any(String::isBlank)) null else ListeningContext(global, details)
    }.getOrNull()
}

data class SummaryState(val priorGlobal: String = "", val recent: List<String> = emptyList()) {
    fun append(delta: String, maxCharacters: Int = 6_000): SummaryState {
        val kept = (recent + delta).takeLastWhileBudget(maxCharacters)
        return copy(recent = kept)
    }
    fun compact(global: String) = SummaryState(global, recent.takeLast(3))
}
private fun List<String>.takeLastWhileBudget(limit: Int): List<String> {
    var used = 0
    return asReversed().takeWhile { used += it.length; used <= limit }.asReversed()
}

object SecretRedactor {
    private val tokens = Regex("(?i)(bearer\\s+|sk-or-v1-)[A-Za-z0-9._-]+")
    fun redact(value: String) = value.replace(tokens) { if (it.value.startsWith("Bearer", true)) "Bearer [REDACTED]" else "[REDACTED]" }
}
