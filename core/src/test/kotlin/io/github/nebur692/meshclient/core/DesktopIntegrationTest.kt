package io.github.nebur692.meshclient.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The remote desktop against the bench's real Linux agent when it has a screen (MESHTEST_DESKTOP=1: an X server in its
 * container). / El escritorio remoto contra el agente Linux de verdad del banco cuando tiene pantalla (un servidor X).
 */
class DesktopIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val withScreen = System.getenv("MESHTEST_DESKTOP") == "1"

    private suspend fun admin() = MeshSession.connect(ServerAddress.parse(url)!!, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))

    private suspend fun MeshSession.agent(): Device {
        deviceGroups()
        repeat(60) { devices().firstOrNull { it.hasAgent }?.let { return it }; delay(1000) }
        fail("no online agent"); error("")
    }

    @Test fun `the screen comes as its size and JPEG tiles, and a click and keys do not cut it`() = runBlocking {
        assumeTrue("no test server with a screen", !url.isNullOrEmpty() && !cert.isNullOrEmpty() && withScreen)
        val s = admin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // The agent offers the desktop once it has found the X server, which may be after it connects.
            // El agente ofrece el escritorio cuando ha encontrado el servidor X, que puede ser después de conectar.
            var d = s.agent()
            repeat(60) { if (!d.canOpenDesktop) { delay(1000); d = s.agent() } }
            assertTrue("the agent says it has no desktop", d.canOpenDesktop)
            val desk = DesktopSession.open(s, d.id, scope)
            val size = withTimeout(60_000) { desk.events.first { it is DesktopEvent.Size || it is DesktopEvent.Message || it is DesktopEvent.NoImage } }
            assertTrue("no size but $size", size is DesktopEvent.Size)
            size as DesktopEvent.Size
            assertTrue("odd size $size", size.width >= 320 && size.height >= 200)
            val tile = withTimeout(60_000) { desk.events.first { it is DesktopEvent.Tile } } as DesktopEvent.Tile
            // A real JPEG (FF D8 … FF D9) inside the screen. / Un JPEG de verdad dentro de la pantalla.
            assertEquals(0xFF, tile.jpeg[0].toInt() and 0xff); assertEquals(0xD8, tile.jpeg[1].toInt() and 0xff)
            assertEquals(0xD9, tile.jpeg[tile.jpeg.size - 1].toInt() and 0xff)
            assertTrue("tile outside the screen", tile.x < size.width && tile.y < size.height)
            desk.mouseMove(10, 10); desk.mouseButton(MouseButton.Left, true, 10, 10); desk.mouseButton(MouseButton.Left, false, 10, 10)
            desk.char('a'); desk.key(13, true); desk.key(13, false)
            desk.setQuality(40, 512, 200)
            val before = desk.tilesReceived
            withTimeout(30_000) { while (desk.tilesReceived == before) delay(100) } // a new quality repaints / una calidad nueva repinta
            // Out of sight (as the app does): paused and one frame every 2 s. Typing a letter every 200 ms must bring far
            // fewer tiles than the same typing on screen (Linux's agent ignores the pause; the frame time it honours).
            // Fuera de la vista (como hace la app): en pausa y una imagen cada 2 s. Escribir una letra cada 200 ms debe traer
            // muchas menos teselas que lo mismo en pantalla (el agente de Linux no hace caso a la pausa; al tiempo sí).
            desk.mouseButton(MouseButton.Left, true, 150, 100); desk.mouseButton(MouseButton.Left, false, 150, 100); delay(1500)
            suspend fun tilesWhileTyping(text: String): Long {
                val from = desk.tilesReceived
                text.forEach { desk.char(it); delay(200) }
                delay(1000)
                return desk.tilesReceived - from
            }
            val onScreen = tilesWhileTyping("abcdefghij")
            desk.setPaused(true); desk.setQuality(40, 512, 2000); delay(3000)
            val hidden = tilesWhileTyping("klmnopqrst")
            println("tiles typing on screen: $onScreen, out of sight: $hidden")
            assertTrue("typing on screen brought only $onScreen tiles", onScreen >= 6)
            assertTrue("out of sight brought $hidden tiles (on screen $onScreen)", hidden * 3 <= onScreen)
            // Back: unpaused, its frame time again, and the screen comes on the same tunnel. / De vuelta, la pantalla llega.
            val back = desk.tilesReceived
            desk.setPaused(false); desk.setQuality(40, 512, 100)
            withTimeout(30_000) { while (desk.tilesReceived == back) delay(100) }
            assertTrue(desk.tunnel.isOpen)
            desk.close()
        } finally {
            scope.cancel()
            s.close()
        }
    }
}
