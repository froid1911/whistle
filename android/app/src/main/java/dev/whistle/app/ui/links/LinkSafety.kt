package dev.whistle.app.ui.links

import java.net.IDN
import java.net.URI
import java.net.URLDecoder

/** Where a link really goes. [host] is ASCII (punycode), so look-alike Unicode hosts show as xn--…. */
data class LinkTarget(val url: String, val scheme: String, val host: String, val wellFormed: Boolean = true) {
    /**
     * Only well-formed web links leave the app; anything else (intent:, file:, javascript:, custom
     * schemes, a web link a browser could read differently) is copy-only.
     */
    val openable: Boolean get() = wellFormed && (scheme == "http" || scheme == "https") && host.isNotEmpty()
}

/**
 * Agent output is untrusted: a Markdown link's text can claim one site and point at another. These
 * helpers find the real target and whether the visible text disagrees with it.
 */
object LinkSafety {
    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")
    private val AUTHORITY = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://(?:[^/?#@]*@)?(\[[^\]]*]|[^/?#:]*)""")
    private val HOST_OK = Regex("""^[a-z0-9_-]+(?:\.[a-z0-9_-]+)*\.?$""")
    private val IPV6_OK = Regex("""^\[[0-9a-f:.]+]$""")
    private val PORT_OK = Regex("""^\d{0,5}$""")

    /**
     * The real target of [url]. Web links are read the way a browser (WHATWG URL) reads them, not
     * the way java.net.URI does: tabs/newlines are removed, `\` counts as `/`, any number of
     * slashes after `https:` is fine, the last `@` before the path ends the userinfo. The result's
     * [LinkTarget.url] is rebuilt from those parts (scheme + host + port + path, no userinfo) and is
     * exactly what Open hands to the browser; anything that does not rebuild cleanly is copy-only.
     */
    fun parse(url: String): LinkTarget {
        val raw = url.trim()
        val scheme = SCHEME.find(raw)?.groupValues?.get(1)?.lowercase().orEmpty()
        if (scheme == "http" || scheme == "https") return parseWeb(raw, scheme)
        val rawHost = runCatching { URI(raw).host }.getOrNull()
            ?: AUTHORITY.find(raw)?.groupValues?.get(1).orEmpty()
        return LinkTarget(raw, scheme, asciiHost(rawHost))
    }

    private fun parseWeb(raw: String, scheme: String): LinkTarget {
        val copyOnly = { host: String -> LinkTarget(raw, scheme, host, wellFormed = false) }
        val u = raw.filterNot { it == '\t' || it == '\n' || it == '\r' }.replace('\\', '/')
        val rest = u.substring(scheme.length + 1).trimStart('/')
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end)
        val tail = rest.substring(end)
        val hostPort = authority.substringAfterLast('@')
        val (hostRaw, port) = if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return copyOnly(hostPort)
            hostPort.substring(0, close + 1) to hostPort.substring(close + 1).removePrefix(":")
        } else {
            hostPort.substringBefore(':') to hostPort.substringAfter(':', "")
        }
        val decoded = runCatching { URLDecoder.decode(hostRaw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return copyOnly(hostRaw)
        val host = if (decoded.startsWith("[")) decoded.lowercase() else asciiHost(decoded)
        val valid = host.isNotEmpty() && PORT_OK.matches(port) &&
            (if (host.startsWith("[")) IPV6_OK.matches(host) else HOST_OK.matches(host))
        if (!valid) return copyOnly(host)
        val rebuilt = "$scheme://$host" + (if (port.isNotEmpty()) ":$port" else "") + encodeTail(tail.ifEmpty { "/" })
        // Belt and braces: the rebuilt string must parse to the same host with a strict parser too.
        val check = runCatching { URI(rebuilt).host?.lowercase() }.getOrNull()
        if (check == null || check.trimEnd('.') != host.trimEnd('.')) return LinkTarget(rebuilt, scheme, host, wellFormed = false)
        return LinkTarget(rebuilt, scheme, host)
    }

    private const val TAIL_OK = "-._~:/?#[]@!$&'()*+,;=%"

    /** Percent-encodes what a browser would encode anyway (space, quotes, |, {, …; a second #). */
    private fun encodeTail(t: String): String = buildString {
        var seenHash = false
        for (ch in t) {
            val plain = ch.code >= 0x80 || ch.isLetterOrDigit() || (ch in TAIL_OK && !(ch == '#' && seenHash))
            if (ch == '#') seenHash = true
            if (plain && ch.code > 0x20) append(ch)
            else ch.toString().toByteArray(Charsets.UTF_8).forEach { append('%').append("%02X".format(it.toInt() and 0xff)) }
        }
    }

    private fun asciiHost(h: String): String =
        runCatching { IDN.toASCII(h.trimEnd('.'), IDN.ALLOW_UNASSIGNED) }.getOrDefault(h).lowercase()

    private val INLINE = Regex("""\[((?:[^\[\]]|\[[^\]]*])*)]\(\s*<?([^)\s>]+)>?(?:\s+["'(][^)]*)?\s*\)""")
    private val REF_USE = Regex("""\[((?:[^\[\]]|\[[^\]]*])*)]\[([^\]]*)]""")
    private val REF_DEF = Regex("""(?m)^[ \t]{0,3}\[([^\]]+)]:[ \t]*<?([^\s>]+)>?(?:[ \t]+.*)?$""")

    /** The visible texts of the links in [markdown], by destination URL (inline and reference links). */
    fun linkTexts(markdown: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        fun add(url: String, text: String) { out.getOrPut(url) { mutableListOf() } += text }
        INLINE.findAll(markdown).forEach { add(it.groupValues[2], it.groupValues[1]) }
        val defs = REF_DEF.findAll(markdown).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        REF_USE.findAll(markdown).forEach { m ->
            val label = m.groupValues[2].ifEmpty { m.groupValues[1] }.lowercase()
            defs[label]?.let { add(it, m.groupValues[1]) }
        }
        return out
    }

    private val TEXT_WITH_SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://\S+$""")
    private val TEXT_DOMAIN = Regex(
        """^(?:www\.)?((?:[\p{L}\p{N}-]+\.)+([\p{L}]{2,63}))(?::\d+)?(/\S*)?$""",
    )

    /** TLDs that make a bare "name.tld" read as a site, not a file name (README.md, install.sh). */
    private val SITE_TLDS = setOf(
        "com", "net", "org", "io", "dev", "app", "ai", "co", "me", "info", "biz", "xyz", "gov", "edu", "de", "uk",
        "fr", "eu", "ch", "at", "nl", "us", "ru", "cn", "jp", "gg", "ly", "tech", "cloud", "site", "online", "page",
    )

    /** The host the visible [text] claims, or null when it doesn't look like a URL or domain. */
    fun claimedHost(text: String): String? {
        val t = text.trim().trim('`', '<', '>', '*', '_', '"', '\'').trim()
        if (TEXT_WITH_SCHEME.matches(t)) return parse(t).host.ifEmpty { null }
        val m = TEXT_DOMAIN.matchEntire(t) ?: return null
        val claimsSite = t.startsWith("www.") || m.groupValues[3].isNotEmpty() || m.groupValues[2].lowercase() in SITE_TLDS
        return if (claimsSite) asciiHost(m.groupValues[1]) else null
    }

    /** Does the link [text] name a different site than [target]? (A subdomain of the named site is fine.) */
    fun textDiffers(text: String, target: LinkTarget): Boolean {
        val claimed = claimedHost(text)?.removePrefix("www.") ?: return false
        val real = target.host.removePrefix("www.")
        return !(real == claimed || real.endsWith(".$claimed"))
    }
}
