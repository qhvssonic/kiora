package dev.kiora.model

import java.net.URI

/** Accepts an origin or /v1 base URL while preserving an explicitly supplied endpoint path. */
fun resolveChatCompletionsEndpoint(configuredUrl: String): String {
    val input = configuredUrl.trim()
    val uri = URI(input)
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "Model endpoint must use HTTP(S)"
    }
    require(!uri.host.isNullOrBlank()) { "Model endpoint must include a host" }
    val suffix = when (uri.rawPath.orEmpty()) {
        "", "/" -> "/v1/chat/completions"
        "/v1", "/v1/" -> "/v1/chat/completions"
        else -> return input
    }
    return buildString {
        append(uri.scheme).append("://").append(uri.rawAuthority).append(suffix)
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}
