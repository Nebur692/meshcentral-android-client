package io.github.nebur692.meshclient.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The website's reports against the bench, the same steps the Windows app's ServerIntegrationTests measured.
 * Los informes de la web contra el banco, los mismos pasos que midió la prueba de Windows.
 */
class ReportsIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val server get() = ServerAddress.parse(url)!!

    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun connect(u: String, p: String) = MeshSession.connect(server, PasswordCredentials(u, p), CertificateTrust(cert))

    private suspend fun failureOf(block: suspend () -> Unit): MeshClientException =
        try { block(); fail("it did not fail"); error("") } catch (e: MeshClientException) { e }

    private fun str(v: kotlinx.serialization.json.JsonElement?) = (v as? JsonPrimitive)?.content

    @Test fun `a terminal session, the traffic, a website sign-in and the database come out as the website's reports`() = runBlocking {
        need()
        val from = System.currentTimeMillis() - 60_000
        val admin = connect("admin", "Admin-Pass-1234")
        try {
            admin.deviceGroups()
            var dev: Device? = null
            for (i in 0 until 60) { dev = admin.devices().firstOrNull { it.hasAgent }; if (dev != null) break; delay(1000) }
            val d = dev ?: run { fail("no online agent"); error("") }
            val t = TerminalSession.open(admin, d.id, TunnelProtocol.Terminal, 80, 25)
            val marker = "rp${System.nanoTime()}"
            val seen = StringBuilder()
            t.write("echo $marker-o\"k\"\n")
            withTimeout(20_000) { t.output.takeWhile { seen.append(it.toString(Charsets.UTF_8)); !seen.contains("$marker-ok") }.collect {} }
            t.close()

            var sessions: Report? = null
            for (i in 0 until 40) {
                sessions = admin.report(ReportKind.RemoteSessions, ReportGroupBy.Device, from, System.currentTimeMillis() + 60_000)
                if (sessions.groups.any { it.key.contains(d.id) && it.rows.isNotEmpty() }) break
                delay(500)
            }
            assertEquals("nodemesh", sessions!!.groupFormat)
            val row = sessions.groups.first { it.key.contains(d.id) }.rows[0]
            assertEquals(admin.user.id, str(row["userid"]))
            assertTrue(sessions.columns.toString(), sessions.columns.any { it.id == "time" && it.format == "datetime" })

            // The traffic report: one table with a row per account. / El de tráfico: una tabla con una fila por cuenta.
            val traffic = admin.report(ReportKind.Traffic, ReportGroupBy.User, from, System.currentTimeMillis() + 60_000)
            assertEquals(listOf("userid", "length", "bytesin", "bytesout"), traffic.columns.map { it.id })
            assertEquals("0", traffic.groups.single().key)

            // The server logs only sign-ins on its website, not the app's (measured on Windows), so the test signs in
            // on the website's form first. / El servidor solo apunta las entradas de su web, así que se entra por su formulario.
            val http = CertificateTrust(cert).applyTo(OkHttpClient.Builder()).build()
            http.newCall(Request.Builder().url(server.baseUrl).post(FormBody.Builder().add("action", "login").add("username", "admin")
                .add("password", "Admin-Pass-1234").build()).build()).execute().close()
            val logins = admin.report(ReportKind.Logins, ReportGroupBy.User, from, System.currentTimeMillis() + 60_000)
            val mine = logins.groups.single { it.key == admin.user.id }
            assertTrue(mine.rows.toString(), mine.rows.any { "ip" in it && "time" in it })

            val db = admin.report(ReportKind.Database, ReportGroupBy.User, from, System.currentTimeMillis())
            assertTrue(db.groups.single().rows.any { str(it["record"]) == "user" })

            // What the server would drop without answering is stopped here. / Lo que el servidor tiraría se para aquí.
            assertEquals("servidor/informe/dato-invalido", failureOf { admin.report(ReportKind.Logins, ReportGroupBy.Device, from, System.currentTimeMillis()) }.key)
            assertEquals("servidor/informe/dato-invalido", failureOf { admin.report(ReportKind.RemoteSessions, ReportGroupBy.User, System.currentTimeMillis(), from) }.key)
            assertTrue(admin.isOpen)
        } finally { admin.close() }

        val user = connect("user", "User-Pass-1234")
        try {
            assertEquals(listOf(ReportKind.RemoteSessions, ReportKind.Traffic, ReportKind.Logins), user.reportKinds)
            assertEquals("servidor/informe/no-autorizado", failureOf { user.report(ReportKind.Database, ReportGroupBy.User, from, System.currentTimeMillis()) }.key)
        } finally { user.close() }
    }
}
