package app.nya.password

import app.nya.password.autofill.UrlBars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The page address read from a browser's address bar (compatibility mode). */
class UrlBarsTest {
    @Test
    fun domainOnly() {
        assertEquals("example.com" to null, UrlBars.domainOf("example.com"))
        assertEquals("login.example.com" to null, UrlBars.domainOf("  Login.Example.com/sign-in?next=/ "))
    }

    @Test
    fun fullUrlKeepsScheme() {
        assertEquals("example.com" to "https", UrlBars.domainOf("https://example.com/login"))
        assertEquals("203.0.113.5" to "http", UrlBars.domainOf("http://203.0.113.5:8080/"))
        assertEquals("example.com" to "https", UrlBars.domainOf("https://user@example.com./"))
    }

    @Test
    fun notAnAddress() {
        assertNull(UrlBars.domainOf(null))
        assertNull(UrlBars.domainOf(""))
        assertNull(UrlBars.domainOf("搜索或输入网址"))
        assertNull(UrlBars.domainOf("how to log in"))
        assertNull(UrlBars.domainOf("password"))
        assertNull(UrlBars.domainOf("ftp://example.com/"))
        assertNull(UrlBars.domainOf("javascript:alert(1)"))
    }

    @Test
    fun knownBrowsersOnly() {
        assertEquals("url_bar", UrlBars.idOf("com.microsoft.emmx"))
        assertNull(UrlBars.idOf("com.example.app"))
    }
}
