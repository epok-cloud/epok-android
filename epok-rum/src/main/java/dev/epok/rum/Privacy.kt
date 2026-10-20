package dev.epok.rum

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/** The web URL policy, applied before serialization and disk storage. */
object Privacy {
    const val REDACTED = "<redacted>"
    private val scheme = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
    private val sensitive = Regex(
        "^(access[-_]?token|id[-_]?token|refresh[-_]?token|token|secret|password|passwd|" +
            "reset([-_]password)?|auth|authorization|bearer|api[-_]?key|key|code|invite|verify|verification|session)$",
        RegexOption.IGNORE_CASE
    )

    fun scrubUrl(input: String, allowedQueryKeys: Set<String> = emptySet()): String = try {
        if (input.isEmpty()) "" else {
            val hasFragment = '#' in input
            val base = input.substringBefore('#')
            var path = base.substringBefore('?')
            var authority = ""
            // OkHttp is deliberately permissive about backslashes/control characters; the
            // privacy boundary rejects these rather than allowing parser disagreement.
            require(input.none { it <= '\u001f' || it == '\u007f' || it == '\\' })
            if (path.startsWith("//") || scheme.containsMatchIn(path)) {
                val relative = path.startsWith("//")
                require(relative || path.startsWith("https://", true) || path.startsWith("http://", true))
                require((if (relative) path.drop(2) else path.substringAfter("://")).substringBefore('/').isNotEmpty())
                val parsed = (if (relative) "https:$path" else path).toHttpUrlOrNull()
                    ?: error("Malformed URL")
                authority = (if (relative) "" else "${parsed.scheme}:") + "//" +
                    (if (':' in parsed.host) "[${parsed.host}]" else parsed.host) +
                    (if (parsed.port == okhttp3.HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}")
                path = parsed.encodedPath
            }
            var secretNext = false
            val safePath = path.split('/').joinToString("/") { segment ->
                var decoded = segment
                repeat(4) { if ('%' in decoded) decoded = decode(decoded) }
                val secret = secretNext
                secretNext = secret || sensitive.matches(decoded)
                val opaque = decoded.startsWith("eyJ", true) || decoded.split('-', '_', '.').any {
                    it.matches(Regex("[a-f0-9]{16,}", RegexOption.IGNORE_CASE)) ||
                        it.matches(Regex("[a-z0-9]{24,}", RegexOption.IGNORE_CASE)) ||
                        (it.matches(Regex("[a-z0-9]{16,}", RegexOption.IGNORE_CASE)) && it.any(Char::isDigit))
                }
                when {
                    decoded.isEmpty() -> ""
                    !secret && decoded in setOf(":id", ":uuid", ":hash") -> decoded
                    secret || opaque || !decoded.matches(Regex("[a-z][a-z0-9_.-]{0,31}", RegexOption.IGNORE_CASE)) -> REDACTED
                    else -> decoded
                }
            }
            val query = base.substringAfter('?', "")
            val kept = mutableListOf<String>()
            var dropped = false
            if (query.isNotEmpty()) query.split('&').forEach { pair ->
                val rawKey = pair.substringBefore('=')
                val key = try { decode(rawKey) } catch (_: Exception) { rawKey }
                if (key in allowedQueryKeys) kept += pair else dropped = true
            }
            if (dropped) kept += REDACTED
            authority + safePath + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) +
                (if (hasFragment) "#$REDACTED" else "")
        }
    } catch (_: Exception) { REDACTED }

    // decodeURIComponent semantics: '+' is a literal plus, not a form-encoded space.
    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}
