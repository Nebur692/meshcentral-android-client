package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A piece of what an agent wrote on its console. / Un trozo de lo que escribió un agente en su consola. */
data class ConsoleText(val deviceId: String, val text: String)

/*
 * The agent's own console, as the website's "Console" tab (a "msg" of type "console"), the same as the Windows app:
 * commands to the agent itself (help, info, netinfo…). The answer comes on its own, in as many pieces as the agent
 * likes, through [MeshSession.consoleOutput]. The agent requires remote control and console rights.
 * La consola del propio agente, como la pestaña «Consola» de la web, igual que la app de Windows. La respuesta llega
 * sola, en los trozos que quiera, por [MeshSession.consoleOutput]. El agente pide control remoto y consola.
 */

fun MeshSession.canUseAgentConsole(device: Device): Boolean {
    val r = rightsOn(device)
    return device.hasAgent && device.capabilities and AgentCaps.CONSOLE != 0 &&
        (r == MeshRights.FULL || (MeshRights.has(r, MeshRights.REMOTE_CONTROL) && MeshRights.has(r, MeshRights.AGENT_CONSOLE)))
}

fun MeshSession.sendConsoleCommand(device: Device, command: String) {
    if (!canUseAgentConsole(device)) throw MeshClientException(MeshFailure.NotAllowed, "ficha/consola/no-autorizado", "This account has no rights to use the agent's console.")
    val text = command.trim()
    if (text.isEmpty() || text.length > 4096) throw MeshClientException(MeshFailure.Rejected, "ficha/consola/dato-invalido", "A console command is one line of up to 4096 characters.")
    send(buildJsonObject { put("action", "msg"); put("type", "console"); put("nodeid", device.id); put("value", text) }, "ficha/consola/conexion-cortada")
}
