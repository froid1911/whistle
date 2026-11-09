package dev.whistle.app.ui.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSafetyTest {
    private fun differs(text: String, url: String) = LinkSafety.textDiffers(text, LinkSafety.parse(url))

    @Test
    fun parsesTheRealHost() {
        val t = LinkSafety.parse("https://github.com@evil.example:8443/login?x=1")
        assertEquals("https", t.scheme)
        assertEquals("evil.example", t.host) // userinfo is not the host
        assertTrue(t.openable)
        assertTrue(LinkSafety.parse("https://gооgle.com/").host.startsWith("xn--")) // Cyrillic о: shown as punycode
        assertEquals("example.com", LinkSafety.parse("HTTP://Example.COM/a").host)
    }

    @Test
    fun onlyWebLinksOpen() {
        assertTrue(LinkSafety.parse("http://a.b/").openable)
        listOf("javascript:alert(1)", "intent://x#Intent;scheme=http;end", "file:///sdcard/x", "content://a/b", "tel:123", "mailto:a@b.c", "myapp://open")
            .forEach { assertFalse(it, LinkSafety.parse(it).openable) }
    }

    @Test
    fun linkTextsByDestination() {
        val md = """
            See [github.com](https://github.com.evil.io/x "t") and [docs][d] or [the docs][].
            <https://auto.link/>

            [d]: https://docs.example/page
            [the docs]: <https://docs.example/other>
        """.trimIndent()
        val m = LinkSafety.linkTexts(md)
        assertEquals(listOf("github.com"), m["https://github.com.evil.io/x"])
        assertEquals(listOf("docs"), m["https://docs.example/page"])
        assertEquals(listOf("the docs"), m["https://docs.example/other"])
    }

    @Test
    fun textClaimingAnotherSiteDiffers() {
        assertTrue(differs("github.com", "https://github.com.evil.io/login"))
        assertTrue(differs("https://github.com/anthropics", "https://evil.example/anthropics"))
        assertTrue(differs("www.paypal.com", "http://paypa1.com"))
        assertTrue(differs("github.com/x/y", "https://evil.example/github.com/x/y"))
        assertTrue(differs("google.com", "https://gооgle.com")) // look-alike host
        assertTrue(differs("example.com", "javascript:alert(1)"))
    }

    @Test
    fun matchingOrPlainTextIsFine() {
        assertFalse(differs("github.com", "https://github.com/x"))
        assertFalse(differs("https://www.github.com", "https://github.com/"))
        assertFalse(differs("github.com", "https://gist.github.com/x")) // subdomain of the named site
        assertFalse(differs("click here", "https://anything.example"))
        assertFalse(differs("README.md", "https://github.com/a/b/README.md")) // a file name, not a site
        assertFalse(differs("install.sh", "https://github.com/a/b/install.sh"))
        assertFalse(differs("`build.gradle`", "https://github.com/x"))
        assertNull(LinkSafety.claimedHost("v1.2.3"))
    }

    @Test
    fun whatwgSpoofsShowTheHostBrowsersOpen() {
        // A browser treats \\ as / in http(s) URLs: the host is evil.com, not github.com.
        LinkSafety.parse("https://evil.com\\@github.com").let {
            assertEquals("evil.com", it.host)
            assertFalse(it.url.contains('\\'))
            if (it.openable) assertEquals("evil.com", java.net.URI(it.url).host)
        }
        LinkSafety.parse("https:/\\evil.com/x").let {
            assertEquals("evil.com", it.host)
            if (it.openable) assertEquals("evil.com", java.net.URI(it.url).host)
        }
        // %5C stays in the userinfo: the host really is github.com.
        assertEquals("github.com", LinkSafety.parse("https://evil.com%5C@github.com/").host)
        // %40 is not a userinfo separator; a host with % is invalid: copy-only.
        assertFalse(LinkSafety.parse("https://github.com%40evil.com/").openable)
        // @ in the path is not userinfo.
        LinkSafety.parse("https://github.com/user@evil.com").let { assertEquals("github.com", it.host); assertTrue(it.openable) }
        // IPv6 literal.
        LinkSafety.parse("http://[::1]:8080/x").let { assertEquals("[::1]", it.host); assertTrue(it.openable) }
        // Trailing dot: the same site.
        assertEquals("github.com", LinkSafety.parse("https://github.com./x").host)
        // Tab / newline inside the host are stripped by browsers.
        LinkSafety.parse("https://git\thub.com/x").let {
            assertEquals("github.com", it.host)
            assertEquals("https://github.com/x", it.url)
        }
        LinkSafety.parse("https://evil\n.com/").let { assertEquals("evil.com", it.host) }
    }

    @Test
    fun theOpenedUrlIsTheReconstructedOne() {
        val t = LinkSafety.parse("HTTPS://GitHub.com\\x?q=1#f")
        assertEquals("https://github.com/x?q=1#f", t.url)
        assertTrue(t.openable)
        // Unparseable leftovers are copy-only, never opened.
        assertFalse(LinkSafety.parse("https://exa mple.com/").openable)
    }
}
