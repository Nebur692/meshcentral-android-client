package io.github.nebur692.meshclient

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshRights
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.PowerAction
import io.github.nebur692.meshclient.core.notify
import io.github.nebur692.meshclient.core.power
import io.github.nebur692.meshclient.core.rightsOn
import io.github.nebur692.meshclient.core.runCommands
import io.github.nebur692.meshclient.core.shellFor
import io.github.nebur692.meshclient.core.wake
import io.github.nebur692.meshclient.core.canUpdateAgent
import io.github.nebur692.meshclient.core.updateAgents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** What can be done to several devices at once. / Lo que se puede hacer a varios equipos a la vez. */
enum class BulkAction { Wake, Sleep, Restart, PowerOff, Notify, Run, UpdateAgent }

enum class BulkSheet { Power, Notify, Run, ConfirmRestart, ConfirmPowerOff, ConfirmUpdateAgent }

/** One device's answer to commands run on several. / La respuesta de un equipo a órdenes ejecutadas en varios. */
data class BulkOutput(val device: Device, val output: String?, val error: UiError?)

/**
 * The devices picked in the list (a long press starts it) and what is done to them. Each action goes to the picked
 * devices that allow it (rights, agent connected); the others are left out and said so, as the website does. Power and
 * notifications go in one request to the server; commands go one per device at the same time, since each agent runs
 * one batch at a time.
 * Los equipos elegidos en la lista (se empieza con una pulsación larga) y lo que se les hace. Cada acción va a los
 * elegidos que la permiten (permisos, agente conectado); los demás se quedan fuera y se dice. Energía y avisos van en
 * una petición al servidor; las órdenes, una por equipo a la vez, porque cada agente ejecuta una tanda cada vez.
 */
class BulkModel(private val session: () -> MeshSession?, private val scope: CoroutineScope) {
    /** The picked device ids; empty: not picking. / Los ids elegidos; vacío: sin elegir. */
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var sheet by mutableStateOf<BulkSheet?>(null)
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Notice?>(null)
    /** How many went and how many were left out, for the last notice. / Cuántos fueron y cuántos se quedaron fuera. */
    var noticeCounts by mutableStateOf(0 to 0)
        private set
    var outputs by mutableStateOf<List<BulkOutput>?>(null)
        private set
    /** What the agents said to the last update, until read. / Lo que dijeron los agentes a la última actualización. */
    var answers by mutableStateOf<AgentAnswers?>(null)

    val active: Boolean get() = selected.isNotEmpty()

    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun selectAll(ids: Collection<String>) { selected = selected + ids }
    fun clear() { selected = emptySet(); sheet = null; outputs = null }

    /** The picked devices that still exist. / Los elegidos que siguen existiendo. */
    fun devices(all: Map<String, Device>): List<Device> = selected.mapNotNull(all::get)

    /** The ones [action] would reach. / Los que alcanzaría [action]. */
    fun eligible(action: BulkAction, devices: List<Device>): List<Device> {
        val s = session() ?: return emptyList()
        return devices.filter { d ->
            val r = s.rightsOn(d)
            when (action) {
                BulkAction.Wake -> MeshRights.has(r, MeshRights.WAKE)
                BulkAction.Sleep, BulkAction.Restart, BulkAction.PowerOff -> d.hasAgent && MeshRights.has(r, MeshRights.RESET_OFF)
                BulkAction.Notify -> d.hasAgent && MeshRights.has(r, MeshRights.CHAT_NOTIFY)
                BulkAction.Run -> d.hasAgent && MeshRights.has(r, MeshRights.REMOTE_COMMAND)
                BulkAction.UpdateAgent -> s.canUpdateAgent(d)
            }
        }
    }

    private fun current() = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "acciones/accionar-dispositivo/conexion-cortada", "no session")

    fun run(action: BulkAction, all: Map<String, Device>, title: String = "", text: String = "", commands: String = "", powerShell: Boolean = false) {
        if (busy) return
        val picked = devices(all)
        val go = eligible(action, picked)
        if (go.isEmpty()) return
        busy = true
        if (action == BulkAction.Run) outputs = null
        scope.launch {
            try {
                val s = current()
                when (action) {
                    BulkAction.Wake -> s.wake(go)
                    BulkAction.Sleep -> s.power(go, PowerAction.Sleep)
                    BulkAction.Restart -> s.power(go, PowerAction.Restart)
                    BulkAction.PowerOff -> s.power(go, PowerAction.PowerOff)
                    BulkAction.Notify -> s.notify(go, title, text)
                    BulkAction.UpdateAgent -> s.updateAgents(go).let { said -> answers = AgentAnswers(go.map { it.name to said[it] }) }
                    BulkAction.Run -> {
                        // Each device answers on its own; one that fails does not stop the others. / Uno que falla no para a los demás.
                        outputs = go.map { d ->
                            scope.async {
                                try { BulkOutput(d, s.runCommands(d, commands, shellFor(d, powerShell)).ifEmpty { "—" }, null) }
                                catch (e: MeshClientException) { BulkOutput(d, null, UiError(e.failure, e.key, e.httpStatus)) }
                            }
                        }.awaitAll()
                        return@launch
                    }
                }
                sheet = null
                noticeCounts = go.size to (picked.size - go.size)
                notice = Notice(when (action) {
                    BulkAction.Wake -> R.string.done_wake
                    BulkAction.Sleep -> R.string.done_sleep
                    BulkAction.Restart -> R.string.done_restart
                    BulkAction.PowerOff -> R.string.done_power_off
                    BulkAction.UpdateAgent -> R.string.done_update_agent
                    else -> R.string.done_message
                })
            } catch (e: MeshClientException) {
                noticeCounts = 0 to 0
                notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            } finally {
                busy = false
            }
        }
    }

    /** The shell of a mixed selection: PowerShell or cmd only matter on Windows. / El intérprete: solo importa en Windows. */
    fun anyWindows(all: Map<String, Device>) = devices(all).any { it.isWindows }

    fun clearOutputs() { outputs = null }

}
