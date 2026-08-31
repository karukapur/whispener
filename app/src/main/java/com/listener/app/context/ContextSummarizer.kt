package com.listener.app.context

import kotlinx.serialization.json.*

data class ListeningContext(val globalContext: String, val details: List<String>)
sealed interface RemoteStatus { data object Ready : RemoteStatus; data object Offline : RemoteStatus; data object RateLimited : RemoteStatus; data object TimedOut : RemoteStatus; data object InvalidKey : RemoteStatus; data object ModelUnavailable : RemoteStatus; data object InvalidResponse : RemoteStatus }

object StructuredContextValidator {
    fun parse(body: String): ListeningContext? = runCatching {
        val root = Json.parseToJsonElement(body.extractJsonObject()).jsonObject
        val global = root["globalContext"]?.jsonPrimitive?.content?.trim().orEmpty()
        val details = root["details"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }.orEmpty().filter(String::isNotBlank)
        if (global.isBlank() || details.isEmpty()) null else ListeningContext(global, details.take(MAX_DETAILS))
    }.getOrNull()

    fun parseModelOutput(body: String): ListeningContext? = parse(body) ?: parseLooseText(body)

    fun parsePartialModelOutput(body: String): ListeningContext? {
        val global = body.extractJsonStringValue("globalContext").orEmpty().trim()
        val details = body.extractJsonStringArrayValues("details").map(String::trim).filter(String::isNotBlank).take(MAX_DETAILS)
        return if (global.isBlank() && details.isEmpty()) null else ListeningContext(global, details)
    }
}

private fun String.extractJsonObject(): String {
    val trimmed = trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
}

private fun parseLooseText(body: String): ListeningContext? {
    val lines = body
        .replace("```json", "")
        .replace("```", "")
        .lines()
        .map { it.trim().trim('*') }
        .filter { it.isNotBlank() }
    if (lines.size < 3) return null

    val heading = lines.firstNotNullOfOrNull { line ->
        line.afterLabel("globalContext")
            ?: line.afterLabel("global context")
            ?: line.afterLabel("heading")
            ?: line.afterLabel("context")
    } ?: lines.firstOrNull { line -> !line.isListLike() && !line.endsWith(":") }?.cleanLine()

    val details = lines.mapNotNull { line ->
        when {
            line.isListLike() -> line.cleanLine()
            line.startsWith("detail", ignoreCase = true) -> line.substringAfter(':', "").cleanLine()
            else -> null
        }
    }.filter { it.isNotBlank() && it != heading }.take(MAX_DETAILS)

    return if (!heading.isNullOrBlank() && details.isNotEmpty()) ListeningContext(heading, details.take(MAX_DETAILS)) else null
}

private fun String.afterLabel(label: String): String? {
    val prefix = "$label:"
    return if (startsWith(prefix, ignoreCase = true)) substring(prefix.length).cleanLine().takeIf { it.isNotBlank() } else null
}

private fun String.isListLike(): Boolean = matches(Regex("""^([-*•]|\d+[.)])\s+.+"""))

private fun String.cleanLine(): String = trim()
    .removePrefix("-")
    .removePrefix("*")
    .removePrefix("•")
    .replace(Regex("""^\d+[.)]\s*"""), "")
    .trim()

private fun String.extractJsonStringValue(key: String): String? {
    val keyIndex = indexOf("\"$key\"")
    if (keyIndex < 0) return null
    val colon = indexOf(':', keyIndex)
    if (colon < 0) return null
    val quote = indexOf('"', colon + 1)
    if (quote < 0) return null
    val end = findClosingJsonQuote(quote + 1) ?: return null
    return decodeJsonString(substring(quote + 1, end))
}

private fun String.extractJsonStringArrayValues(key: String): List<String> {
    val keyIndex = indexOf("\"$key\"")
    if (keyIndex < 0) return emptyList()
    val start = indexOf('[', keyIndex)
    if (start < 0) return emptyList()
    val values = mutableListOf<String>()
    var index = start + 1
    while (index < length && values.size < MAX_DETAILS) {
        when (this[index]) {
            ' ', '\n', '\r', '\t', ',' -> index++
            ']' -> return values
            '"' -> {
                val end = findClosingJsonQuote(index + 1) ?: return values
                values += decodeJsonString(substring(index + 1, end))
                index = end + 1
            }
            else -> index++
        }
    }
    return values
}

private fun String.findClosingJsonQuote(start: Int): Int? {
    var escaped = false
    for (index in start until length) {
        val char = this[index]
        if (escaped) {
            escaped = false
        } else if (char == '\\') {
            escaped = true
        } else if (char == '"') {
            return index
        }
    }
    return null
}

private fun decodeJsonString(raw: String): String = runCatching {
    Json.parseToJsonElement("\"$raw\"").jsonPrimitive.content
}.getOrElse { raw }

private const val MAX_DETAILS = 6

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
    private val tokens = Regex("(?i)(bearer\\s+|sk-or-v1-|gsk[_-]|org_)[A-Za-z0-9._-]+")
    fun redact(value: String) = value.replace(tokens) { if (it.value.startsWith("Bearer", true)) "Bearer [REDACTED]" else "[REDACTED]" }
}
