package io.github.nebur692.meshclient.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A terminal (shell, cmd or PowerShell) on a device over a relay tunnel, as the Windows app opens it: xterm output as
 * bytes, what the person types as UTF-8, and the size in columns and rows.
 * Una terminal (shell, cmd o PowerShell) en un equipo por un túnel de relé, como la abre la app de Windows: la salida
 * xterm en bytes, lo que escribe la persona en UTF-8 y el tamaño en columnas y filas.
 */
class TerminalSession private constructor(val tunnel: MeshTunnel) : TerminalConnection {

    override val isRecorded: Boolean get() = tunnel.isRecorded

    /**
     * What the device prints, in order; it ends with the reason as its error (none if [close] was called). Some agents
     * send the prompt as text: the same UTF-8 for the screen. One collector at a time; stopping does not close anything.
     * Lo que escribe el equipo, en orden; acaba con el motivo como error (ninguno si se llamó a [close]). Algunos
     * agentes mandan el aviso como texto: el mismo UTF-8 para la pantalla. Lo lee un solo colector a la vez; dejar de leer no cierra nada.
     */
    override val output: Flow<ByteArray> = tunnel.frames.receiveAsFlow().mapNotNull {
        when (it) {
            is TunnelFrame.Binary -> it.bytes
            is TunnelFrame.Text -> it.text.toByteArray(Charsets.UTF_8)
            // What the agent says about the session ("console", e.g. waiting for the user, or why it could not open it) is
            // written on the screen in yellow: otherwise the terminal would stay empty with nothing said.
            // Lo que dice el agente de la sesión («console», p. ej. esperando al usuario o por qué no pudo abrirla) se
            // escribe en la pantalla en amarillo: si no, la terminal se quedaría vacía sin decir nada.
            is TunnelFrame.Control -> it.message.takeIf { m -> m.str("type") == "console" }?.str("msg")?.takeIf(String::isNotBlank)
                ?.let { msg -> "\r\n\u001b[33m$msg\u001b[0m\r\n".toByteArray(Charsets.UTF_8) }
        }
    }

    /** What the person types. / Lo que escribe la persona. */
    override fun write(text: String) = tunnel.send(text.toByteArray(Charsets.UTF_8), LOST)

    override fun resize(cols: Int, rows: Int) = tunnel.sendControl(buildJsonObject { put("type", "termsize"); put("cols", cols); put("rows", rows) }, LOST)

    override fun close() = tunnel.close()

    companion object {
        private const val LOST = "tunel/ver-pantalla/conexion-cortada"

        val kinds = setOf(TunnelProtocol.Terminal, TunnelProtocol.PowerShell, TunnelProtocol.UserShell, TunnelProtocol.UserPowerShell)

        suspend fun open(session: MeshSession, nodeId: String, kind: TunnelProtocol, cols: Int, rows: Int): TerminalSession {
            require(kind in kinds) { "not a terminal: $kind" }
            val options = buildJsonObject { put("protocol", kind.code); put("cols", cols); put("rows", rows); put("xterm", true) }
            return TerminalSession(MeshTunnel.open(session, nodeId, kind, options))
        }
    }
}
