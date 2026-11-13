package io.github.nebur692.meshclient

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.DeviceProcess
import io.github.nebur692.meshclient.core.DeviceService
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.ServiceAction
import io.github.nebur692.meshclient.core.killProcess
import io.github.nebur692.meshclient.core.processes
import io.github.nebur692.meshclient.core.serviceAction
import io.github.nebur692.meshclient.core.services
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A device's processes or services, asked when the screen opens and when the person refreshes: nothing is polled in the
 * background (battery, data). An action that gets no answer (end a process, start a service) is checked by asking the
 * list again: what is shown is what the device says, not what was hoped.
 * Los procesos o servicios de un equipo, pedidos al abrir la pantalla y al actualizar: nada se pide en segundo plano.
 * Una acción sin respuesta se comprueba pidiendo otra vez la lista: lo que se ve es lo que dice el equipo.
 */
class DeviceToolsModel(private val session: () -> MeshSession?, val device: Device, val services: Boolean, private val scope: CoroutineScope) {
    var processes by mutableStateOf<List<DeviceProcess>?>(null)
        private set
    var serviceList by mutableStateOf<List<DeviceService>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** The agent gave no list (no service manager on this device). / El agente no dio lista. */
    var unavailable by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiError?>(null)
    var notice by mutableStateOf<Notice?>(null)
    var filter by mutableStateOf("")

    private var job: Job? = null

    private fun current() = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "herramientas/abrir-detalle/conexion-cortada", "no session")

    fun load() {
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                if (services) {
                    val l = current().services(device); serviceList = l.orEmpty(); unavailable = l == null
                } else {
                    val l = current().processes(device); processes = l.orEmpty(); unavailable = l == null
                }
            } catch (e: MeshClientException) {
                error = UiError(e.failure, e.key, e.httpStatus)
            } finally {
                loading = false
            }
        }
    }

    fun kill(p: DeviceProcess) = scope.launch {
        try {
            current().killProcess(device, p)
            // The agent does not answer: the list says whether it ended (on Linux the agent may not manage it).
            // El agente no contesta: la lista dice si acabó (en Linux puede que el agente no lo consiga).
            delay(1200)
            val after = current().processes(device)
            processes = after.orEmpty()
            notice = Notice(if (after?.any { it.pid == p.pid } == true) R.string.tools_kill_failed else R.string.tools_killed)
        } catch (e: MeshClientException) {
            notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
        }
    }

    fun act(s: DeviceService, action: ServiceAction) = scope.launch {
        try {
            current().serviceAction(device, s, action)
            delay(2000)
            val after = current().services(device)
            serviceList = after.orEmpty()
            notice = Notice(R.string.tools_service_asked)
        } catch (e: MeshClientException) {
            notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
        }
    }

    fun close() { job?.cancel() }
}
