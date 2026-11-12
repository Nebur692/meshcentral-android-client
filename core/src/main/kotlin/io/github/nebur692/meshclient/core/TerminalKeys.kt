package io.github.nebur692.meshclient.core

/**
 * The Ctrl and Alt of the phone's key bar, applied as xterm does: a phone keyboard has neither, so they are pressed on the
 * bar and change the next key. / El Ctrl y el Alt de la barra de teclas, aplicados como xterm: un teclado de móvil no
 * los tiene, así que se pulsan en la barra y cambian la tecla siguiente.
 */
object TerminalKeys {
    private const val ESC = '\u001b'

    /**
     * [input] as it goes to the device with the modifiers on. One character: Ctrl makes its control code (Ctrl+C = 3), Alt
     * puts ESC in front. A cursor or editing key from the bar ("ESC [ A", "ESC O A", "ESC [ 5 ~") gets xterm's modifier
     * number (3 Alt, 5 Ctrl, 7 both). Anything longer (a paste, a word from the keyboard's suggestions) goes as it is.
     * [input] tal como va al equipo con los modificadores. Un carácter: Ctrl da su código de control (Ctrl+C = 3) y Alt
     * le pone ESC delante. Una tecla de cursor o edición de la barra recibe el número de modificador de xterm (3 Alt,
     * 5 Ctrl, 7 los dos). Lo más largo (lo pegado, una palabra de las sugerencias del teclado) va tal cual.
     */
    fun modified(input: String, ctrl: Boolean, alt: Boolean): String {
        if (!ctrl && !alt || input.isEmpty()) return input
        key(input, ctrl, alt)?.let { return it }
        if (input.codePointCount(0, input.length) != 1) return input
        val c = input.codePointAt(0)
        val withCtrl = if (ctrl) control(c)?.toString() ?: input else input
        return if (alt) ESC + withCtrl else withCtrl
    }

    /** Ctrl on one character, as a terminal sends it; null if it has none. / Ctrl sobre un carácter; null si no tiene. */
    internal fun control(c: Int): Char? = when (c) {
        in 'a'.code..'z'.code -> (c - 'a'.code + 1).toChar()
        in 'A'.code..'Z'.code -> (c - 'A'.code + 1).toChar()
        '@'.code, ' '.code, '2'.code -> 0.toChar()
        '['.code, '3'.code -> 27.toChar()
        '\\'.code, '4'.code -> 28.toChar()
        ']'.code, '5'.code -> 29.toChar()
        '^'.code, '6'.code -> 30.toChar()
        '_'.code, '/'.code, '7'.code -> 31.toChar()
        '?'.code, '8'.code -> 127.toChar()
        else -> null
    }

    private val cursor = Regex("^\u001b[\\[O]([ABCDHF])$")
    private val editing = Regex("^\u001b\\[([0-9]+)~$")

    private fun key(input: String, ctrl: Boolean, alt: Boolean): String? {
        val m = (if (alt) 2 else 0) + (if (ctrl) 4 else 0) + 1
        cursor.matchEntire(input)?.let { return "$ESC[1;$m${it.groupValues[1]}" }
        editing.matchEntire(input)?.let { return "$ESC[${it.groupValues[1]};$m~" }
        return null
    }
}
