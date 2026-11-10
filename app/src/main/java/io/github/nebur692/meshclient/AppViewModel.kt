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
import io.github.nebur692.meshclient.data.SavedServer
import io.github.nebur692.meshclient.data.SavedServers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Login : Screen
    data object Devices : Screen
    data object Account : Screen
    data class DeviceDetail(val id: String) : Screen
}

/** A failure as the screen shows it. / Un fallo tal como lo enseña la pantalla. */
data class UiError(val failure: MeshFailure, val key: String, val httpStatus: Int? = null)

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

    // Kept only while signed in, to reconnect or answer the second factor. / Solo mientras dura la sesión.
    private var credentials: PasswordCredentials? = null
    private var pendingPin: String? = null
    private var watching: Job? = null

    init {
        savedServers.firstOrNull()?.let(::pick)
    }

    fun edit(change: LoginForm.() -> LoginForm) { form = form.change().copy(error = null) }

    fun pick(server: SavedServer) {
        form = form.copy(server = server.address, username = server.username,
            password = saved.password(server).orEmpty(), remember = server.hasPassword, error = null)
    }

    fun forget(server: SavedServer) {
        saved.forget(server.address)
        savedServers = saved.all()
    }

    fun connect() {
        val address = ServerAddress.parse(form.server)
        if (address == null) {
            form = form.copy(error = UiError(MeshFailure.InvalidAddress, "conexion/iniciar-sesion/dato-invalido"))
            return
        }
        credentials = PasswordCredentials(form.username.trim(), form.password)
        pendingPin = savedServers.firstOrNull { it.address == address.toString() }?.pinnedSha256
        attempt(address, credentials!!)
    }

    fun acceptCertificate() {
        val prompt = form.certificate ?: return
        pendingPin = prompt.sha256
        form = form.copy(certificate = null)
        ServerAddress.parse(form.server)?.let { attempt(it, credentials ?: return) }
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

    private fun attempt(address: ServerAddress, creds: PasswordCredentials) {
        form = form.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val s = MeshSession.connect(address, creds, CertificateTrust(pendingPin), userAgent = "MeshCentralAndroidClient/${BuildConfig.VERSION_NAME}")
                // A second-factor code is single-use: a reconnection asks for a new one. / El código es de un solo uso.
                credentials = creds.withCode(null)
                saved.remember(address.toString(), creds.username, pendingPin, if (form.remember) form.password else null)
                savedServers = saved.all()
                open(s)
                form = form.copy(busy = false, password = if (form.remember) form.password else "")
                screen = Screen.Devices
            } catch (e: MeshClientException) {
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
        val c = credentials ?: return signOut()
        val address = session?.server ?: return signOut()
        lost = false
        viewModelScope.launch {
            try {
                open(MeshSession.connect(address, c, CertificateTrust(pendingPin), userAgent = "MeshCentralAndroidClient/${BuildConfig.VERSION_NAME}"))
            } catch (_: MeshClientException) {
                lost = true
            }
        }
    }

    fun show(target: Screen) { screen = target }

    fun signOut() {
        watching?.cancel()
        session?.close()
        session = null
        credentials = null
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
