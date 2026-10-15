package net.berkchy.zerochan.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * zerochan serves its `?json` listings wrapped in a stray `{ ... }` pair followed by the
 * regular sidebar markup, and reports HTTP 500 for them even though the body is valid.
 *
 * `"{\r\n" + <json> + "\n" + <html> + "\n}"` -> we cut the balanced JSON document out.
 */
object JsonEnvelope {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val CHALLENGE = "totally-innocent-logo-image.svg"

    fun isChallenge(body: String): Boolean = body.contains(CHALLENGE)

    fun challengeToken(body: String): String? =
        Regex("totally-innocent-logo-image\\.svg\\?([A-Za-z0-9_-]+)")
            .find(body)?.groupValues?.getOrNull(1)

    /** Extracts the first complete JSON object embedded in an arbitrary html/text payload. */
    fun slice(body: String): String? {
        val start = body.indexOfFirstBalancedStart() ?: return null
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
                    if (depth == 0) return body.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Listing endpoints answer with `"{\r\n{ ...items... }\r\n<html>...}"`, so the embedded
     * document starts on the first line that begins with `{`. Plain `/{id}?json` endpoints
     * start with `{` directly and are handled by [parse].
     */
    fun parseListing(body: String): JsonObject? = asObject(json.parseToJsonElement(slice(body) ?: return null))

    fun parse(body: String): JsonObject? {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return null
        return asObject(json.parseToJsonElement(trimmed))
    }

    fun <T> decode(body: String): T? {
        val element = parse(body) ?: return null
        return try {
            json.decodeFromJsonElement(element)
        } catch (e: Exception) {
            null
        }
    }

    fun <T> decodeListing(body: String): T? {
        val element = parseListing(body) ?: return null
        return try {
            json.decodeFromJsonElement(element)
        } catch (e: Exception) {
            null
        }
    }

    private fun asObject(element: JsonElement): JsonObject? = element as? JsonObject

    private fun String.indexOfFirstBalancedStart(): Int? {
        if (startsWith("{")) return 0
        val newline = indexOf("\n{")
        return if (newline >= 0) newline + 1 else null
    }
}