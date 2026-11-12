package io.github.nebur692.meshclient.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Terminal and Files tunnels against the bench's real Linux agent (tests/server/run-integration.sh). Everything the
 * files test writes goes in a folder of its own under /tmp, removed at the end.
 * Túneles de terminal y ficheros contra el agente Linux de verdad del banco. Todo lo que escribe la prueba de
 * ficheros va a una carpeta propia en /tmp, que se borra al acabar.
 */
class TunnelIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val server get() = ServerAddress.parse(url)!!

    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun admin() = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))

    private suspend fun MeshSession.agent(): Device {
        deviceGroups()
        repeat(60) {
            devices().firstOrNull { it.hasAgent }?.let { return it }
            delay(1000)
        }
        fail("no online agent"); error("")
    }

    private suspend fun failureOf(block: suspend () -> Unit): MeshClientException =
        try { block(); fail("it did not fail"); error("") } catch (e: MeshClientException) { e }

    @Test fun `a shell answers what is typed, takes a new size and ends when closed`() = runBlocking {
        need()
        val s = admin()
        try {
            val d = s.agent()
            val t = TerminalSession.open(s, d.id, TunnelProtocol.Terminal, 100, 30)
            val marker = "marca-${System.nanoTime()}"
            val seen = StringBuilder()
            // The echo of the command line also has the marker: the sum is only in the answer. / El eco también lleva la marca: la suma solo en la respuesta.
            t.write("echo $marker-\$((6*7))\n")
            withTimeout(30_000) {
                t.output.takeWhile { seen.append(it.toString(Charsets.UTF_8)); !seen.contains("$marker-42") }.collect {}
            }
            assertTrue(seen.toString(), seen.contains("$marker-42"))
            t.resize(80, 24)
            t.write("stty size\n")
            seen.setLength(0)
            withTimeout(30_000) { t.output.takeWhile { seen.append(it.toString(Charsets.UTF_8)); !seen.contains("24 80") }.collect {} }
            t.close()
            assertFalse(t.tunnel.isOpen)
        } finally {
            s.close()
        }
    }

    @Test fun `files are listed, uploaded, downloaded the same, renamed and deleted`() = runBlocking {
        need()
        val s = admin()
        val f = FilesSession.open(s, s.agent().id)
        val folder = "/tmp/mcc-files-${System.nanoTime()}"
        try {
            assertTrue("/ has no tmp", f.list("/").entries.any { it.name == "tmp" && it.kind == RemoteEntryKind.Folder })
            f.createFolder(folder)
            repeat(20) { if (f.list("/tmp").entries.none { "/tmp/" + it.name == folder }) delay(250) }

            // Three cases the upload tells apart: a block starting with '{', one starting with 0, and plain data; and
            // more blocks than the window, so acks are waited for. / Tres casos: bloque que empieza por «{», por 0 y
            // normal; y más bloques que la ventana, para que se esperen las confirmaciones.
            val cases = mapOf(
                "llave.json" to "{\"no es\":\"una orden\"}".toByteArray(),
                "cero.bin" to byteArrayOf(0, 1, 2, 3, 0, 123),
                "grande.bin" to Random(7).nextBytes(65564 * 40 + 123),
                "vacio.txt" to ByteArray(0),
            )
            for ((name, bytes) in cases) {
                var last = -1L
                f.upload(folder, name, ByteArrayInputStream(bytes), bytes.size.toLong()) { last = it }
                if (bytes.isNotEmpty()) assertEquals("upload progress of $name", bytes.size.toLong(), last)
            }
            val listed = f.list(folder).entries.associateBy { it.name }
            for ((name, bytes) in cases) {
                assertEquals("size of $name", bytes.size.toLong(), listed[name]?.size)
                assertEquals(RemoteEntryKind.File, listed[name]?.kind)
                val out = ByteArrayOutputStream()
                f.download("$folder/$name", out)
                assertArrayEquals("content of $name", bytes, out.toByteArray())
            }

            f.rename(folder, "cero.bin", "renombrado.bin")
            var names = f.list(folder).entries.map { it.name }
            repeat(20) { if ("renombrado.bin" !in names) { delay(250); names = f.list(folder).entries.map { it.name } } }
            assertTrue(names.toString(), "renombrado.bin" in names && "cero.bin" !in names)

            f.delete(folder, listOf("renombrado.bin"), recursive = false)
            repeat(20) { if ("renombrado.bin" in names) { delay(250); names = f.list(folder).entries.map { it.name } } }
            assertFalse(names.toString(), "renombrado.bin" in names)

            // MENTIRA: what does not exist, or cannot be written, says so with its key. / Lo que no existe o no se puede escribir lo dice con su clave.
            val missing = failureOf { f.download("$folder/no-existe.txt", ByteArrayOutputStream()) }
            assertEquals("ficheros/descargar/no-encontrado", missing.key)
            val noFolder = failureOf { f.upload("$folder/no-existe/dentro", "x.txt", ByteArrayInputStream(byteArrayOf(1)), 1) }
            assertEquals("ficheros/subir/rechazo-remoto", noFolder.key)
            val noList = failureOf { f.list("$folder/no-existe") }
            assertEquals("ficheros/ver-pantalla/no-encontrado", noList.key)

            // After a failure the session still works. / Tras un fallo la sesión sigue sirviendo.
            assertTrue(f.list(folder).entries.any { it.name == "grande.bin" })
        } finally {
            runCatching { f.delete("/tmp", listOf(folder.removePrefix("/tmp/")), recursive = true) }
            f.close()
            s.close()
        }
    }

    @Test fun `a tunnel to a device that does not exist says so instead of hanging`() = runBlocking {
        need()
        val s = admin()
        try {
            s.deviceGroups()
            val e = failureOf { TerminalSession.open(s, "node//no-existe", TunnelProtocol.Terminal, 80, 24) }
            assertTrue(e.key, e.key.startsWith("tunel/abrir-detalle/"))
        } finally {
            s.close()
        }
    }
}
