package app.nya.password.autofill

import java.net.URI

/**
 * Browsers served through the system's compatibility mode report the page's
 * fields without a web domain; the page address is read from the browser's
 * own address bar instead, found by its view id. Only these known ids of
 * these packages are trusted, and only with the browser's own package in the
 * resource name ([StructureParser] checks it).
 */
object UrlBars {
    private val IDS: Map<String, String> = mapOf(
        "com.microsoft.emmx" to "url_bar",
        "com.microsoft.emmx.beta" to "url_bar",
        "com.microsoft.emmx.canary" to "url_bar",
        "com.microsoft.emmx.dev" to "url_bar",
        "com.sec.android.app.sbrowser" to "location_bar_edit_text",
        "com.opera.browser" to "url_bar",
        "com.kiwibrowser.browser" to "url_bar",
        "com.vivaldi.browser" to "url_bar",
    )

    fun idOf(packageName: String): String? = IDS[packageName]

    /**
     * The (domain, scheme) shown in an address bar: `example.com`,
     * `example.com/login` or a full URL. Null for search text or anything
     * that is not a host name.
     */
    fun domainOf(text: String?): Pair<String, String?>? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val withScheme = if (t.contains("://")) t else "https://$t"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        // a host name or an address, not a lone word typed into the bar
        if (!host.contains('.') && !host.contains(':') && host != "localhost") return null
        return host to (if (t.contains("://")) scheme else null)
    }
}
