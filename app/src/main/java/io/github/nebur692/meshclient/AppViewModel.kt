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
enum class DeviceSheet { Terminal, Power, More, Message, Run, Edit, ConfirmRestart, ConfirmPowerOff, ConfirmUninstall, ConfirmRemove }

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

    // ------------------------------------------------------------------ the device / el equipo

    fun openDevice(id: String) {
        screen = Screen.DeviceDetail(id)
        sheet = null
        commandOutput = null
        loadDetails(id)
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

    fun remove(d: Device) = act(R.string.done_removed) {
        it.removeDevice(d)
        devices.remove(d.id)
        screen = Screen.Devices
    }

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
            try {
                open(if (c != null) MeshSession.connect(address, c, CertificateTrust(pendingPin), userAgent = userAgent())
                     else MeshSession.connectWithCookies(address, cookies!!, CertificateTrust(pendingPin), userAgent()))
                (screen as? Screen.DeviceDetail)?.let { loadDetails(it.id) }
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
        desktop?.onScreen(true)
        backgroundJob?.cancel()
        refreshBiometrics()
        if (!paused) return
        paused = false
        if (session != null) reconnect()
    }

    fun show(target: Screen) { screen = target; if (target !is Screen.DeviceDetail) sheet = null }

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
        desktop = DesktopModel(s, d, viewModelScope).also { it.open() }
        keep(d, R.string.act_desktop)
        screen = Screen.Desktop(id)
    }

    fun openTerminal(id: String, kind: TunnelProtocol) {
        val s = session ?: return
        val d = devices[id] ?: return
        terminal?.close()
        sheet = null
        terminal = TerminalModel(s, d, kind, viewModelScope)
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
        terminal?.close(); terminal = null
        files?.close(); files = null
        desktop?.close(); desktop = null
        RemoteSessionService.stop(getApplication())
    }

    private val toolOpen get() = terminal != null || files != null || desktop != null

    /** Wants the notification permission once, the first time a tool opens. / Quiere el permiso de avisos al abrir una. */
    var askNotifications by mutableStateOf(false)
    private var notificationsAsked = false

    private fun keep(d: Device, tool: Int) {
        val app = getApplication<Application>()
        RemoteSessionService.start(app, d.name, app.getString(tool))
        if (!notificationsAsked) { notificationsAsked = true; askNotifications = true }
    }

    init {
        // "Disconnect" in the notification closes the tool, as the back arrow does. / «Desconectar» la cierra, como la flecha.
        viewModelScope.launch { RemoteSessionService.disconnectRequests.collect {
            if (!toolOpen) return@collect
            leaveTool()
            // Out of sight, the connection then closes as usual. / Fuera de la vista, la conexión se cierra como siempre.
            if (!visible) onBackground()
        } }
    }

    fun signOut() {
        closeTools()
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
        closeTools()
        session?.close()
    }
}
