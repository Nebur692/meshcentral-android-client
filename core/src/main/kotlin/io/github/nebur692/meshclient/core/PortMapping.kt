package io.github.nebur692.meshclient.core

import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What a port mapping points at. [remoteHost] null is the device itself; [localPort] 0 takes any free one.
 * A dónde apunta una redirección de puerto. [remoteHost] null es el propio equipo; [localPort] 0 toma uno libre.
 */
data class PortTarget(val nodeId: String, val deviceName: String, val remotePort: Int, val remoteHost: String? = null, val localPort: Int = 0) {
    val destination: String get() = "${remoteHost?.takeIf { it.isNotBlank() } ?: deviceName}:$remotePort"
}

/** How a mapping is doing. / Cómo va una redirección. */
data class PortMappingStats(val active: Int, val total: Int, val bytesUp: Long, val bytesDown: Long, val lastError: MeshClientException?)

/**
 * A port on this phone (127.0.0.1 only, so nothing else on the network can use it) whose every connection goes, through
 * the device's agent, to [PortTarget.remotePort]: MeshCentral Router's port mapping inside the app. Another app on the
 * phone (an RDP or VNC client, a browser) connects to 127.0.0.1:[localPort]. Ported from the Windows app.
 * Un puerto de este móvil (solo 127.0.0.1, para que nadie más de la red lo use) cuyas conexiones van, por el agente del
 * equipo, al puerto remoto: la redirección de MeshCentral Router dentro de la app. Otra app del móvil (un cliente de RDP
 * o VNC, un navegador) conecta a 127.0.0.1:[localPort].
 */
/** [session] is asked for each new connection: after a reconnection the old one is closed. / Se pide en cada conexión nueva. */
class PortMapping private constructor(private val session: () -> MeshSession?, val target: PortTarget, private val server: ServerSocket) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger()
    private val total = AtomicInteger()
    private val up = AtomicLong()
    private val down = AtomicLong()
    @Volatile private var lastError: MeshClientException? = null
    private val _stats = MutableStateFlow(PortMappingStats(0, 0, 0, 0, null))

    /** The port actually listening on 127.0.0.1. / El puerto que escucha de verdad en 127.0.0.1. */
    val localPort: Int = server.localPort

    val stats: StateFlow<PortMappingStats> get() = _stats

    private fun publish() { _stats.value = PortMappingStats(active.get(), total.get(), up.get(), down.get(), lastError) }

    private val publishing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun publishSoon() {
        if (!publishing.compareAndSet(false, true)) return
        scope.launch { kotlinx.coroutines.delay(500); publishing.set(false); publish() }
    }

    private fun accept() = scope.launch {
        while (isActive) {
            val client = try { server.accept() } catch (_: IOException) { return@launch }
            client.tcpNoDelay = true
            launch { carry(client) }
        }
    }

    private suspend fun carry(client: Socket) {
        active.incrementAndGet(); total.incrementAndGet(); publish()
        try {
            client.use { c ->
                val relay = try {
                    val s = session() ?: throw MeshSession.lost("redireccion/llevar-conexion/conexion-cortada", null)
                    TcpRelay.open(s, target.nodeId, target.remotePort, target.remoteHost)
                } catch (e: MeshClientException) { lastError = e; publish(); return }
                // Counted as the bytes pass and told at most twice a second: nothing wakes up while nothing moves (it
                // used to look every 500 ms). / Se cuentan al pasar y se dicen dos veces por segundo como mucho: nada
                // se despierta mientras no pasa nada (antes se miraba cada 500 ms).
                relay.onTraffic = { u, d -> up.addAndGet(u.toLong()); down.addAndGet(d.toLong()); publishSoon() }
                relay.pump(c.getInputStream(), c.getOutputStream()) { runCatching { c.close() } }
            }
        } finally {
            active.decrementAndGet(); publish()
        }
    }

    /** Stops listening and closes every connection. / Deja de escuchar y cierra todas las conexiones. */
    fun close() {
        runCatching { server.close() }
        scope.cancel()
    }

    companion object {
        /** Starts listening; a local port in use fails here, before anything reaches the server. / Un puerto ocupado falla aquí. */
        fun start(session: MeshSession, target: PortTarget): PortMapping = start({ session }, target)

        fun start(session: () -> MeshSession?, target: PortTarget): PortMapping {
            if (target.remotePort !in 1..65535 || target.localPort !in 0..65535)
                throw MeshClientException(MeshFailure.Rejected, "redireccion/redirigir-puerto/dato-invalido", "Ports go from 1 to 65535.")
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), target.localPort))
            } catch (e: BindException) {
                runCatching { server.close() }
                throw MeshClientException(MeshFailure.Rejected, "redireccion/redirigir-puerto/conflicto-estado",
                    "Port ${target.localPort} of this phone is already in use by another app.", e)
            }
            return PortMapping(session, target, server).also { it.accept() }
        }
    }
}
