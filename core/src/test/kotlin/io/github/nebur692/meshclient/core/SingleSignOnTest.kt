package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading what a server offers and what it answers, without a server. / Leer lo que ofrece y contesta un servidor, sin servidor. */
class SingleSignOnTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `the login page says which single sign-on methods there are`() {
        // As MeshCentral writes it, and minified (BurbuMote 1.3.1 writes the second). / Como lo escribe y comprimido.
        assertEquals(listOf("oidc", "saml"), SignInMethods.parseLoginPage("var authStrategies = 'oidc,saml'.split(',');").map { it.strategy })
        assertEquals(listOf("oidc"), SignInMethods.parseLoginPage("""x;authStrategies="oidc".split(",");y""").map { it.strategy })
        // Unknown names and repeats are left out; none, nothing. / Los desconocidos y repetidos fuera; sin nada, nada.
        assertEquals(listOf("google"), SignInMethods.parseLoginPage("authStrategies = 'evil,google,google'.split(',')").map { it.strategy })
        assertTrue(SignInMethods.parseLoginPage("authStrategies = ''.split(',')").isEmpty())
        assertTrue(SignInMethods.parseLoginPage("<html>no single sign-on here</html>").isEmpty())
    }

    @Test fun `each method has its path and a name that assumes no company`() {
        val s = ServerAddress.parse("https://mesh.example.com/")!!
        assertEquals("https://mesh.example.com/auth-oidc", SingleSignOn("oidc").startUrl(s))
        assertEquals("OpenID Connect", SingleSignOn("oidc").displayName)
        assertEquals("Microsoft", SingleSignOn("azure").displayName)
    }

    @Test fun `a browser's cookie line is read into names and values`() {
        assertEquals(mapOf("xid" to "abc=", "xid.sig" to "def"), MeshSession.parseCookies("xid=abc=; xid.sig=def"))
        assertTrue(MeshSession.parseCookies(null).isEmpty())
        assertTrue(MeshSession.parseCookies("garbage; =x").isEmpty())
    }

    @Test fun `a refused session is told apart from a refused password`() {
        assertEquals(MeshFailure.SessionExpired, MeshSession.sessionFailure(obj("""{"action":"close","cause":"noauth"}""")).failure)
        assertEquals(MeshFailure.ToolsNotAllowed, MeshSession.sessionFailure(obj("""{"action":"close","cause":"notools"}""")).failure)
        assertEquals(MeshFailure.Rejected, MeshSession.sessionFailure(obj("""{"action":"close","cause":"other"}""")).failure)
    }

    @Test fun `a login token is read, with its password only when just created`() {
        val t = LoginToken.from(obj("""{"action":"createLoginToken","name":"Phone","tokenUser":"~t:abc","tokenPass":"p","created":1,"expire":0}"""))!!
        assertEquals("~t:abc", t.tokenUser); assertEquals("p", t.tokenPass); assertEquals(0L, t.expire)
        assertNull(LoginToken.from(obj("""{"action":"createLoginToken","result":"Denied"}""")))
        assertNull(LoginToken.from(obj("""{"name":"listed","tokenUser":"~t:x"}"""))!!.tokenPass)
    }
}
