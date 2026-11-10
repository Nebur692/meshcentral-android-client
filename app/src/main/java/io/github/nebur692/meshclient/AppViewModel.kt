package io.github.nebur692.meshclient

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nebur692.meshclient.core.CertificateTrust
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.DeviceChange
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.PasswordCredentials
import io.github.nebur692.meshclient.core.ServerAddress
import io.github.nebur692.meshclient.core.SignInMethods
import io.github.nebur692.meshclient.core.SingleSignOn
import io.github.nebur692.meshclient.data.SavedServer
import io.github.nebur692.meshclient.data.SavedServers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Login : Screen
    data object Devices : Screen
    data object Account : Screen
    data class DeviceDetail(val id: String) : Screen
    /** The provider's page, in a browser inside the app. / La página del proveedor, en un navegador dentro de la app. */
    data class SignOn(val method: SingleSignOn, val address: ServerAddress, val pinnedSha256: String?) : Screen
}

/** A failure as the screen shows it. / Un fallo tal como lo enseña la pantalla. */
data class UiError(val failure: MeshFailure, val key: String, val httpStatus: Int? = null, val missingFields: Boolean = false)

/** After a single sign-on: keep a login token so the next time skips the provider? / ¿Guardar un token para no pasar por el proveedor? */
data class RememberPrompt(val method: String, val days: Int)

data class CertificatePrompt(val host: String, val sha256: String, val changed: Boolean)

data class TwoFactorPrompt(val emailAvailable: Boolean, val emailSent: Boolean, val wrong: Boolean)

data class LoginForm(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val remember: Boolean = false,
    val busy: Boolean = false,
    val error: UiError? = null,
    val certificate: CertificatePrompt? = null,
    val twoFactor: TwoFactorPrompt? = null,
)

enum class DeviceFilter { All, Online, Windows, Linux }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val saved = SavedServers(app)

    var savedServers by mutableStateOf(saved.all())
        private set
    var form by mutableStateOf(LoginForm())
        private set
    var screen by mutableStateOf<Screen>(Screen.Login)
        private set
    var session by mutableStateOf<MeshSession?>(null)
        private set
    var lost by mutableStateOf(false)
        private set
    var groupNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    val devices = mutableStateMapOf<String, Device>()
    var query by mutableStateOf("")
    var filter by mutableStateOf(DeviceFilter.All)

    /** The single sign-on methods the server on the form offers (read from its login page). / Los que ofrece el servidor del formulario. */
    var signOnMethods by mutableStateOf<List<SingleSignOn>>(emptyList())
        private set
    var rememberPrompt by mutableStateOf<RememberPrompt?>(null)
        private set

    // Kept only while signed in, to reconnect or answer the second factor. / Solo mientras dura la sesión.
    private var credentials: PasswordCredentials? = null
    private var pendingPin: String? = null
    private var watching: Job? = null
    private var discovering: Job? = null
    private var signOnFor: String? = null
    private var signedInWith: Screen.SignOn? = null

    /** Why the session could not be remembered, shown once. / Por qué no se pudo recordar la sesión, se enseña una vez. */
    var rememberFailed by mutableStateOf<UiError?>(null)

    /** Fingerprints accepted in this run, before any sign-in saved them. / Huellas aceptadas en esta vez, antes de guardarlas. */
    private val acceptedPins = mutableMapOf<String, String>()

    /** The provider's page is already open (it runs in its own activity). / La página del proveedor ya está abierta. */
    var signOnLaunched = false

    // A single sign-on session without a remembered token reconnects with its cookies. / Sin token, se reconecta con sus cookies.
    private var signOnCookies: Map<String, String>? = null
    private var backgroundJob: Job? = null
    private var paused = false

    // Every property above this line: init runs in order, and pick() already uses them (a crash on the POCO with a
    // saved server). / Todas las propiedades por encima: init corre en orden y pick() ya las usa (se cerraba en el POCO).
    init {
        savedServers.firstOrNull()?.let(::pick)
    }

    private fun userAgent() = "MeshCentralAndroidClient/${BuildConfig.VERSION_NAME}"

    fun edit(change: LoginForm.() -> LoginForm) {
        val before = form.server
        form = form.change().copy(error = null)
        if (form.server != before) discoverSignOn()
    }

    /** Looks up, after a pause in typing, which single sign-on the server offers. / Mira, tras una pausa al escribir, qué inicio único ofrece. */
    private fun discoverSignOn(now: Boolean = false) {
        discovering?.cancel()
        val address = ServerAddress.parse(form.server)
        if (address == null) { signOnMethods = emptyList(); signOnFor = null; return }
        if (signOnFor == address.toString()) return
        discovering = viewModelScope.launch {
            if (!now) delay(700)
            val pin = savedServers.firstOrNull { it.address == address.toString() }?.pinnedSha256 ?: acceptedPins[address.toString()]
            val trust = CertificateTrust(pin)
            val found = SignInMethods.discover(address, trust, userAgent())
            signOnMethods = found
            // Empty because of a certificate not accepted yet says nothing: Connect looks again and asks.
            // Vacío por un certificado aún sin aceptar no dice nada: Conectar vuelve a mirar y pregunta.
            signOnFor = if (found.isEmpty() && trust.rejectedSha256 != null) null else address.toString()
        }
    }

    fun pick(server: SavedServer) {
        form = form.copy(server = server.address, username = server.username,
            password = saved.password(server).orEmpty(), remember = server.hasPassword, error = null)
        discoverSignOn(now = true)
    }

    fun forget(server: SavedServer) {
        saved.forget(server.address)
        savedServers = saved.all()
        // Forgetting the server on the form also takes its password off the screen. / Olvidar el servidor del formulario también quita su contraseña de la pantalla.
        if (form.server == server.address) form = LoginForm()
    }

    fun connect() {
        val address = ServerAddress.parse(form.server)
        if (address == null) {
            form = form.copy(error = UiError(MeshFailure.InvalidAddress, "conexion/iniciar-sesion/dato-invalido"))
            return
        }
        val known = savedServers.firstOrNull { it.address == address.toString() }
        pendingPin = known?.pinnedSha256 ?: acceptedPins[address.toString()]
        // A single sign-on account saved here: its remembered token, or the provider again. / Una cuenta de inicio único guardada.
        if (known?.singleSignOn != null && form.password.isEmpty() && form.username == known.username) {
            val method = SingleSignOn(known.singleSignOn)
            val token = saved.token(known)
            if (token == null) startSignOn(method) else {
                credentials = PasswordCredentials(token.first, token.second)
                attempt(address, credentials!!, viaToken = method)
            }
            return
        }
        // Nothing typed: the server's single sign-on if it has one, otherwise say what is missing.
        // Nada escrito: el inicio único del servidor si lo tiene; si no, decir qué falta.
        if (form.username.isBlank() && form.password.isEmpty()) {
            form = form.copy(busy = true, error = null)
            viewModelScope.launch {
                val trust = CertificateTrust(pendingPin)
                val methods = if (signOnFor == address.toString()) signOnMethods
                else SignInMethods.discover(address, trust, userAgent()).also { signOnFor = address.toString(); signOnMethods = it }
                form = form.copy(busy = false)
                // Its own certificate, not accepted yet: that first, then on. / Su propio certificado, aún sin aceptar: eso primero.
                val rejected = trust.rejectedSha256
                if (methods.isEmpty() && rejected != null) {
                    credentials = null
                    form = form.copy(certificate = CertificatePrompt(address.host, rejected, changed = pendingPin != null && pendingPin != rejected))
                    return@launch
                }
                if (methods.size == 1) startSignOn(methods[0])
                else form = form.copy(error = UiError(MeshFailure.InvalidCredentials, "conexion/iniciar-sesion/falta-dato", missingFields = true))
            }
            return
        }
        credentials = PasswordCredentials(form.username.trim(), form.password)
        attempt(address, credentials!!)
    }

    /** Opens the provider's page. / Abre la página del proveedor. */
    fun startSignOn(method: SingleSignOn) {
        val address = ServerAddress.parse(form.server) ?: return
        signOnLaunched = false
        form = form.copy(error = null, busy = false)
        screen = Screen.SignOn(method, address, savedServers.firstOrNull { it.address == address.toString() }?.pinnedSha256 ?: pendingPin)
    }

    fun cancelSignOn() { signOnLaunched = false; if (screen is Screen.SignOn) screen = Screen.Login }

    /**
     * The browser came back to MeshCentral: its session cookies are tried on the control channel, and only an accepted
     * channel means the person is in. / El navegador volvió a MeshCentral: sus cookies se prueban en el canal de
     * control, y solo un canal aceptado significa que la persona ha entrado.
     */
    fun finishSignOn(cookies: Map<String, String>) {
        val how = screen as? Screen.SignOn ?: return
        signOnLaunched = false
        screen = Screen.Login
        form = form.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val s = MeshSession.connectWithCookies(how.address, cookies, CertificateTrust(how.pinnedSha256), userAgent())
                credentials = null
                signedInWith = how
                signOnCookies = cookies
                pendingPin = how.pinnedSha256
                saved.rememberSingleSignOn(how.address.toString(), s.user.name, how.pinnedSha256, how.method.strategy, null, 0)
                savedServers = saved.all()
                open(s)
                form = form.copy(busy = false, username = s.user.name, password = "")
                screen = Screen.Devices
                // Asked, as the Windows app does: a token lets in without the provider. / Se pregunta: un token entra sin el proveedor.
                rememberPrompt = RememberPrompt(how.method.displayName, REMEMBER_DAYS)
            } catch (e: MeshClientException) {
                form = form.copy(busy = false, error = UiError(e.failure, e.key, e.httpStatus))
            }
        }
    }

    fun answerRemember(yes: Boolean) {
        rememberPrompt = null
        val s = session ?: return
        val how = signedInWith ?: return
        if (!yes) return
        viewModelScope.launch {
            try {
                val name = "Android · ${android.os.Build.MODEL}".take(100)
                // One token per phone: the one from a previous time goes. / Uno por móvil: el de otra vez se quita.
                val old = s.loginTokens().filter { it.name == name }.map { it.tokenUser }
                if (old.isNotEmpty()) s.removeLoginTokens(old)
                val t = s.createLoginToken(name, REMEMBER_DAYS * 24L * 60)
                val pass = t.tokenPass ?: return@launch
                saved.rememberSingleSignOn(how.address.toString(), s.user.name, how.pinnedSha256, how.method.strategy, t.tokenUser to pass,
                    System.currentTimeMillis() + REMEMBER_DAYS * 24L * 3600 * 1000)
                savedServers = saved.all()
                credentials = PasswordCredentials(t.tokenUser, pass)
            } catch (e: MeshClientException) {
                rememberFailed = UiError(e.failure, e.key, e.httpStatus)
            }
        }
    }


    private companion object {
        const val REMEMBER_DAYS = 90
        // Long enough for switching apps for a moment. / Lo bastante para cambiar de app un momento.
        const val BACKGROUND_GRACE_MS = 60_000L
    }

    fun acceptCertificate() {
        val prompt = form.certificate ?: return
        pendingPin = prompt.sha256
        form = form.copy(certificate = null)
        val address = ServerAddress.parse(form.server) ?: return
        acceptedPins[address.toString()] = prompt.sha256
        val c = credentials
        // Accepted while looking for its single sign-on: look again, now trusting it. / Aceptado buscando su inicio único: otra vez.
        if (c == null) { signOnFor = null; connect() } else attempt(address, c)
    }


    fun dismissPrompts() { form = form.copy(certificate = null, twoFactor = null, busy = false) }

    fun submitCode(code: String) {
        val c = credentials ?: return
        form = form.copy(twoFactor = null)
        ServerAddress.parse(form.server)?.let { attempt(it, c.withCode(code.trim())) }
    }

    fun emailCode() {
        val c = credentials ?: return
        ServerAddress.parse(form.server)?.let { attempt(it, c.withCode(PasswordCredentials.EMAIL_CODE)) }
    }

    /** [viaToken]: signing in with the remembered token of that single sign-on. / Entrando con el token recordado de ese inicio único. */
    private fun attempt(address: ServerAddress, creds: PasswordCredentials, viaToken: SingleSignOn? = null) {
        form = form.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val s = MeshSession.connect(address, creds, CertificateTrust(pendingPin), userAgent = userAgent())
                // A second-factor code is single-use: a reconnection asks for a new one. / El código es de un solo uso.
                credentials = creds.withCode(null)
                // A token login keeps the saved account as it is (its name, not the token's user). / Con token, la cuenta guardada se queda igual.
                if (viaToken == null) saved.remember(address.toString(), creds.username, pendingPin, if (form.remember) form.password else null)
                signedInWith = viaToken?.let { Screen.SignOn(it, address, pendingPin) }
                savedServers = saved.all()
                open(s)
                form = form.copy(busy = false, password = if (viaToken == null && form.remember) form.password else "")
                screen = Screen.Devices
            } catch (e: MeshClientException) {
                // The remembered session was removed or ran out: the provider again, and the dead token goes.
                // La sesión recordada se quitó o caducó: otra vez el proveedor, y el token muerto se va.
                if (viaToken != null && e.failure == MeshFailure.InvalidCredentials) {
                    val known = savedServers.firstOrNull { it.address == address.toString() }
                    if (known != null) saved.rememberSingleSignOn(known.address, known.username, known.pinnedSha256, viaToken.strategy, null, 0)
                    savedServers = saved.all()
                    form = form.copy(busy = false)
                    startSignOn(viaToken)
                    return@launch
                }
                form = when (e.failure) {
                    MeshFailure.UntrustedCertificate -> form.copy(busy = false, certificate = e.certificateSha256?.let {
                        CertificatePrompt(address.host, it, changed = pendingPin != null && pendingPin != it)
                    }, error = if (e.certificateSha256 == null) UiError(e.failure, e.key) else null)
                    MeshFailure.TwoFactorRequired -> form.copy(busy = false, twoFactor = TwoFactorPrompt(e.emailCodeAvailable, e.emailCodeSent, wrong = false))
                    MeshFailure.InvalidTwoFactorCode -> form.copy(busy = false, twoFactor = TwoFactorPrompt(e.emailCodeAvailable, false, wrong = true))
                    else -> form.copy(busy = false, error = UiError(e.failure, e.key, e.httpStatus))
                }
            }
        }
    }

    private suspend fun open(s: MeshSession) {
        session?.close()
        watching?.cancel()
        session = s
        lost = false
        groupNames = s.deviceGroups().associate { it.id to it.name }
        devices.clear()
        s.devices().forEach { devices[it.id] = it }
        watching = viewModelScope.launch {
            launch { s.deviceChanges.collect(::apply) }
            launch { s.closedReason.filterNotNull().collect { lost = true } }
        }
    }

    private fun apply(change: DeviceChange) {
        when (change) {
            is DeviceChange.Added -> devices[change.deviceId] = change.device
            is DeviceChange.Changed -> {
                val old = devices[change.deviceId]
                devices[change.deviceId] = old?.withDetails(change.device) ?: change.device
                // A device in a group not known yet: the names are reloaded. / Un grupo aún desconocido: se recargan los nombres.
                if (change.device.groupId !in groupNames) viewModelScope.launch {
                    runCatching { session?.deviceGroups() }.getOrNull()?.let { list -> groupNames = list.associate { it.id to it.name } }
                }
            }
            is DeviceChange.Removed -> devices.remove(change.deviceId)
            is DeviceChange.Connection -> devices[change.deviceId]?.let {
                devices[change.deviceId] = it.copy(connectivity = change.connectivity, powerState = change.powerState)
            }
        }
    }

    fun reconnect() {
        val address = session?.server ?: return signOut()
        val c = credentials
        val cookies = signOnCookies
        // Signed in by the provider, no token and its session gone: through the provider again. / Sin token ni sesión: otra vez el proveedor.
        if (c == null && cookies == null) return signedInWith?.let { how ->
            signOut()
            form = form.copy(server = how.address.toString())
            startSignOn(how.method)
        } ?: signOut()
        lost = false
        viewModelScope.launch {
            try {
                open(if (c != null) MeshSession.connect(address, c, CertificateTrust(pendingPin), userAgent = userAgent())
                     else MeshSession.connectWithCookies(address, cookies!!, CertificateTrust(pendingPin), userAgent()))
            } catch (e: MeshClientException) {
                // The provider's session ran out: next time, through the provider. / La sesión del proveedor caducó.
                if (c == null && e.failure == MeshFailure.SessionExpired) signOnCookies = null
                lost = true
            }
        }
    }

    /** The app left the screen: after [BACKGROUND_GRACE_MS], the live connection closes (no pings, no events).
     *  La app dejó la pantalla: pasado ese margen se cierra la conexión en vivo (sin pings ni avisos). */
    fun onBackground() {
        if (session == null) return
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch {
            delay(BACKGROUND_GRACE_MS)
            paused = true
            watching?.cancel()
            session?.close()
        }
    }

    /** Back on screen: a closed connection opens again, quietly. / De vuelta: la conexión cerrada se abre otra vez, sin avisar. */
    fun onForeground() {
        backgroundJob?.cancel()
        if (!paused) return
        paused = false
        if (session != null) reconnect()
    }

    fun show(target: Screen) { screen = target }

    fun signOut() {
        watching?.cancel()
        session?.close()
        session = null
        credentials = null
        signedInWith = null
        signOnCookies = null
        rememberPrompt = null
        backgroundJob?.cancel()
        paused = false
        devices.clear()
        lost = false
        savedServers = saved.all()
        savedServers.firstOrNull()?.let(::pick)
        screen = Screen.Login
    }

    override fun onCleared() {
        session?.close()
    }
}
