package io.github.nebur692.meshclient.core

import java.io.IOException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A signed-in session on a MeshCentral server, over its control web socket (control.ashx): the same channel as the
 * website and meshctrl, so it works with any installation. / Una sesión abierta en un servidor MeshCentral, por su web
 * socket de control: el mismo canal que la web y meshctrl, así que funciona con cualquier instalación.
 */
class MeshSession private constructor(
    val server: ServerAddress,
    val trust: CertificateTrust,
    private val listener: Listener,
    private val timeoutMs: Long,
) {
    private lateinit var socket: WebSocket

    var serverInfo: ServerInfo = ServerInfo("", "", null)
        private set
    var user: UserInfo = UserInfo("", "", 0)
        private set

    /** Live device changes. / Cambios de equipos en vivo. */
    val deviceChanges: SharedFlow<DeviceChange> get() = listener.changes

    /** Why it ended, once it has (never set by [close]). / Por qué acabó, cuando acaba (nunca por [close]). */
    val closedReason: StateFlow<MeshClientException?> get() = listener.closedReason

    val isOpen: Boolean get() = listener.open && closedReason.value == null

    suspend fun deviceGroups(): List<DeviceGroup> {
        // "meshes" does not echo responseid. / «meshes» no devuelve el responseid.
        val reply = request(buildJsonObject { put("action", "meshes") }, { it.str("action") == "meshes" },
            "equipos/ver-pantalla/tiempo-agotado", "equipos/ver-pantalla/conexion-cortada")
        val arr = reply["meshes"] as? JsonArray ?: throw unexpected("equipos/ver-pantalla/respuesta-ilegible", "meshes")
        return arr.filterIsInstance<JsonObject>().map(DeviceGroup::from)
    }

    suspend fun devices(): List<Device> {
        val id = newId()
        val reply = request(buildJsonObject { put("action", "nodes"); put("responseid", id) },
            { it.str("action") == "nodes" && it.str("responseid") == id },
            "equipos/ver-pantalla/tiempo-agotado", "equipos/ver-pantalla/conexion-cortada")
        val byGroup = reply["nodes"] as? JsonObject ?: throw unexpected("equipos/ver-pantalla/respuesta-ilegible", "nodes")
        return byGroup.flatMap { (groupId, nodes) ->
            (nodes as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { Device.from(it, groupId) }
        }
    }

    internal suspend fun request(
        message: JsonObject, match: (JsonObject) -> Boolean, timeoutKey: String, lostKey: String, timeout: Long = timeoutMs,
    ): JsonObject {
        if (!isOpen) throw lost(lostKey, null)
        val wait = listener.waitFor(match)
        if (!socket.send(message.toString())) {
            listener.remove(wait)
            throw lost(lostKey, null)
        }
        return try {
            withTimeout(timeout) { wait.result.await() }
        } catch (e: TimeoutCancellationException) {
            listener.remove(wait)
            if (!isOpen) throw lost(lostKey, null)
            throw MeshClientException(MeshFailure.Timeout, timeoutKey, "The server did not answer \"${message.str("action")}\" in ${timeout / 1000} s.")
        } catch (e: MeshClientException) {
            if (e.failure == MeshFailure.ConnectionLost) throw lost(lostKey, e) else throw e
        }
    }

    /** Closes the channel; [closedReason] stays null. / Cierra el canal; [closedReason] se queda en null. */
    fun close() {
        listener.closing = true
        socket.close(1000, null)
        listener.shutdown(lost("conexion/ver-pantalla/conexion-cortada", null), raise = false)
    }

    /** Receives on OkHttp's thread; waiters are matched there. / Recibe en el hilo de OkHttp; ahí se casan las esperas. */
    internal class Listener : WebSocketListener() {
        class Waiter(val match: (JsonObject) -> Boolean) { val result = CompletableDeferred<JsonObject>() }

        private val waiters = mutableListOf<Waiter>()
        val opened = CompletableDeferred<Unit>()
        val changes = MutableSharedFlow<DeviceChange>(extraBufferCapacity = 512)
        val closedReason = MutableStateFlow<MeshClientException?>(null)
        @Volatile var open = false
        @Volatile var closing = false
        @Volatile private var finished = false
        var onFailureBeforeOpen: ((Throwable, Response?) -> MeshClientException)? = null

        fun waitFor(match: (JsonObject) -> Boolean): Waiter = Waiter(match).also { synchronized(waiters) { waiters += it } }
        fun remove(w: Waiter) = synchronized(waiters) { waiters.remove(w) }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            open = true
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return
            val hit = synchronized(waiters) {
                val h = waiters.filter { w -> runCatching { w.match(o) }.getOrDefault(false) }
                waiters.removeAll(h)
                h
            }
            hit.forEach { it.result.complete(o) }
            if (o.str("action") == "event") (o["event"] as? JsonObject)?.let(::onEvent)
        }

        private fun onEvent(e: JsonObject) {
            val change: DeviceChange? = when (e.str("action")) {
                "addnode" -> (e["node"] as? JsonObject)?.let { DeviceChange.Added(Device.from(it)) }
                // Moved to another group too: the node comes whole, with its new meshid.
                // También al moverlo a otro grupo: el equipo llega entero, con su meshid nuevo.
                "changenode", "nodemeshchange" -> (e["node"] as? JsonObject)?.let { DeviceChange.Changed(Device.from(it)) }
                "removenode" -> e.str("nodeid")?.let { DeviceChange.Removed(it) }
                "nodeconnect" -> e.str("nodeid")?.let { DeviceChange.Connection(it, (e.long("conn") ?: 0).toInt(), (e.long("pwr") ?: 0).toInt()) }
                else -> null
            }
            if (change != null && change.deviceId.isNotEmpty()) changes.tryEmit(change)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            shutdown(lost("conexion/ver-pantalla/conexion-cortada", null), raise = !closing)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            shutdown(lost("conexion/ver-pantalla/conexion-cortada", null), raise = !closing)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!open) {
                opened.completeExceptionally(onFailureBeforeOpen?.invoke(t, response) ?: lost("conexion/iniciar-sesion/sin-conexion", t))
            }
            shutdown(lost("conexion/ver-pantalla/conexion-cortada", t), raise = !closing)
        }

        fun shutdown(reason: MeshClientException, raise: Boolean) {
            if (finished) return
            finished = true
            open = false
            val pending = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
            // Whoever still waits gets the reason instead of hanging. / Al que sigue esperando se le da el motivo.
            pending.forEach { it.result.completeExceptionally(reason) }
            if (raise) closedReason.value = reason
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        private val json = Json { ignoreUnknownKeys = true }
        private val random = SecureRandom()
        private val baseClient: OkHttpClient by lazy { OkHttpClient() }

        /** Signs in with username, password and, if asked, the second factor. / Entra con usuario, contraseña y, si se pide, el doble factor. */
        suspend fun connect(
            server: ServerAddress, credentials: PasswordCredentials, trust: CertificateTrust,
            userAgent: String = "MeshCentralAndroidClient", timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): MeshSession = connectCore(server, trust, userAgent, timeoutMs, { it.header("x-meshauth", credentials.header()) }) { loginFailure(it, credentials) }

        private suspend fun connectCore(
            server: ServerAddress, trust: CertificateTrust, userAgent: String, timeoutMs: Long,
            authenticate: (Request.Builder) -> Request.Builder, onClose: (JsonObject) -> MeshClientException,
        ): MeshSession {
            val client = trust.applyTo(baseClient.newBuilder())
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
            val listener = Listener()
            listener.onFailureBeforeOpen = { t, r -> connectFailure(t, r, trust) }
            // Registered before connecting, so the first messages cannot be missed.
            // Se apuntan antes de conectar, para no perder los primeros mensajes.
            val serverInfo = listener.waitFor { it.str("action") == "serverinfo" || it.str("action") == "close" }
            val userInfo = listener.waitFor { it.str("action") == "userinfo" || it.str("action") == "close" }
            val session = MeshSession(server, trust, listener, timeoutMs)
            val request = authenticate(Request.Builder().url(server.controlUrl).header("User-Agent", userAgent)).build()
            session.socket = client.newWebSocket(request, listener)
            try {
                withTimeout(timeoutMs) { listener.opened.await() }
            } catch (e: TimeoutCancellationException) {
                session.socket.cancel()
                throw MeshClientException(MeshFailure.Timeout, "conexion/iniciar-sesion/tiempo-agotado", "The server did not answer in time.")
            }
            try {
                val first = withTimeout(timeoutMs) { serverInfo.result.await() }
                if (first.str("action") == "close") throw onClose(first)
                val me = withTimeout(timeoutMs) { userInfo.result.await() }
                if (me.str("action") == "close") throw onClose(me)
                session.serverInfo = ServerInfo.from(first["serverinfo"] as? JsonObject ?: JsonObject(emptyMap()))
                session.user = UserInfo.from(me["userinfo"] as? JsonObject ?: JsonObject(emptyMap()))
                return session
            } catch (e: TimeoutCancellationException) {
                session.close()
                throw MeshClientException(MeshFailure.Timeout, "conexion/iniciar-sesion/tiempo-agotado", "The server accepted the connection but did not answer the login in time.")
            } catch (e: Throwable) {
                session.close()
                throw e
            }
        }

        internal fun loginFailure(close: JsonObject, credentials: PasswordCredentials): MeshClientException {
            val msg = close.str("msg").orEmpty()
            val cause = close.str("cause").orEmpty()
            val code = credentials.twoFactorCode
            val sentCode = !code.isNullOrBlank() && code != PasswordCredentials.EMAIL_CODE && code != PasswordCredentials.SMS_CODE
            fun e(f: MeshFailure, key: String, text: String) = MeshClientException(f, key, text).apply {
                serverMessage = msg.ifEmpty { cause }
                emailCodeAvailable = close.bool("email2fa")
                smsCodeAvailable = close.bool("sms2fa")
                emailCodeSent = close.bool("email2fasent")
            }
            return when {
                msg == "tokenrequired" && sentCode -> e(MeshFailure.InvalidTwoFactorCode, "conexion/verificar-identidad/credencial-invalida", "The second-factor code is not valid.")
                msg == "tokenrequired" -> e(MeshFailure.TwoFactorRequired, "conexion/verificar-identidad/falta-dato", "The server asks for a second-factor code.")
                cause == "banned" || cause == "locked" -> e(MeshFailure.TooManyAttempts, "conexion/iniciar-sesion/limite-de-tasa", "Too many failed attempts: the server is refusing logins for a while.")
                cause == "notools" -> e(MeshFailure.ToolsNotAllowed, "conexion/iniciar-sesion/no-autorizado", "This account is not allowed to use tools like this one.")
                cause == "emailvalidation" -> e(MeshFailure.EmailValidationRequired, "conexion/iniciar-sesion/falta-dato", "The account's email address has not been verified yet.")
                cause == "noauth" -> e(MeshFailure.InvalidCredentials, "conexion/iniciar-sesion/credencial-invalida", "Wrong username or password.")
                else -> e(MeshFailure.Rejected, "conexion/iniciar-sesion/rechazo-remoto", "The server refused the login ($cause $msg).")
            }
        }

        internal fun connectFailure(t: Throwable, response: Response?, trust: CertificateTrust): MeshClientException {
            if (trust.rejectedSha256 != null && (t is SSLException || t.cause is SSLException))
                return MeshClientException(MeshFailure.UntrustedCertificate, "conexion/iniciar-sesion/tls", "The server's certificate is not trusted.", t).apply {
                    certificateSha256 = trust.rejectedSha256
                    certificateSubject = trust.rejectedSubject
                }
            val status = response?.code
            if (status != null && status != 101)
                return MeshClientException(MeshFailure.HttpError, "conexion/iniciar-sesion/error-remoto",
                    "The server answered HTTP $status: it may not be a MeshCentral server, or the address is wrong.", t).apply { httpStatus = status }
            if (generateSequence(t) { it.cause }.any { it is UnknownHostException })
                return MeshClientException(MeshFailure.NameNotResolved, "conexion/iniciar-sesion/dns", "The server name could not be found.", t)
            if (t is IOException && t.message?.contains("timeout", ignoreCase = true) == true)
                return MeshClientException(MeshFailure.Timeout, "conexion/iniciar-sesion/tiempo-agotado", "The server did not answer in time.", t)
            return MeshClientException(MeshFailure.ServerUnreachable, "conexion/iniciar-sesion/sin-conexion", "Could not connect to the server.", t)
        }

        internal fun lost(key: String, cause: Throwable?) =
            MeshClientException(MeshFailure.ConnectionLost, key, "The connection to the server was lost.", cause)

        private fun unexpected(key: String, what: String) =
            MeshClientException(MeshFailure.UnexpectedResponse, key, "The server sent an unexpected \"$what\" reply.")

        private fun newId(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
