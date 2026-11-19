package io.github.nebur692.meshclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The last code by ntfy is offered once, for a few minutes, and only to its server; one the waiting screen already
 * got is not offered again. / El último código por ntfy se ofrece una vez, unos minutos y solo a su servidor.
 */
class NtfyCodeTest {
    private val server = "https://mesh.example/"

    @Test fun `a kept code is taken once`() {
        NtfyPush.keep(NtfyPush.Code(server, "123456"), atMs = 1_000)
        assertEquals("123456", NtfyPush.take(server, nowMs = 2_000))
        assertNull(NtfyPush.take(server, nowMs = 3_000))
    }

    @Test fun `another server, an old code or one already used are not offered`() {
        NtfyPush.keep(NtfyPush.Code(server, "111111"), atMs = 1_000)
        assertNull(NtfyPush.take("https://other.example/", nowMs = 2_000))
        assertNull(NtfyPush.take(server, nowMs = 1_000 + NtfyPush.KEEP_MS + 1))
        NtfyPush.keep(NtfyPush.Code(server, "222222"), atMs = 1_000)
        NtfyPush.used("222222")
        assertNull(NtfyPush.take(server, nowMs = 2_000))
    }
}
