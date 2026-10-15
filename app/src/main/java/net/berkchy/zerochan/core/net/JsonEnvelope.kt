package net.berkchy.zerochan.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * zerochan serves its `?json` listings wrapped in a stray `{ ... }` pair followed by the
 * regular sidebar markup, and reports HTTP 500 for them even though the body is valid:
 *
 * `"{" + "\r\n" + <json> + "\r\n" + <sidebar html> + "\n}"`
 *
 * The interesting document therefore does not start at offset 0, and a throttled response can
 * be truncated mid-object. Rather than guessing an offset we try every top level `{` and keep
 * the first balanced slice that actually parses, which rejects truncated payloads for free.
 */
object JsonEnvelope {

    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @PublishedApi
    internal val CHALLENGE = "totally-innocent-logo-image.svg"

    @PublishedApi
    internal fun isChallenge(body: String): Boolean = body.contains(CHALLENGE)

    /** True when the payload really is the listing envelope and not a truncated fragment. */
    fun hasItems(body: String): Boolean {
        if (body.length < 200) return false
        return parse(body)?.get("items")?.let { it is JsonArray && it.size > 0 } == true
    }

    /** True when the payload parses as a complete json object. */
    fun isJsonObject(body: String): Boolean {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        return parse(trimmed) != null
    }

    fun challengeToken(body: String): String? =
        Regex("totally-innocent-logo-image\\.svg\\?([A-Za-z0-9_-]+)")
            .find(body)?.groupValues?.getOrNull(1)

    /** First balanced, parseable JSON object embedded anywhere in the payload. */
    fun slice(body: String): String? {
        var index = body.indexOf('{')
        var attempts = 0
        while (index >= 0 && attempts < MAX_CANDIDATES) {
            attempts++
            val end = balancedEnd(body, index)
            if (end > index) {
                val candidate = body.substring(index, end)
                if (looksLikeJson(candidate)) return candidate
            }
            index = body.indexOf('{', index + 1)
        }
        return null
    }

    fun parse(body: String): JsonObject? {
        val slice = slice(body) ?: return null
        return asObject(json.parseToJsonElement(slice))
    }

    inline fun <reified T> decode(body: String): T? {
        val element = parse(body) ?: return null
        return try {
            json.decodeFromJsonElement(element)
        } catch (e: Exception) {
            null
        }
    }

    private fun balancedEnd(body: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until body.length) {
            val c = body[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return -1
    }

    private fun looksLikeJson(candidate: String): Boolean {
        val trimmed = candidate.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        return runCatching { json.parseToJsonElement(trimmed) }.isSuccess
    }

    private fun asObject(element: JsonElement): JsonObject? = element as? JsonObject

    private const val MAX_CANDIDATES = 6
}