package io.github.nebur692.meshclient

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nebur692.meshclient.core.PortMapping
import io.github.nebur692.meshclient.core.PortMappingStats
import io.github.nebur692.meshclient.core.SshHostKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The SSH servers' identities this phone has accepted, by server, device and destination: OpenSSH's known_hosts for the
 * app. A changed one is never taken silently. / Las identidades de servidores SSH aceptadas en este móvil, por servidor,
 * equipo y destino: el known_hosts de la app. Una que cambia nunca se acepta sin decirlo.
 */
class KnownHosts(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE)

    fun key(server: String, nodeId: String, host: String?, port: Int) = "$server|$nodeId|${host.orEmpty()}:$port"

    fun get(key: String): String? = prefs.getString(key, null)

    fun accept(key: String, k: SshHostKey) = prefs.edit { putString(key, "${k.algorithm} ${k.fingerprint}") }

    companion object {
        fun line(k: SshHostKey) = "${k.algorithm} ${k.fingerprint}"
    }
}

/** What was last used for SSH on a device (never the password). / Lo último usado para SSH en un equipo (nunca la contraseña). */
data class SshPlace(val host: String = "", val port: Int = 22, val user: String = "")

class SshPlaces(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_places", Context.MODE_PRIVATE)

    fun get(nodeId: String): SshPlace {
        val v = prefs.getString(nodeId, null)?.split('\n') ?: return SshPlace()
        return SshPlace(v.getOrElse(0) { "" }, v.getOrNull(1)?.toIntOrNull() ?: 22, v.getOrElse(2) { "" })
    }

    fun put(nodeId: String, p: SshPlace) = prefs.edit { putString(nodeId, "${p.host}\n${p.port}\n${p.user}") }
}

/** An SSH server's identity to accept, new or changed. / La identidad de un servidor SSH por aceptar, nueva o cambiada. */
data class HostKeyPrompt(val destination: String, val key: SshHostKey, val before: String?)

/**
 * A port mapping as the screen shows it: its stats follow the mapping live. / Una redirección como la enseña la
 * pantalla: sus datos siguen a la redirección en vivo.
 */
class MappingView(val mapping: PortMapping, scope: CoroutineScope) {
    var stats by mutableStateOf(PortMappingStats(0, 0, 0, 0, null))
        private set
    private val watching: Job = scope.launch { mapping.stats.collect { stats = it } }

    fun close() {
        watching.cancel()
        mapping.close()
    }
}

/** Well-known ports a technician maps, for a quick start. / Puertos conocidos que redirige un técnico, para empezar rápido. */
enum class KnownPort(val port: Int, val label: String, val web: Boolean = false) {
    Rdp(3389, "RDP"), Vnc(5900, "VNC"), Http(80, "HTTP", web = true), Https(443, "HTTPS", web = true), Ssh(22, "SSH")
}
