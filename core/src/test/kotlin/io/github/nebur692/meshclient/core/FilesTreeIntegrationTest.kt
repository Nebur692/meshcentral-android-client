package io.github.nebur692.meshclient.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Several files at once against the bench's real Linux agent: delete several, copy and move a folder with what is
 * inside, and never into itself. Everything goes in a folder of its own under /tmp, removed at the end.
 * Varios ficheros a la vez contra el agente Linux real del banco: borrar varios, copiar y mover una carpeta con lo que
 * tiene dentro, y nunca dentro de sí misma. Todo va a una carpeta propia en /tmp, que se borra al acabar.
 */
class FilesTreeIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val server get() = ServerAddress.parse(url)!!

    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun MeshSession.agent(): Device {
        deviceGroups()
        repeat(60) { devices().firstOrNull { it.hasAgent }?.let { return it }; delay(1000) }
        fail("no online agent"); error("")
    }

    private suspend fun FilesSession.put(folder: String, name: String, bytes: ByteArray) =
        upload(folder, name, ByteArrayInputStream(bytes), bytes.size.toLong())

    private suspend fun FilesSession.get(path: String): ByteArray = ByteArrayOutputStream().also { download(path, it) }.toByteArray()

    @Test fun `several are deleted, copied and moved with their folders, and never into themselves`() = runBlocking {
        need()
        val s = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))
        val f = FilesSession.open(s, s.agent())
        val root = "/tmp/mcc-tree-${System.nanoTime()}"
        try {
            f.createFolder(root)
            f.waitFor("/tmp", "create the work folder") { d -> d.entries.any { "/tmp/" + it.name == root } }
            val a = "$root/a"; val b = "$root/b"
            f.createFolder(a); f.createFolder(b)
            f.waitFor(root, "create a and b") { d -> d.entries.count { it.kind == RemoteEntryKind.Folder } == 2 }
            val one = "uno ñ".toByteArray(); val two = ByteArray(70_000) { (it % 251).toByte() }
            f.put(a, "1.txt", one); f.put(a, "2.bin", two); f.put(a, "3.txt", "tres".toByteArray())
            f.createFolder("$a/sub"); f.waitFor(a, "create sub") { d -> d.entries.any { it.name == "sub" } }
            f.put("$a/sub", "dentro.txt", "dentro".toByteArray())

            // Delete two at once, and only those. / Borrar dos a la vez, y solo esos.
            val inA = f.list(a).entries
            f.deleteAll(a, inA.filter { it.name == "3.txt" || it.name == "nada" })
            assertEquals(setOf("1.txt", "2.bin", "sub"), f.list(a).entries.map { it.name }.toSet())

            // Copy files and a folder with what is inside: everything is there, the same bytes. / Copiar con su contenido.
            f.copyTree(a, f.list(a).entries, b)
            assertEquals(setOf("1.txt", "2.bin", "sub"), f.list(b).entries.map { it.name }.toSet())
            assertArrayEquals(two, f.get("$b/2.bin"))
            assertArrayEquals("dentro".toByteArray(), f.get("$b/sub/dentro.txt"))
            assertEquals(3, f.list(a).entries.size) // the originals stay / los originales se quedan

            // Move: there, and gone from where they were. / Mover: allí, y fuera de donde estaban.
            val c = "$root/c"; f.createFolder(c); f.waitFor(root, "create c") { d -> d.entries.any { it.name == "c" } }
            f.moveTree(a, f.list(a).entries.filter { it.name != "2.bin" }, c)
            assertEquals(setOf("1.txt", "sub"), f.list(c).entries.map { it.name }.toSet())
            assertArrayEquals(one, f.get("$c/1.txt"))
            assertEquals(setOf("2.bin"), f.list(a).entries.map { it.name }.toSet())

            // A folder into itself or inside it is refused before anything is sent. / Dentro de sí misma: se rechaza antes.
            val inside = try { f.copyTree(root, f.list(root).entries.filter { it.name == "c" }, "$c/sub"); null } catch (e: MeshClientException) { e }
            assertEquals("ficheros/gestionar-ficheros/dato-invalido", inside?.key)
            assertTrue(isInside("$c/sub", c))
        } finally {
            runCatching { f.delete("/tmp", listOf(root.removePrefix("/tmp/")), recursive = true) }
            f.close(); s.close()
        }
    }
}
