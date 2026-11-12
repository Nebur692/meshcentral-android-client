package io.github.nebur692.meshclient.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the Windows app's DesktopProtocolTests, byte for byte. / Los mismos casos que la app de Windows, byte a byte. */
class DesktopProtocolTest {
    private fun parser(): Pair<MutableList<Pair<Int, ByteArray>>, DesktopFrameParser> {
        val got = mutableListOf<Pair<Int, ByteArray>>()
        return got to DesktopFrameParser { c, d -> got += c to d }
    }

    private fun cmd(c: Int, vararg payload: Byte): ByteArray {
        val size = 4 + payload.size
        return byteArrayOf((c shr 8).toByte(), c.toByte(), (size shr 8).toByte(), size.toByte()) + payload
    }

    @Test fun `a whole command and its padding`() {
        val (got, p) = parser()
        p.feed(cmd(7, 0x04, 0x00, 0x03, 0x00) + ByteArray(4)) // 8 + 4 of padding / 8 + 4 de relleno
        assertEquals(1, got.size)
        assertEquals(7, got[0].first)
        assertEquals(8, got[0].second.size)
    }

    @Test fun `a command cut across frames`() {
        val (got, p) = parser()
        val tile = cmd(3, *ByteArray(30000) { it.toByte() })
        p.feed(tile.copyOfRange(0, 1000))
        p.feed(tile.copyOfRange(1000, 21000))
        assertTrue(got.isEmpty())
        p.feed(tile.copyOfRange(21000, tile.size))
        assertEquals(1, got.size)
        assertEquals(3, got[0].first)
        assertArrayEquals(tile, got[0].second)
    }

    @Test fun `a jumbo command over 64 KB`() {
        val (got, p) = parser()
        val payload = ByteArray(100_000) { (it * 7).toByte() }
        val size = 8 + payload.size
        val inner = byteArrayOf(0, 3, 0, 0, 0, 10, 0, 20) + payload
        val jumbo = byteArrayOf(0, 27, 0, 8, 0, (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) + inner
        p.feed(jumbo.copyOfRange(0, 50_000))
        p.feed(jumbo.copyOfRange(50_000, jumbo.size))
        assertEquals(1, got.size)
        assertEquals(3, got[0].first)
        assertArrayEquals(inner, got[0].second)
    }

    @Test fun `too short frames are ignored`() {
        val (got, p) = parser()
        p.feed(byteArrayOf(0, 3))
        p.feed(byteArrayOf(0, 3, 0, 2)) // a size below the header / un tamaño menor que la cabecera
        assertTrue(got.isEmpty())
    }

    @Test fun `key commands`() {
        assertArrayEquals(DesktopSession.bytes(0, 1, 0, 6, 0, 65), DesktopSession.keyCommand(65, down = true, extended = false))
        assertArrayEquals(DesktopSession.bytes(0, 1, 0, 6, 1, 65), DesktopSession.keyCommand(65, down = false, extended = false))
        assertArrayEquals(DesktopSession.bytes(0, 1, 0, 6, 4, 37), DesktopSession.keyCommand(37, down = true, extended = true))
        assertArrayEquals(DesktopSession.bytes(0, 1, 0, 6, 3, 37), DesktopSession.keyCommand(37, down = false, extended = true))
    }

    @Test fun `mouse commands - up is the button twice, a move is 0`() {
        assertArrayEquals(DesktopSession.bytes(0, 2, 0, 10, 0, 0x02, 0x01, 0x2C, 0x00, 0xC8), DesktopSession.mouseCommand(MouseButton.Left, true, 300, 200))
        assertArrayEquals(DesktopSession.bytes(0, 2, 0, 10, 0, 0x04, 0x01, 0x2C, 0x00, 0xC8), DesktopSession.mouseCommand(MouseButton.Left, false, 300, 200))
        assertArrayEquals(DesktopSession.bytes(0, 2, 0, 10, 0, 0x10, 0x01, 0x2C, 0x00, 0xC8), DesktopSession.mouseCommand(MouseButton.Right, false, 300, 200))
        assertArrayEquals(DesktopSession.bytes(0, 2, 0, 10, 0, 0x00, 0x01, 0x2C, 0x00, 0xC8), DesktopSession.mouseCommand(MouseButton.None, null, 300, 200))
    }

    @Test fun `wheel commands - negative as the website writes them`() {
        assertArrayEquals(DesktopSession.bytes(0, 2, 0, 12, 0, 0, 0, 10, 0, 20, 0x00, 0x78), DesktopSession.wheelCommand(10, 20, 120))
        assertArrayEquals(DesktopSession.bytes(0, 2, 0, 12, 0, 0, 0, 10, 0, 20, 0xFF, 0x87), DesktopSession.wheelCommand(10, 20, -120))
    }
}

class DisplayLayoutTest {
    private fun layout(vararg ds: IntArray): ByteArray {
        val size = 4 + ds.size * 10
        val b = mutableListOf<Byte>(0, 82, (size shr 8).toByte(), size.toByte())
        for (d in ds) for (v in d) { b += (v shr 8).toByte(); b += v.toByte() }
        return b.toByteArray()
    }

    @Test fun `two monitors side by side`() {
        assertEquals(listOf(DisplayRect(1, 0, 0, 1920, 1080), DisplayRect(2, 1920, 0, 1280, 1024)),
            DesktopSession.parseLayout(layout(intArrayOf(1, 0, 0, 1920, 1080), intArrayOf(2, 1920, 0, 1280, 1024))))
    }

    @Test fun `a monitor left of the main one has a negative x and starts the image`() {
        val l = DesktopSession.parseLayout(layout(intArrayOf(1, 0, 0, 1920, 1080), intArrayOf(2, -1280, 56, 1280, 1024)))!!
        assertEquals(DisplayRect(2, 0, 56, 1280, 1024), l.single { it.id == 2 })
        assertEquals(DisplayRect(1, 1280, 0, 1920, 1080), l.single { it.id == 1 })
    }

    @Test fun `a monitor above comes first`() {
        assertEquals(listOf(DisplayRect(2, 0, 0, 1920, 1080), DisplayRect(1, 0, 1080, 1920, 1080)),
            DesktopSession.parseLayout(layout(intArrayOf(1, 0, 0, 1920, 1080), intArrayOf(2, 0, -1080, 1920, 1080))))
    }

    @Test fun `malformed layouts are ignored`() {
        assertNull(DesktopSession.parseLayout(byteArrayOf(0, 82, 0, 4)))
        assertNull(DesktopSession.parseLayout(byteArrayOf(0, 82, 0, 9, 0, 1, 0, 0, 0)))
    }
}

class NoImageWatchTest {
    @Test fun `with nothing ever coming it asks each time and then gives up`() = runBlocking {
        var asked = 0
        assertTrue(NoImageWatch.gaveUp({ false }, { asked++ }, 5, 1))
        assertEquals(5, asked)
    }

    @Test fun `an image at the very start asks nothing`() = runBlocking {
        var asked = 0
        assertFalse(NoImageWatch.gaveUp({ true }, { asked++ }, 5, 1))
        assertEquals(0, asked)
    }

    @Test fun `what the asking brings stops it and is not giving up`() = runBlocking {
        var asked = 0
        assertFalse(NoImageWatch.gaveUp({ asked >= 2 }, { asked++ }, 5, 1))
        assertEquals(2, asked)
    }

    @Test fun `what the last asking brings still counts`() = runBlocking {
        var asked = 0
        assertFalse(NoImageWatch.gaveUp({ asked == 5 }, { asked++ }, 5, 1))
    }
}
