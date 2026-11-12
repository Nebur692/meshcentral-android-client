package io.github.nebur692.meshclient.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A sign-in that never reaches the server gets one more go on fresh connections (a phone that changed network kept
 * a connection that hangs). 10.255.255.1 swallows packets: each go waits 3/5 of the limit, so two goes take ~2.4 s
 * of a 2 s limit, and one alone would be ~1.2 s.
 * Un inicio que no llega al servidor tiene otro intento con conexiones nuevas. 10.255.255.1 se traga los paquetes:
 * cada intento espera 3/5 del límite, así que dos tardan ~2,4 s con un límite de 2 s, y uno solo ~1,2 s.
 */
class RetryFreshTest {
    @Test fun `a sign-in that never connects tries once more and then says it timed out`() = runBlocking {
        val start = System.nanoTime()
        val e = try {
            MeshSession.connect(ServerAddress.parse("https://10.255.255.1")!!, PasswordCredentials("a", "b"), CertificateTrust(), timeoutMs = 2_000)
            fail("it connected"); error("")
        } catch (e: MeshClientException) { e }
        val ms = (System.nanoTime() - start) / 1_000_000
        // A network that answers "unreachable" at once is not this case. / Una red que contesta «inalcanzable» al momento no es este caso.
        assumeTrue("this network refuses at once ($ms ms, ${e.failure})", e.failure == MeshFailure.Timeout)
        assertEquals("conexion/iniciar-sesion/tiempo-agotado", e.key)
        assertTrue("one go only? $ms ms", ms >= 2_200)
        assertTrue("too long: $ms ms", ms < 6_000)
    }
}
