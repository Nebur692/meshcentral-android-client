package io.github.nebur692.meshclient

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nebur692.meshclient.core.CertificateTrust
import io.github.nebur692.meshclient.core.CommandShell
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.DeviceAbilities
import io.github.nebur692.meshclient.core.DeviceChange
import io.github.nebur692.meshclient.core.DeviceDetails
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.canMove
import io.github.nebur692.meshclient.core.rightsOn
import io.github.nebur692.meshclient.core.moveDevice
import io.github.nebur692.meshclient.core.TunnelProtocol
import io.github.nebur692.meshclient.core.PasswordCredentials
import io.github.nebur692.meshclient.core.PowerAction
import io.github.nebur692.meshclient.core.ServerAddress
import io.github.nebur692.meshclient.core.SignInMethods
import io.github.nebur692.meshclient.core.SingleSignOn
import io.github.nebur692.meshclient.core.abilitiesOn
import io.github.nebur692.meshclient.core.changeDevice
import io.github.nebur692.meshclient.core.details
import io.github.nebur692.meshclient.core.messageBox
import io.github.nebur692.meshclient.core.notes
import io.github.nebur692.meshclient.core.notify
import io.github.nebur692.meshclient.core.power
import io.github.nebur692.meshclient.core.removeDevice
import io.github.nebur692.meshclient.core.runCommands
import io.github.nebur692.meshclient.core.setNotes
import io.github.nebur692.meshclient.core.uninstallAgent
import io.github.nebur692.meshclient.core.updateAgents
import io.github.nebur692.meshclient.core.wake
import io.github.nebur692.meshclient.data.BiometricVault
import io.github.nebur692.meshclient.data.SavedServer
import io.github.nebur692.meshclient.data.SavedServers
import javax.crypto.Cipher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Login : Screen
    data object Devices : Screen
    data object Account : Screen
    /** The own account's password, second factor and login tokens. / Contraseña, doble factor y tokens de la cuenta propia. */
    data object Security : Screen
    /** The server's live state. / El estado en vivo del servidor. */
    data object ServerState : Screen
    /** The administration: a list, or one of its items. / La administración: una lista, o uno de sus elementos. */
    data class Admin(val page: AdminPage, val id: String? = null) : Screen
    /** The port mappings open now. / Las redirecciones de puertos abiertas. */
    data object Ports : Screen
    /** A device's processes or services. / Los procesos o servicios de un equipo. */
    data class Tools(val id: String) : Screen
    /** The chat with the person at the device. / El chat con la persona del equipo. */
    data class Chat(val id: String) : Screen
    /** The server's recorded sessions. / Las sesiones grabadas del servidor. */
    data object Recordings : Screen
    data class DeviceDetail(val id: String) : Screen
    data class Terminal(val id: String) : Screen
    data class Files(val id: String) : Screen
    data class Desktop(val id: String) : Screen
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
    /** The whole form (address, user, password) instead of the saved accounts. / El formulario entero en vez de las cuentas guardadas. */
    val expanded: Boolean = false,
)

/** "Sign in with your fingerprint next time?", after signing in. [session]: a single sign-on account (a token is kept).
 *  «¿Entrar con huella la próxima vez?», tras entrar. [session]: una cuenta de inicio único (se guarda un token). */
data class BiometricOffer(val address: String, val session: Boolean, val days: Int)

/** What the fingerprint prompt is for: to seal [secret] for [address], or to open [server]'s.
 *  Para qué es el aviso de la huella: sellar [secret] de [address], o abrir el de [server]. */
sealed interface BiometricRequest {
    data class Seal(val address: String, val secret: String, val tokenExpires: Long = 0) : BiometricRequest
    data class Open(val server: SavedServer) : BiometricRequest
}

/** Why the fingerprint did not work, shown once. / Por qué no funcionó la huella, se enseña una vez. */
enum class BiometricProblem { KeyChanged, Unavailable, Locked, Stale }

/** A panel of the device's sheet. / Un panel de la ficha del equipo. */
enum class DeviceSheet { Terminal, Power, More, Pin, Move, Ssh, Port, Message, Run, Edit, ConfirmRestart, ConfirmPowerOff, ConfirmUninstall, ConfirmRemove, ConfirmUpdateAgent }

/** What the agents said after being asked to update: device name and its words (null = nothing). / Lo que dijeron los agentes. */
data class AgentAnswers(val said: List<Pair<String, String?>>)

/** A short word after an action, or its failure. / Una palabra corta tras una acción, o su fallo. */
data class Notice(val text: Int, val error: UiError? = null)

enum class DeviceFilter { All, Online, Windows, Linux, Mac }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val saved = SavedServers(app)
    private val vault = BiometricVault()

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

    /** A strong biometric is set up on the phone (asked again each time the app comes back). / El móvil tiene biometría fuerte. */
    var biometricsAvailable by mutableStateOf(false)
        private set
    var biometricOffer by mutableStateOf<BiometricOffer?>(null)
        private set
    var biometricRequest by mutableStateOf<BiometricRequest?>(null)
        private set
    var biometricProblem by mutableStateOf<BiometricProblem?>(null)

    // The device's sheet. / La ficha del equipo.
    var details by mutableStateOf<DeviceDetails?>(null)
        private set
    var detailsLoading by mutableStateOf(false)
        private set
    var sheet by mutableStateOf<DeviceSheet?>(null)
    var notice by mutableStateOf<Notice?>(null)
    var actionBusy by mutableStateOf(false)
        private set
    var commandOutput by mutableStateOf<String?>(null)
        private set

    // Kept only while signed in, to reconnect or answer the second factor. / Solo mientras dura la sesión.
    private var credentials: PasswordCredentials? = null
    private var pendingPin: String? = null
    private var watching: Job? = null
    private var discovering: Job? = null
    private var detailsJob: Job? = null
    private var detailsFor: String? = null
    private var signOnFor: String? = null
    private var signedInWith: Screen.SignOn? = null
    private var unlockedWithBiometric = false
    private var autoUnlockTried = false

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
    private var visible = true
    private var visibleSince = 0L

    /** Why a shortcut could not open its device, said once. / Por qué un acceso directo no pudo abrir su equipo. */
    var linkProblem by mutableStateOf<Int?>(null)

    /** A shortcut's device, opened once its server is signed in. / El equipo de un acceso, que se abre al entrar en su servidor. */
    private var pendingLink: DeviceLink? = null

    // Every property above this line: init runs in order, and pick() already uses them (a crash on the POCO with a
    // saved server). / Todas las propiedades por encima: init corre en orden y pick() ya las usa (se cerraba en el POCO).
    init {
        biometricsAvailable = BiometricVault.available(app)
        form = LoginForm(expanded = savedServers.isEmpty())
        savedServers.firstOrNull()?.let(::pick)
    }

    private fun userAgent() = "MeshCentralAndroidClient/${BuildConfig.VERSION_NAME}"

    private fun savedFor(address: String) = savedServers.firstOrNull { it.address == address }

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
            // On the local network without the permission, the look-up would only time out: Connect asks first.
            // En la red local sin el permiso, la consulta solo agotaría el tiempo: Conectar pregunta antes.
            if (!localNetworkAllowed(address, ask = false)) return@launch
            val pin = savedFor(address.toString())?.pinnedSha256 ?: acceptedPins[address.toString()]
            val trust = CertificateTrust(pin)
            val found = SignInMethods.discover(address, trust, userAgent())
            signOnMethods = found
            // Empty because of a certificate not accepted yet says nothing: Connect looks again and asks.
            // Vacío por un certificado aún sin aceptar no dice nada: Conectar vuelve a mirar y pregunta.
            signOnFor = if (found.isEmpty() && trust.rejectedSha256 != null) null else address.toString()
        }
    }

    fun pick(server: SavedServer) {
        // Another server picked by hand: a shortcut's device no longer waits. / Otro servidor elegido a mano: el del acceso ya no espera.
        if (pendingLink?.server != server.address) pendingLink = null
        // With the fingerprint set, the password never reaches the form. / Con la huella, la contraseña nunca llega al formulario.
        form = form.copy(server = server.address, username = server.username,
            password = if (server.hasBiometric) "" else saved.password(server).orEmpty(),
            remember = server.hasPassword, error = null)
        discoverSignOn(now = true)
    }

    /** Back on the screen: the fingerprint may have been set up or removed meanwhile. / Puede que se configurara o quitara la huella. */
    private fun refreshBiometrics() {
        biometricsAvailable = BiometricVault.available(getApplication())
        // The first time the app opens with a fingerprint account, the prompt comes by itself.
        // La primera vez que se abre con una cuenta con huella, el aviso sale solo.
        if (!autoUnlockTried && screen == Screen.Login && session == null && !form.expanded) {
            autoUnlockTried = true
            savedFor(form.server)?.takeIf { it.hasBiometric && biometricsAvailable }?.let(::unlock)
        }
    }

    /** The whole form, for another account or for the same one with its password. / El formulario entero. */
    fun showForm(server: SavedServer?) {
        if (server == null) form = LoginForm(expanded = true) else { pick(server); form = form.copy(expanded = true, password = "") }
        signOnFor = null
        discoverSignOn(now = true)
    }

    /** Back from the form to the saved accounts. / Del formulario a las cuentas guardadas. */
    fun showAccounts() {
        if (savedServers.isEmpty()) return
        val keep = savedFor(form.server) ?: savedServers.first()
        form = LoginForm()
        pick(keep)
    }

    fun forget(server: SavedServer) {
        saved.forget(server.address)
        Shortcuts.forgetServer(getApplication(), server.address, getApplication<Application>().getString(R.string.shortcut_server_gone))
        savedServers = saved.all()
        // Forgetting the server on the form also takes its password off the screen. / Olvidar el servidor del formulario también quita su contraseña de la pantalla.
        if (form.server == server.address) {
            form = LoginForm(expanded = savedServers.isEmpty())
            savedServers.firstOrNull()?.let(::pick)
        }
    }

    /** The saved account: by its fingerprint, its remembered password or token, or its provider. / La cuenta guardada. */
    fun signInSaved(server: SavedServer) {
        pick(server)
        if (server.hasBiometric && biometricsAvailable) unlock(server)
        else if (server.hasPassword || server.singleSignOn != null) connect()
        else showForm(server)
    }

    fun unlock(server: SavedServer) {
        if (biometricRequest == null) biometricRequest = BiometricRequest.Open(server)
    }

    /** The cipher the prompt needs, or null with the problem shown. / El cifrado que necesita el aviso, o null con el problema. */
    fun cipherFor(request: BiometricRequest): Cipher? {
        val prepared = when (request) {
            is BiometricRequest.Seal -> vault.forSealing()
            is BiometricRequest.Open -> request.server.sealedBiometric?.let(vault::forOpening)
        }
        if (prepared?.cipher != null) return prepared.cipher
        biometricRequest = null
        if (prepared?.problem == BiometricVault.Problem.KeyChanged) {
            saved.clearAllBiometric()
            savedServers = saved.all()
            biometricProblem = BiometricProblem.KeyChanged
        } else biometricProblem = BiometricProblem.Unavailable
        return null
    }

    /** The prompt for [request] unlocked [cipher]; an answer from a prompt no longer open (a rotation) is ignored.
     *  El aviso de [request] desbloqueó [cipher]; la respuesta de un aviso que ya no está abierto (un giro) no cuenta. */
    fun biometricDone(request: BiometricRequest, cipher: Cipher) {
        if (request !== biometricRequest) return
        biometricRequest = null
        when (val r = request) {
            is BiometricRequest.Seal -> {
                val sealed = vault.seal(cipher, r.secret) ?: run { biometricProblem = BiometricProblem.Unavailable; return }
                saved.setBiometric(r.address, sealed, r.tokenExpires)
                savedServers = saved.all()
                notice = Notice(R.string.biometric_on)
            }
            is BiometricRequest.Open -> {
                val secret = r.server.sealedBiometric?.let { vault.open(cipher, it) } ?: run { biometricProblem = BiometricProblem.Unavailable; return }
                signInWithSecret(r.server, secret)
            }
        }
    }

    /** Cancelled, or Android refused (too many tries…). / Cancelado, o Android no dejó (demasiados intentos…). */
    fun biometricFailed(request: BiometricRequest, lockedOut: Boolean) {
        if (request !== biometricRequest) return
        biometricRequest = null
        if (lockedOut) biometricProblem = BiometricProblem.Locked
    }

    private fun signInWithSecret(server: SavedServer, secret: String) {
        val address = ServerAddress.parse(server.address) ?: return
        val parts = secret.split("\n")
        pendingPin = server.pinnedSha256 ?: acceptedPins[server.address]
        unlockedWithBiometric = true
        when {
            parts.size == 2 && parts[0] == "p" -> {
                credentials = PasswordCredentials(server.username, parts[1])
                attempt(address, credentials!!, viaBiometric = true)
            }
            parts.size == 3 && parts[0] == "t" && server.singleSignOn != null -> {
                val method = SingleSignOn(server.singleSignOn)
                if (server.tokenExpires in 1..System.currentTimeMillis()) {
                    // The 90 days are over: the provider again, and the fingerprint is offered again after.
                    // Pasaron los 90 días: otra vez el proveedor, y la huella se vuelve a ofrecer después.
                    saved.clearBiometric(server.address); savedServers = saved.all()
                    startSignOn(method)
                } else {
                    credentials = PasswordCredentials(parts[1], parts[2])
                    attempt(address, credentials!!, viaToken = method, viaBiometric = true)
                }
            }
            else -> { saved.clearBiometric(server.address); savedServers = saved.all(); biometricProblem = BiometricProblem.Unavailable }
        }
    }

    fun answerBiometricOffer(yes: Boolean) {
        val offer = biometricOffer ?: return
        biometricOffer = null
        if (!yes) { saved.declineBiometric(offer.address); savedServers = saved.all(); return }
        if (offer.session) rememberSession(underBiometric = true)
        else credentials?.let { biometricRequest = BiometricRequest.Seal(offer.address, "p\n" + it.passwordForSealing()) }
    }

    /** The switch in the account: on asks for the finger, off forgets it. / El interruptor de la cuenta. */
    fun setBiometric(on: Boolean) {
        val s = session ?: return
        val address = s.server.toString()
        if (!on) { saved.clearBiometric(address); savedServers = saved.all(); return }
        if (signedInWith != null) rememberSession(underBiometric = true)
        else credentials?.let { biometricRequest = BiometricRequest.Seal(address, "p\n" + it.passwordForSealing()) }
    }

    /** The account on screen can be put under the fingerprint now. / La cuenta en pantalla se puede poner ya bajo la huella. */
    val canSetBiometric: Boolean get() = biometricsAvailable && session != null && (signedInWith != null || credentials != null)

    fun connect() {
        val address = ServerAddress.parse(form.server)
        if (address == null) {
            form = form.copy(error = UiError(MeshFailure.InvalidAddress, "conexion/iniciar-sesion/dato-invalido"))
            return
        }
        val known = savedFor(address.toString())
        pendingPin = known?.pinnedSha256 ?: acceptedPins[address.toString()]
        unlockedWithBiometric = false
        // A saved account with nothing typed: its fingerprint first. / Una cuenta guardada sin nada escrito: primero su huella.
        if (known != null && known.hasBiometric && biometricsAvailable && form.password.isEmpty() && form.username == known.username) {
            unlock(known)
            return
        }
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
                else form = form.copy(expanded = true, error = UiError(MeshFailure.InvalidCredentials, "conexion/iniciar-sesion/falta-dato", missingFields = true))
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
        screen = Screen.SignOn(method, address, savedFor(address.toString())?.pinnedSha256 ?: pendingPin)
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
            if (!localNetworkAllowed(how.address)) { form = form.copy(busy = false, error = localNetworkError()); return@launch }
            try {
                val s = MeshSession.connectWithCookies(how.address, cookies, CertificateTrust(how.pinnedSha256), userAgent())
                credentials = null
                signedInWith = how
                signOnCookies = cookies
                pendingPin = how.pinnedSha256
                saved.rememberSingleSignOn(how.address.toString(), s.user.name, how.pinnedSha256, how.method.strategy, null, 0)
                savedServers = saved.all()
                open(s)
                form = form.copy(busy = false, username = s.user.name, password = "", expanded = false)
                screen = Screen.Devices
                followPending(how.address.toString())
                // With a fingerprint, the fingerprint is offered (it keeps the token); without, the token alone, as the Windows app.
                // Con huella se ofrece la huella (guarda el token); sin ella, solo el token, como la app de Windows.
                val known = savedFor(how.address.toString())
                if (biometricsAvailable) {
                    if (known?.biometricDeclined != true) biometricOffer = BiometricOffer(how.address.toString(), session = true, days = REMEMBER_DAYS)
                } else rememberPrompt = RememberPrompt(how.method.displayName, REMEMBER_DAYS)
            } catch (e: MeshClientException) {
                form = form.copy(busy = false, error = UiError(e.failure, e.key, e.httpStatus))
            }
        }
    }

    fun answerRemember(yes: Boolean) {
        rememberPrompt = null
        if (yes) rememberSession(underBiometric = false)
    }

    /** A login token for this phone, kept plainly or under the fingerprint. / Un token para este móvil, normal o bajo la huella. */
    private fun rememberSession(underBiometric: Boolean) {
        val s = session ?: return
        val how = signedInWith ?: return
        viewModelScope.launch {
            try {
                val name = "Android · ${android.os.Build.MODEL}".take(100)
                // One token per phone: the one from a previous time goes. / Uno por móvil: el de otra vez se quita.
                val old = s.loginTokens().filter { it.name == name }.map { it.tokenUser }
                if (old.isNotEmpty()) s.removeLoginTokens(old)
                val t = s.createLoginToken(name, REMEMBER_DAYS * 24L * 60)
                val pass = t.tokenPass ?: return@launch
                val expires = System.currentTimeMillis() + REMEMBER_DAYS * 24L * 3600 * 1000
                credentials = PasswordCredentials(t.tokenUser, pass)
                if (underBiometric) {
                    biometricRequest = BiometricRequest.Seal(how.address.toString(), "t\n${t.tokenUser}\n$pass", expires)
                } else {
                    saved.rememberSingleSignOn(how.address.toString(), s.user.name, how.pinnedSha256, how.method.strategy, t.tokenUser to pass, expires)
                    savedServers = saved.all()
                }
            } catch (e: MeshClientException) {
                rememberFailed = UiError(e.failure, e.key, e.httpStatus)
            }
        }
    }

    private companion object {
        const val REMEMBER_DAYS = 90
        // Long enough for switching apps for a moment. / Lo bastante para cambiar de app un momento.
        const val BACKGROUND_GRACE_MS = 60_000L
        /** A drop this soon after coming back is the frozen app's. / Un corte tan pronto al volver es de la app congelada. */
        const val JUST_BACK_MS = 10_000L
    }

    fun acceptCertificate() {
        val prompt = form.certificate ?: return
        pendingPin = prompt.sha256
        form = form.copy(certificate = null)
        val address = ServerAddress.parse(form.server) ?: return
        acceptedPins[address.toString()] = prompt.sha256
        val c = credentials
        // Accepted while looking for its single sign-on: look again, now trusting it. / Aceptado buscando su inicio único: otra vez.
        if (c == null) { signOnFor = null; connect() } else attempt(address, c, viaBiometric = unlockedWithBiometric)
    }

    fun dismissPrompts() { form = form.copy(certificate = null, twoFactor = null, busy = false) }

    fun submitCode(code: String) {
        val c = credentials ?: return
        form = form.copy(twoFactor = null)
        ServerAddress.parse(form.server)?.let { attempt(it, c.withCode(code.trim()), viaBiometric = unlockedWithBiometric) }
    }

    fun emailCode() {
        val c = credentials ?: return
        ServerAddress.parse(form.server)?.let { attempt(it, c.withCode(PasswordCredentials.EMAIL_CODE), viaBiometric = unlockedWithBiometric) }
    }

    /** [viaToken]: with the remembered token of that single sign-on; [viaBiometric]: with what the fingerprint opened.
     *  [viaToken]: con el token recordado de ese inicio único; [viaBiometric]: con lo que abrió la huella. */
    private fun attempt(address: ServerAddress, creds: PasswordCredentials, viaToken: SingleSignOn? = null, viaBiometric: Boolean = false) {
        form = form.copy(busy = true, error = null)
        viewModelScope.launch {
            if (!localNetworkAllowed(address)) { form = form.copy(busy = false, error = localNetworkError()); return@launch }
            if (signOnFor != address.toString()) discoverSignOn(now = true)
            try {
                val s = MeshSession.connect(address, creds, CertificateTrust(pendingPin), userAgent = userAgent())
                // A second-factor code is single-use: a reconnection asks for a new one. / El código es de un solo uso.
                credentials = creds.withCode(null)
                // A token login keeps the saved account as it is (its name, not the token's user). / Con token, la cuenta guardada se queda igual.
                val typed = viaToken == null && !viaBiometric
                if (viaToken == null) saved.remember(address.toString(), creds.username, pendingPin, if (typed && form.remember) form.password else null)
                signedInWith = viaToken?.let { Screen.SignOn(it, address, pendingPin) }
                savedServers = saved.all()
                open(s)
                form = form.copy(busy = false, password = if (typed && form.remember) form.password else "", expanded = false)
                screen = Screen.Devices
                followPending(address.toString())
                // Typed in by hand, with a fingerprint on the phone and none here yet: offered once.
                // Escrita a mano, con huella en el móvil y aquí aún sin ella: se ofrece una vez.
                val known = savedFor(address.toString())
                if (typed && biometricsAvailable && known != null && !known.hasBiometric && !known.biometricDeclined)
                    biometricOffer = BiometricOffer(address.toString(), session = false, days = 0)
            } catch (e: MeshClientException) {
                // The remembered session was removed or ran out: the provider again, and the dead token goes.
                // La sesión recordada se quitó o caducó: otra vez el proveedor, y el token muerto se va.
                if (viaToken != null && e.failure == MeshFailure.InvalidCredentials) {
                    val known = savedFor(address.toString())
                    if (known != null) saved.rememberSingleSignOn(known.address, known.username, known.pinnedSha256, viaToken.strategy, null, 0)
                    if (viaBiometric) saved.clearBiometric(address.toString())
                    savedServers = saved.all()
                    form = form.copy(busy = false)
                    startSignOn(viaToken)
                    return@launch
                }
                // The password changed since it was put under the fingerprint: it goes, and the person types the new one.
                // La contraseña cambió desde que se puso bajo la huella: se quita, y la persona escribe la nueva.
                if (viaBiometric && e.failure == MeshFailure.InvalidCredentials) {
                    saved.clearBiometric(address.toString())
                    savedServers = saved.all()
                    biometricProblem = BiometricProblem.Stale
                    form = form.copy(busy = false, expanded = true, password = "")
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
            launch { s.closedReason.filterNotNull().collect { dropped() } }
            launch { s.notifications.collect(::arrived) }
        }
    }

    /**
     * The connection dropped. Android freezes a hidden app within seconds: its pings go unanswered and, back on screen,
     * the connection gives up on the one it missed. That is not a loss to show: out of sight, or just back, it reconnects
     * quietly; only a drop while the person is using the app says so.
     * Se cortó la conexión. Android congela una app oculta en segundos: sus pings se quedan sin contestar y, al volver,
     * la conexión da por perdido el que faltó. Eso no es una pérdida que enseñar: fuera de la vista, o recién vuelta,
     * se reconecta sin avisar; solo un corte mientras se usa la app lo dice.
     */
    private fun dropped() {
        when {
            !visible -> paused = true
            android.os.SystemClock.elapsedRealtime() - visibleSince < JUST_BACK_MS -> reconnect()
            else -> lost = true
        }
    }

    /** The last notification from the server, shown in a window until closed. / El último aviso del servidor, en una ventana. */
    var serverNotice by mutableStateOf<io.github.nebur692.meshclient.core.ServerNotification?>(null)

    private fun arrived(n: io.github.nebur692.meshclient.core.ServerNotification) {
        serverNotice = n
        // Out of sight, Android's own notification too; in sight, the permission is asked once. / Fuera, también la de Android.
        if (!visible) ServerNotices.post(getApplication(), session?.serverInfo?.name?.ifBlank { null } ?: session?.server?.host.orEmpty(), n)
        else if (!notificationsAsked) { notificationsAsked = true; askNotifications = true }
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

    // ------------------------------------------------------------------ the device / el equipo

    fun openDevice(id: String) {
        screen = Screen.DeviceDetail(id)
        sheet = null
        commandOutput = null
        loadDetails(id)
        val s = session ?: return
        val d = devices[id] ?: return
        val app = getApplication<Application>()
        // First among the launcher's recent devices; drawing its icon is kept off the screen's thread. / Fuera del hilo de la pantalla.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            Shortcuts.recent(app, s.server.toString(), s.serverInfo.name.ifBlank { s.server.host }, d)
        }
    }

    // ------------------------------------------------------------------ shortcuts / accesos directos

    /**
     * A shortcut was tapped. Signed in to its server, its device opens now; otherwise the saved account signs in as it
     * always does (fingerprint, remembered password, provider or the form) and the device opens after.
     * Se tocó un acceso directo. Con su servidor ya abierto, el equipo se abre ahora; si no, la cuenta guardada entra como
     * siempre (huella, contraseña recordada, proveedor o el formulario) y el equipo se abre después.
     */
    fun openLink(link: DeviceLink) {
        val s = session
        if (s != null && s.server.toString() == link.server) {
            // Closed while out of sight, the connection is opened again as the app comes back, before or after Android hands
            // the shortcut over: the device waits for the new one (a tool opened on the closed one dropped at once).
            // Cerrada fuera de la vista, la conexión se vuelve a abrir al volver la app, antes o después de que Android entregue
            // el acceso: el equipo espera a la nueva (una herramienta abierta sobre la cerrada se cortaba al momento).
            if (paused || !s.isOpen) pendingLink = link else follow(link)
            return
        }
        val server = savedFor(link.server) ?: run { linkProblem = R.string.link_server_gone; return }
        if (s != null) signOut()
        // The first saved account's fingerprint prompt must not come instead. / Que no salga en su lugar la huella de la primera cuenta.
        autoUnlockTried = true
        form = LoginForm()
        pick(server)
        pendingLink = link
        signInSaved(server)
    }

    private fun followPending(address: String) {
        val link = pendingLink ?: return
        pendingLink = null
        if (link.server == address) follow(link)
    }

    /** What the link asks, if this account may; else the device's sheet, which says why. / Lo que pide, si se puede; si no, la ficha. */
    private fun follow(link: DeviceLink) {
        val d = devices[link.deviceId]
        if (d == null) { closeTools(); show(Screen.Devices); linkProblem = R.string.link_device_gone; return }
        val rights = session?.rightsOn(d) ?: 0L
        fun may(no: Long) = io.github.nebur692.meshclient.core.MeshRights.tool(rights, no)
        val target = when (link.tool) {
            LinkTool.Desktop -> if (d.canOpenDesktop && may(io.github.nebur692.meshclient.core.MeshRights.NO_DESKTOP)) Screen.Desktop(d.id) else null
            LinkTool.Terminal, LinkTool.PowerShell -> if (d.canOpenTerminal && may(io.github.nebur692.meshclient.core.MeshRights.NO_TERMINAL)) Screen.Terminal(d.id) else null
            LinkTool.Files -> if (d.canOpenFiles && may(io.github.nebur692.meshclient.core.MeshRights.NO_FILES)) Screen.Files(d.id) else null
            LinkTool.Device -> null
        }
        // Already on it (back from the home screen): left as it is. / Ya está en ello: se deja como está.
        if (screen == (target ?: Screen.DeviceDetail(d.id))) return
        closeTools()
        when (target) {
            is Screen.Desktop -> openDesktop(d.id)
            is Screen.Terminal -> openTerminal(d.id, if (link.tool == LinkTool.PowerShell && d.isWindows) TunnelProtocol.PowerShell else TunnelProtocol.Terminal)
            is Screen.Files -> openFiles(d.id)
            else -> openDevice(d.id)
        }
    }

    /** Asks the launcher to put [tool] of [d] on the home screen. / Pide al lanzador poner [tool] de [d] en la pantalla de inicio. */
    fun pinShortcut(d: Device, tool: LinkTool, toolName: String) {
        val s = session ?: return
        sheet = null
        if (!Shortcuts.pin(getApplication(), DeviceLink(s.server.toString(), d.id, tool), d, toolName)) notice = Notice(R.string.shortcut_not_added)
    }

    /** Inventory, network, last contact, notes and history, all at once; each part may come empty. / Todo a la vez. */
    fun loadDetails(id: String) {
        val s = session ?: return
        val d = devices[id] ?: return
        if (detailsFor != id) details = null
        detailsFor = id
        detailsJob?.cancel()
        detailsLoading = true
        detailsJob = viewModelScope.launch {
            try {
                details = s.details(d)
            } catch (_: MeshClientException) {
                // Only a lost connection gets here, and the banner already says it. / Solo llega aquí una conexión perdida.
            } finally {
                detailsLoading = false
            }
        }
    }

    fun abilities(d: Device): DeviceAbilities = session?.abilitiesOn(d) ?: DeviceAbilities.NONE

    /** SSH and port mapping go through the agent with remote control rights. / SSH y puertos van por el agente con control remoto. */
    fun canRelay(d: Device): Boolean = d.hasAgent && session?.rightsOn(d)?.let { io.github.nebur692.meshclient.core.MeshRights.has(it, io.github.nebur692.meshclient.core.MeshRights.REMOTE_CONTROL) } == true

    /** Runs one action, says it went well or why not. / Hace una acción y dice si fue bien o por qué no. */
    private fun act(ok: Int, block: suspend (MeshSession) -> Unit) {
        val s = session ?: return
        if (actionBusy) return
        actionBusy = true
        viewModelScope.launch {
            try {
                block(s)
                sheet = null
                notice = Notice(ok)
            } catch (e: MeshClientException) {
                notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            } finally {
                actionBusy = false
            }
        }
    }

    fun wake(d: Device) = act(R.string.done_wake) { it.wake(d) }

    fun power(d: Device, action: PowerAction) = act(when (action) {
        PowerAction.Sleep -> R.string.done_sleep
        PowerAction.Restart -> R.string.done_restart
        PowerAction.PowerOff -> R.string.done_power_off
    }) { it.power(d, action) }

    /** A notification by default; a window that waits to be closed when [window]. / Un aviso; una ventana si [window]. */
    fun sendMessage(d: Device, title: String, text: String, window: Boolean) =
        act(R.string.done_message) { if (window) it.messageBox(d, title, text) else it.notify(d, title, text) }

    fun runCommands(d: Device, commands: String, shell: CommandShell) {
        val s = session ?: return
        if (actionBusy) return
        commandOutput = null
        actionBusy = true
        viewModelScope.launch {
            try {
                commandOutput = s.runCommands(d, commands, shell).ifEmpty { "—" }
            } catch (e: MeshClientException) {
                notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            } finally {
                actionBusy = false
            }
        }
    }

    fun clearCommandOutput() { commandOutput = null }

    fun saveDevice(d: Device, name: String, description: String, tags: List<String>) = act(R.string.done_saved) {
        it.changeDevice(d, name = name.trim().takeIf { n -> n.isNotEmpty() && n != d.name },
            description = description.trim().takeIf { t -> t != d.description.orEmpty() },
            tags = tags.takeIf { t -> t != d.tags })
    }

    /** setNotes never answers: read back to say it was saved. / setNotes no contesta: se relee para decir que se guardó. */
    fun saveNotes(d: Device, text: String) = act(R.string.done_saved) { s ->
        s.setNotes(d, text)
        val want = text.ifEmpty { null }
        var back: String? = null
        for (i in 0 until 12) {
            back = try { s.notes(d.id, 3_000) } catch (e: MeshClientException) { if (e.failure == MeshFailure.Timeout) null else throw e }
            if (back == want) break
            delay(250)
        }
        if (back != want) throw MeshClientException(MeshFailure.Rejected, "ficha/cambiar-ajustes/rechazo-remoto", "The notes did not stay.")
        details = details?.copy(notes = back)
    }

    fun uninstall(d: Device) = act(R.string.done_uninstall) { it.uninstallAgent(d) }

    /** What the agents answered to the last update, shown until read. / Lo que contestaron los agentes, hasta leerlo. */
    var agentAnswers by mutableStateOf<AgentAnswers?>(null)

    /** Asks the agents to update and shows what each one said. / Pide actualizar y enseña lo que dijo cada uno. */
    fun updateAgents(list: List<Device>, then: () -> Unit = {}) = act(R.string.done_update_agent) { s ->
        val said = s.updateAgents(list)
        agentAnswers = AgentAnswers(list.map { it.name to said[it] })
        then()
    }

    fun remove(d: Device) = act(R.string.done_removed) {
        it.removeDevice(d)
        devices.remove(d.id)
        Shortcuts.forgetDevice(getApplication(), it.server.toString(), d.id, getApplication<Application>().getString(R.string.shortcut_device_gone))
        screen = Screen.Devices
    }

    // ------------------------------------------------------------------ local network / red local

    /** The explanation before Android's own question, while it is on screen. / La explicación antes de la pregunta de Android. */
    var localNetworkPrompt by mutableStateOf(false)
        private set
    /** The activity asks Android now. / La actividad pregunta ya a Android. */
    var askLocalNetwork by mutableStateOf(false)
    private var localNetworkAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var localNetworkRefused = false

    /** The person's answer to the explanation: go on to Android's question, or not. / La respuesta a la explicación. */
    fun answerLocalNetworkPrompt(goOn: Boolean) {
        localNetworkPrompt = false
        if (goOn) askLocalNetwork = true else { localNetworkRefused = true; localNetworkAnswer?.complete(false) }
    }

    /** Android's answer. / La respuesta de Android. */
    fun localNetworkGranted(granted: Boolean) {
        askLocalNetwork = false
        if (!granted) localNetworkRefused = true
        localNetworkAnswer?.complete(granted)
    }

    /**
     * True when the server can be reached: Android before 17, the permission given, or the server not on the phone's
     * local network now. Otherwise the person is asked (once per run; [ask] false only looks).
     * True si se puede llegar al servidor: Android anterior al 17, el permiso dado, o el servidor fuera de la red local del
     * móvil ahora. Si no, se pregunta a la persona (una vez por ejecución; [ask] false solo mira).
     */
    private suspend fun localNetworkAllowed(address: ServerAddress, ask: Boolean = true): Boolean {
        val app = getApplication<Application>()
        if (!LocalNetwork.missing(app) || !LocalNetwork.isLocal(app, address.host)) return true
        if (!ask || localNetworkRefused) return false
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        localNetworkAnswer = answer
        localNetworkPrompt = true
        return answer.await()
    }

    private fun localNetworkError() = UiError(MeshFailure.NotAllowed, LocalNetwork.KEY)

    // ------------------------------------------------------------------ connection / conexión

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
            // Moved onto the server's own network (home Wi-Fi) without the permission: said, not timed out.
            // Pasó a la red del propio servidor sin el permiso: se dice, no se agota el tiempo.
            if (!localNetworkAllowed(address, ask = visible)) { lost = true; return@launch }
            try {
                open(if (c != null) MeshSession.connect(address, c, CertificateTrust(pendingPin), userAgent = userAgent())
                     else MeshSession.connectWithCookies(address, cookies!!, CertificateTrust(pendingPin), userAgent()))
                (screen as? Screen.DeviceDetail)?.let { loadDetails(it.id) }
                followPending(address.toString())
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
        visible = false
        desktop?.onScreen(false)
        // A terminal, the files or a desktop open stay connected (with the notification) until the person closes them.
        // Una terminal, los ficheros o un escritorio abiertos siguen conectados (con el aviso) hasta que se cierran.
        if (session == null || toolOpen) return
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
        visible = true
        visibleSince = android.os.SystemClock.elapsedRealtime()
        desktop?.onScreen(true)
        backgroundJob?.cancel()
        refreshBiometrics()
        if (!paused) return
        paused = false
        if (session != null) reconnect()
    }

    fun show(target: Screen) {
        if (target is Screen.Admin && admin == null) admin = AdminModel({ session }, viewModelScope)
        if (target is Screen.Admin && target.page == AdminPage.Groups && target.id == null) admin?.inviteLink = null
        screen = target
        if (target !is Screen.DeviceDetail) sheet = null
    }

    fun openAdminModel() { if (admin == null) admin = AdminModel({ session }, viewModelScope) }

    /** Devices picked in the list for actions on several. / Equipos elegidos en la lista para acciones en varios. */
    val bulk by lazy { BulkModel({ session }, viewModelScope) }

    /** The own account, made each time its screen opens (it reads the server then). / La cuenta propia, hecha al abrir su pantalla. */
    var ownAccount by mutableStateOf<OwnAccountModel?>(null)
        private set
    val serverState by lazy { ServerStateModel({ session }, viewModelScope) }

    fun openSecurity() {
        ownAccount = OwnAccountModel({ session }, viewModelScope).also { it.load() }
        show(Screen.Security)
    }

    /** The administration, made the first time it opens. / La administración, creada la primera vez que se abre. */
    var admin by mutableStateOf<AdminModel?>(null)
        private set

    /** The groups this device can move into. / Los grupos a los que se puede mover este equipo. */
    fun moveTargets(d: Device): List<io.github.nebur692.meshclient.core.DeviceGroup> {
        val s = session ?: return emptyList()
        return s.knownGroups.filter { s.canMove(d, it) }.sortedBy { it.name.lowercase() }
    }

    /** Processes or services of a device, while their screen is open. / Procesos o servicios, mientras su pantalla está abierta. */
    var deviceTools by mutableStateOf<DeviceToolsModel?>(null)
        private set

    fun openDeviceTools(id: String, services: Boolean) {
        val d = devices[id] ?: return
        deviceTools?.close()
        deviceTools = DeviceToolsModel({ session }, d, services, viewModelScope)
        show(Screen.Tools(id))
    }

    /** The chat with the person at a device, while its screen is open. / El chat, mientras su pantalla está abierta. */
    var chat by mutableStateOf<ChatModel?>(null)
        private set

    fun openChat(id: String) {
        val d = devices[id] ?: return
        if (chat?.device?.id == id) { show(Screen.Chat(id)); return }
        chat?.close()
        chat = ChatModel({ session }, d, viewModelScope)
        show(Screen.Chat(id))
    }

    /** The recordings, while their screen is open. / Las grabaciones, mientras su pantalla está abierta. */
    var recordings by mutableStateOf<RecordingsModel?>(null)
        private set

    fun openRecordings() {
        recordings?.close()
        recordings = RecordingsModel({ session }, getApplication<Application>().contentResolver, viewModelScope)
        show(Screen.Recordings)
    }

    fun leaveRecordings() {
        recordings?.close(); recordings = null
        show(Screen.Account)
    }

    fun leaveChat() {
        val id = chat?.device?.id
        chat?.close(); chat = null
        show(if (id != null && devices[id] != null) Screen.DeviceDetail(id) else Screen.Devices)
    }

    fun leaveDeviceTools() {
        val id = deviceTools?.device?.id
        deviceTools?.close(); deviceTools = null
        show(if (id != null && devices[id] != null) Screen.DeviceDetail(id) else Screen.Devices)
    }

    fun moveDevice(d: Device, target: io.github.nebur692.meshclient.core.DeviceGroup) = act(R.string.done_moved) { it.moveDevice(d, target) }

    // ------------------------------------------------------------------ terminal and files / terminal y ficheros

    var terminal by mutableStateOf<TerminalModel?>(null)
        private set
    var files by mutableStateOf<FilesModel?>(null)
        private set
    var desktop by mutableStateOf<DesktopModel?>(null)
        private set

    fun openDesktop(id: String) {
        val s = session ?: return
        val d = devices[id] ?: return
        desktop?.close()
        sheet = null
        desktop = DesktopModel({ session }, d, viewModelScope).also { it.open() }
        keep(d, R.string.act_desktop)
        screen = Screen.Desktop(id)
    }

    fun openTerminal(id: String, kind: TunnelProtocol) {
        val s = session ?: return
        val d = devices[id] ?: return
        terminal?.close()
        sheet = null
        val app = getApplication<Application>()
        terminal = TerminalModel(d, app.getString(io.github.nebur692.meshclient.ui.terminalName(kind, d.isWindows)), { c, r ->
            io.github.nebur692.meshclient.core.TerminalSession.open(session ?: s, d.id, kind, c, r)
        }, viewModelScope) { openTerminal(id, kind) }
        keep(d, R.string.act_terminal)
        screen = Screen.Terminal(id)
    }

    fun openFiles(id: String) {
        val s = session ?: return
        val d = devices[id] ?: return
        files?.close()
        sheet = null
        // It asks for the current connection each time: after a reconnection, the old one is closed. / Pide la conexión de ahora cada vez.
        files = FilesModel({ session }, d, viewModelScope, getApplication<Application>().contentResolver).also { it.open() }
        keep(d, R.string.act_files)
        screen = Screen.Files(id)
    }

    /** Back from a terminal or the files to the device's sheet, closing its tunnel. / De vuelta a la ficha, cerrando su túnel. */
    fun leaveTool() {
        val id = when (val s = screen) { is Screen.Terminal -> s.id; is Screen.Files -> s.id; is Screen.Desktop -> s.id; else -> null }
        closeTools()
        show(if (id != null && devices[id] != null) Screen.DeviceDetail(id) else Screen.Devices)
    }

    private fun closeTools() {
        chat?.close(); chat = null
        recordings?.close(); recordings = null
        terminal?.close(); terminal = null
        files?.close(); files = null
        desktop?.close(); desktop = null
        keepForMappings()
    }

    private val toolOpen get() = terminal != null || files != null || desktop != null || mappings.isNotEmpty()

    // ------------------------------------------------------------------ SSH

    private val knownHosts = KnownHosts(app)
    val sshPlaces = SshPlaces(app)

    /** An SSH server's identity waiting for the person. / La identidad de un servidor SSH esperando a la persona. */
    var hostKeyPrompt by mutableStateOf<HostKeyPrompt?>(null)
        private set
    private var hostKeyAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    fun answerHostKey(yes: Boolean) {
        hostKeyPrompt = null
        hostKeyAnswer?.complete(yes)
    }

    /**
     * Known and equal: yes. New or changed: the person decides (the SSH client waits on its own thread); a yes is kept.
     * Conocida e igual: sí. Nueva o cambiada: decide la persona (el cliente SSH espera en su hilo); un sí se guarda.
     */
    private fun trustHostKey(serverKey: String, destination: String, k: io.github.nebur692.meshclient.core.SshHostKey): Boolean {
        val before = knownHosts.get(serverKey)
        if (before == KnownHosts.line(k)) return true
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        hostKeyAnswer = answer
        viewModelScope.launch { hostKeyPrompt = HostKeyPrompt(destination, k, before) }
        val yes = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(120_000) { answer.await() } } == true
        if (yes) knownHosts.accept(serverKey, k)
        return yes
    }

    /** An SSH terminal to the device, or to [host] seen from it. / Una terminal SSH al equipo, o a [host] visto desde él. */
    fun openSsh(id: String, host: String, port: Int, user: String, password: String?, privateKey: ByteArray?, passphrase: String?) {
        val s = session ?: return
        val d = devices[id] ?: return
        terminal?.close()
        sheet = null
        val h = host.trim().ifEmpty { null }
        sshPlaces.put(id, SshPlace(h.orEmpty(), port, user.trim()))
        val destination = "${user.trim()}@${h ?: d.name}:$port"
        val serverKey = knownHosts.key(s.server.toString(), d.id, h, port)
        val login = io.github.nebur692.meshclient.core.SshLogin(user.trim(), password, privateKey, passphrase)
        terminal = TerminalModel(d, "SSH · $destination", { c, r ->
            io.github.nebur692.meshclient.core.SshTerminal.open(session ?: s, d.id, d.name, h, port, login, { k -> trustHostKey(serverKey, destination, k) }, c, r)
        }, viewModelScope) { openSsh(id, host, port, user, password, privateKey, passphrase) }
        keep(d, R.string.act_ssh)
        screen = Screen.Terminal(id)
    }

    /** A private key file the person picked, read here (it is small). / Un fichero de clave que eligió la persona. */
    fun readKey(uri: android.net.Uri): ByteArray? = runCatching {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().takeIf { b -> b.size < 64 * 1024 } }
    }.getOrNull()

    // ------------------------------------------------------------------ port mapping / redirección de puertos

    /** The mappings open now; they live until closed or signing out. / Las redirecciones abiertas; viven hasta cerrarlas o salir. */
    val mappings = androidx.compose.runtime.mutableStateListOf<MappingView>()

    fun startMapping(id: String, remotePort: Int, host: String, localPort: Int): Boolean {
        if (session == null) return false
        val d = devices[id] ?: return false
        return try {
            val m = io.github.nebur692.meshclient.core.PortMapping.start({ session },
                io.github.nebur692.meshclient.core.PortTarget(d.id, d.name, remotePort, host.trim().ifEmpty { null }, localPort))
            mappings += MappingView(m, viewModelScope)
            keepForMappings()
            if (!notificationsAsked) { notificationsAsked = true; askNotifications = true }
            true
        } catch (e: MeshClientException) {
            notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            false
        }
    }

    fun stopMapping(m: MappingView) {
        m.close()
        mappings.remove(m)
        keepForMappings()
    }

    private fun closeMappings() {
        mappings.forEach { it.close() }
        mappings.clear()
    }

    /** With mappings left, the notification says so; with nothing open, it goes. / Con redirecciones, el aviso lo dice. */
    private fun keepForMappings() {
        val app = getApplication<Application>()
        when {
            terminal != null || files != null || desktop != null -> Unit
            mappings.isEmpty() -> RemoteSessionService.stop(app)
            else -> RemoteSessionService.start(app, mappings.joinToString { it.mapping.target.destination },
                app.resources.getQuantityString(R.plurals.ports_open, mappings.size, mappings.size))
        }
    }

    /** Wants the notification permission once, the first time a tool opens. / Quiere el permiso de avisos al abrir una. */
    var askNotifications by mutableStateOf(false)
    private var notificationsAsked = false

    private fun keep(d: Device, tool: Int) {
        val app = getApplication<Application>()
        RemoteSessionService.start(app, d.name, app.getString(tool))
        if (!notificationsAsked) { notificationsAsked = true; askNotifications = true }
    }

    /**
     * Android's default network changed (Wi-Fi, mobile data): what was open belongs to the old one and may hang instead of
     * failing. Kept connections go, and the live connection opens again on the new network without a word.
     * Cambió la red por defecto de Android (wifi, datos): lo abierto es de la anterior y puede colgarse en vez de fallar.
     * Lo guardado se tira y la conexión en vivo se abre otra vez en la red nueva, sin avisar.
     */
    private val networks = object : android.net.ConnectivityManager.NetworkCallback() {
        private var current: android.net.Network? = null
        override fun onAvailable(network: android.net.Network) {
            val before = current
            current = network
            if (before == null || before == network) return
            MeshSession.networkChanged()
            viewModelScope.launch { onNetworkChanged() }
        }
    }

    private fun onNetworkChanged() {
        if (session == null) return
        if (!visible) { paused = true; return }
        lost = false
        reconnect()
    }

    init {
        val connectivity = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networks) }
    }

    init {
        // "Disconnect" in the notification closes the tool, as the back arrow does. / «Desconectar» la cierra, como la flecha.
        viewModelScope.launch { RemoteSessionService.disconnectRequests.collect {
            if (!toolOpen) return@collect
            closeMappings()
            if (terminal != null || files != null || desktop != null) leaveTool() else keepForMappings()
            // Out of sight, the connection then closes as usual. / Fuera de la vista, la conexión se cierra como siempre.
            if (!visible) onBackground()
        } }
    }

    fun signOut() {
        closeMappings()
        closeTools()
        admin = null
        ownAccount = null
        bulk.clear()
        serverState.stop()
        watching?.cancel()
        detailsJob?.cancel()
        session?.close()
        session = null
        credentials = null
        signedInWith = null
        signOnCookies = null
        rememberPrompt = null
        biometricOffer = null
        details = null
        detailsFor = null
        sheet = null
        commandOutput = null
        backgroundJob?.cancel()
        paused = false
        devices.clear()
        lost = false
        savedServers = saved.all()
        form = LoginForm(expanded = savedServers.isEmpty())
        savedServers.firstOrNull()?.let(::pick)
        screen = Screen.Login
    }

    override fun onCleared() {
        closeMappings()
        runCatching { getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(networks) }
        closeTools()
        session?.close()
    }
}
