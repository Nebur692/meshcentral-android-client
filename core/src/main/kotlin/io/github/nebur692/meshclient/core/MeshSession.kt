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
import kotlinx.serialization.json.JsonPrimitive
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
    internal val timeoutMs: Long,
) {
    private lateinit var socket: WebSocket

    /** The client of this session (its pinned certificate), for tunnels. / El cliente de esta sesión (su certificado fijado), para los túneles. */
    internal lateinit var http: OkHttpClient

    var serverInfo: ServerInfo = ServerInfo("", "", null)
        private set
    var user: UserInfo = UserInfo("", "", 0)
        private set

    /** Live device changes. / Cambios de equipos en vivo. */
    val deviceChanges: SharedFlow<DeviceChange> get() = listener.changes

    /** Notifications for this person while connected. / Avisos para esta persona mientras está conectada. */
    val notifications: SharedFlow<ServerNotification> get() = listener.notifications

    /** Why it ended, once it has (never set by [close]). / Por qué acabó, cuando acaba (nunca por [close]). */
    val closedReason: StateFlow<MeshClientException?> get() = listener.closedReason

    val isOpen: Boolean get() = listener.open && closedReason.value == null

    suspend fun deviceGroups(): List<DeviceGroup> {
        // "meshes" does not echo responseid. / «meshes» no devuelve el responseid.
        val reply = request(buildJsonObject { put("action", "meshes") }, { it.str("action") == "meshes" },
            "equipos/ver-pantalla/tiempo-agotado", "equipos/ver-pantalla/conexion-cortada")
        val arr = reply["meshes"] as? JsonArray ?: throw unexpected("equipos/ver-pantalla/respuesta-ilegible", "meshes")
        return arr.filterIsInstance<JsonObject>().map(DeviceGroup::from).also { list -> groups = list.associateBy { it.id } }
    }

    /** The groups last listed, for working out rights. / Los últimos grupos listados, para calcular permisos. */
    @Volatile internal var groups: Map<String, DeviceGroup> = emptyMap()

    /** The two cookies a relay tunnel needs (browser side and agent side). / Las dos cookies de un túnel de relé (lado navegador y lado agente). */
    internal class RelayCookies(val cookie: String, val relayCookie: String)

    internal suspend fun relayCookies(): RelayCookies {
        val reply = request(buildJsonObject { put("action", "authcookie") }, { it.str("action") == "authcookie" },
            "conexion/abrir-detalle/tiempo-agotado", "conexion/abrir-detalle/conexion-cortada")
        val c = reply.str("cookie"); val r = reply.str("rcookie")
        if (c == null || r == null) throw unexpected("conexion/abrir-detalle/respuesta-ilegible", "authcookie")
        return RelayCookies(c, r)
    }

    /** Sends without waiting for an answer (for requests the server never answers). / Manda sin esperar respuesta. */
    internal fun send(message: JsonObject, lostKey: String) {
        if (!isOpen || !socket.send(message.toString())) throw lost(lostKey, null)
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

    /**
     * A login token for this account (MeshCentral's "createLoginToken"): a user and password of its own that sign in
     * without the single sign-on provider, until [lifetimeMinutes] pass (0 = never). The person is asked before.
     * Un token de entrada de esta cuenta: usuario y contraseña propios que entran sin el proveedor de inicio único hasta
     * que pasan [lifetimeMinutes] (0 = nunca). Se le pregunta antes a la persona.
     */
    suspend fun createLoginToken(name: String, lifetimeMinutes: Long): LoginToken {
        val id = newId()
        // Success comes without the responseid, errors with it (as the Windows app measured). / El éxito llega sin el responseid.
        val reply = request(buildJsonObject { put("action", "createLoginToken"); put("name", name); put("expire", lifetimeMinutes); put("responseid", id) },
            { it.str("action") == "createLoginToken" && (it.str("responseid") == id || (it.str("tokenUser") != null && it.str("name") == name)) },
            "usuarios/cambiar-ajustes/tiempo-agotado", "usuarios/cambiar-ajustes/conexion-cortada")
        return LoginToken.from(reply) ?: throw MeshClientException(MeshFailure.Rejected, "usuarios/cambiar-ajustes/rechazo-remoto",
            "The server did not give a login token (${reply.str("result")}).").apply { serverMessage = reply.str("result") }
    }

    /** This account's login tokens (without their passwords). / Los tokens de entrada de esta cuenta (sin su contraseña). */
    suspend fun loginTokens(): List<LoginToken> = tokens(buildJsonObject { put("action", "loginTokens") })

    /** Removes login tokens by their user, and gives the ones left. / Quita tokens por su usuario y da los que quedan. */
    suspend fun removeLoginTokens(tokenUsers: List<String>): List<LoginToken> =
        tokens(buildJsonObject { put("action", "loginTokens"); put("remove", JsonArray(tokenUsers.map(::JsonPrimitive))) })

    private suspend fun tokens(message: JsonObject): List<LoginToken> {
        val reply = request(message, { it.str("action") == "loginTokens" && it["loginTokens"] is JsonArray },
            "usuarios/cambiar-ajustes/tiempo-agotado", "usuarios/cambiar-ajustes/conexion-cortada")
        return (reply["loginTokens"] as JsonArray).filterIsInstance<JsonObject>().mapNotNull(LoginToken::from)
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
        val notifications = MutableSharedFlow<ServerNotification>(extraBufferCapacity = 64)
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
            // Replies to this app's own requests carry a titleid and are answered where they were asked (as Windows).
            // Las respuestas a peticiones de la propia app llevan titleid y se contestan donde se pidieron (como Windows).
            if (o.str("action") == "msg" && o.str("type") == "notify" && o["titleid"] == null) {
                val text = o.str("value").orEmpty()
                if (text.isNotBlank()) notifications.tryEmit(ServerNotification(o.str("title") ?: o.str("username").orEmpty(), text))
            }
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
        @Volatile private var baseClient: OkHttpClient = OkHttpClient()

        /**
         * The phone changed network (Wi-Fi, mobile data): every kept connection and route belongs to the old one, and a
         * connection on a network that is gone does not always fail, it can hang until the timeout (seen on a phone that
         * came home on mobile data: no sign-in on Wi-Fi until the app was closed). New sessions start on a new client.
         * El móvil cambió de red (wifi, datos): todo lo guardado es de la anterior, y una conexión en una red que ya no
         * está no siempre falla, puede quedarse colgada hasta el tiempo límite (visto en un móvil que llegó a casa con
         * datos: sin entrar con wifi hasta cerrar la app). Las sesiones nuevas empiezan con un cliente nuevo.
         */
        fun networkChanged() {
            val old = baseClient
            baseClient = OkHttpClient()
            old.connectionPool.evictAll()
        }

        /** Signs in with username, password and, if asked, the second factor. / Entra con usuario, contraseña y, si se pide, el doble factor. */
        suspend fun connect(
            server: ServerAddress, credentials: PasswordCredentials, trust: CertificateTrust,
            userAgent: String = "MeshCentralAndroidClient", timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): MeshSession = retryFresh { connectCore(server, trust, userAgent, timeoutMs, { it.header("x-meshauth", credentials.header()) }) { loginFailure(it, credentials) } }

        /** One more go on fresh connections when the first never reached the server. / Otro intento con conexiones nuevas si el primero no llegó. */
        private suspend fun retryFresh(connect: suspend () -> MeshSession): MeshSession = try {
            connect()
        } catch (e: MeshClientException) {
            if (!e.neverConnected) throw e
            networkChanged()
            connect()
        }

        /**
         * Signs in with the session cookies ("xid", "xid.sig") MeshCentral gave a browser after a single sign-on. The
         * cookie alone proves nothing (a refused account also gets one): only an accepted control channel means the
         * person is in. / Entra con las cookies de sesión que MeshCentral dio a un navegador tras un inicio de sesión
         * único. La cookie sola no prueba nada (una cuenta rechazada también la recibe): solo un canal aceptado.
         */
        suspend fun connectWithCookies(
            server: ServerAddress, cookies: Map<String, String>, trust: CertificateTrust,
            userAgent: String = "MeshCentralAndroidClient", timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): MeshSession {
            if ("xid" !in cookies) throw MeshClientException(MeshFailure.SessionExpired, "conexion/iniciar-sesion/sesion-cerrada", "There is no MeshCentral session to use.")
            val header = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
            return retryFresh { connectCore(server, trust, userAgent, timeoutMs, { it.header("Cookie", header) }, ::sessionFailure) }
        }

        /** Reads a browser's cookie line ("a=1; b=2") into names and values. / Lee la línea de cookies de un navegador. */
        fun parseCookies(line: String?): Map<String, String> =
            line.orEmpty().split(';').map(String::trim).mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()

        internal fun sessionFailure(close: JsonObject): MeshClientException {
            val cause = close.str("cause").orEmpty()
            val msg = close.str("msg").orEmpty()
            return when (cause) {
                "notools" -> MeshClientException(MeshFailure.ToolsNotAllowed, "conexion/iniciar-sesion/no-autorizado", "This account is not allowed to use tools like this one.")
                "banned", "locked" -> MeshClientException(MeshFailure.TooManyAttempts, "conexion/iniciar-sesion/limite-de-tasa", "The server is refusing logins for a while.")
                "noauth" -> MeshClientException(MeshFailure.SessionExpired, "conexion/iniciar-sesion/sesion-cerrada",
                    "The server did not accept the session: the sign-on was refused or the session has expired.")
                else -> MeshClientException(MeshFailure.Rejected, "conexion/iniciar-sesion/rechazo-remoto", "The server refused the session ($cause $msg).")
            }.apply { serverMessage = msg.ifEmpty { cause } }
        }

        private suspend fun connectCore(
            server: ServerAddress, trust: CertificateTrust, userAgent: String, timeoutMs: Long,
            authenticate: (Request.Builder) -> Request.Builder, onClose: (JsonObject) -> MeshClientException,
        ): MeshSession {
            // Two goes at most (retryFresh): each one waits 3/5 of the limit to connect, so a server that is really
            // unreachable says so in ~24 s, not 40. / Dos intentos como mucho: cada uno espera 3/5 del límite para conectar.
            val openMs = timeoutMs * 3 / 5
            val client = trust.applyTo(baseClient.newBuilder())
                .connectTimeout(openMs, TimeUnit.MILLISECONDS)
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
            session.http = client
            val request = authenticate(Request.Builder().url(server.controlUrl).header("User-Agent", userAgent)).build()
            session.socket = client.newWebSocket(request, listener)
            try {
                withTimeout(openMs) { listener.opened.await() }
            } catch (e: TimeoutCancellationException) {
                session.socket.cancel()
                throw MeshClientException(MeshFailure.Timeout, "conexion/iniciar-sesion/tiempo-agotado", "The server did not answer in time.").apply { neverConnected = true }
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
                return MeshClientException(MeshFailure.Timeout, "conexion/iniciar-sesion/tiempo-agotado", "The server did not answer in time.", t).apply { neverConnected = true }
            return MeshClientException(MeshFailure.ServerUnreachable, "conexion/iniciar-sesion/sin-conexion", "Could not connect to the server.", t)
        }

        internal fun lost(key: String, cause: Throwable?) =
            MeshClientException(MeshFailure.ConnectionLost, key, "The connection to the server was lost.", cause)

        private fun unexpected(key: String, what: String) =
            MeshClientException(MeshFailure.UnexpectedResponse, key, "The server sent an unexpected \"$what\" reply.")

        internal fun newId(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
