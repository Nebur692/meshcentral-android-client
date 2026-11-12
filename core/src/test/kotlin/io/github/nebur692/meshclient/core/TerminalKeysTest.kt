package io.github.nebur692.meshclient.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeysTest {
    private fun k(s: String, ctrl: Boolean = false, alt: Boolean = false) = TerminalKeys.modified(s, ctrl, alt)

    @Test fun `without modifiers everything goes as it is`() {
        assertEquals("c", k("c"))
        assertEquals("\u001b[A", k("\u001b[A"))
        assertEquals("", k("", ctrl = true))
    }

    @Test fun `ctrl on a letter is its control code, upper or lower case`() {
        assertEquals("\u0003", k("c", ctrl = true))
        assertEquals("\u0003", k("C", ctrl = true))
        assertEquals("\u0004", k("d", ctrl = true))
        assertEquals("\u001a", k("z", ctrl = true))
        assertEquals("\u001b", k("[", ctrl = true))
        assertEquals("\u0000", k(" ", ctrl = true))
        assertEquals("\u007f", k("?", ctrl = true))
    }

    @Test fun `ctrl on a character without a control code leaves it`() {
        assertEquals("ñ", k("ñ", ctrl = true))
        assertEquals("€", k("€", ctrl = true))
    }

    @Test fun `alt puts escape in front, also together with ctrl`() {
        assertEquals("\u001bx", k("x", alt = true))
        assertEquals("\u001b\u0018", k("x", ctrl = true, alt = true))
        assertEquals("\u001b€", k("€", alt = true))
    }

    @Test fun `cursor and editing keys get the xterm modifier number in both cursor modes`() {
        assertEquals("\u001b[1;5A", k("\u001b[A", ctrl = true))
        assertEquals("\u001b[1;5D", k("\u001bOD", ctrl = true))
        assertEquals("\u001b[1;3C", k("\u001b[C", alt = true))
        assertEquals("\u001b[1;7H", k("\u001bOH", ctrl = true, alt = true))
        assertEquals("\u001b[5;5~", k("\u001b[5~", ctrl = true))
        assertEquals("\u001b[3;3~", k("\u001b[3~", alt = true))
    }

    @Test fun `a longer text (paste, a suggested word) is not touched`() {
        assertEquals("hola", k("hola", ctrl = true))
        assertEquals("ls -la\r", k("ls -la\r", ctrl = true, alt = true))
    }

    @Test fun `escape and tab from the bar stay as they are with ctrl`() {
        // Ctrl+Tab and Ctrl+Esc have no code in a terminal. / Ctrl+Tab y Ctrl+Esc no tienen código en un terminal.
        assertEquals("\t", k("\t", ctrl = true))
        assertEquals("\u001b", k("\u001b", ctrl = true))
    }
}

/** The agent's terminals (meshcore.js 1.3.1: 1, 6, 8, 9; 7 is the plugins' data). / Las terminales del agente (7 es de los plugins). */
class TerminalKindsTest {
    @Test fun `the terminal protocols are the agent's own numbers`() {
        assertEquals(listOf(1, 6, 8, 9), TerminalSession.kinds.map { it.code }.sorted())
        assertEquals(8, TunnelProtocol.UserShell.code)
        assertEquals(9, TunnelProtocol.UserPowerShell.code)
    }
}
