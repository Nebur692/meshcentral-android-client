package io.github.nebur692.meshclient

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent

/** What a physical key sends to the device. / Lo que manda al equipo una tecla física. */
sealed interface KeyOut {
    /** A Windows virtual key, down or up. / Una tecla virtual de Windows, pulsada o soltada. */
    data class Key(val vk: Int, val down: Boolean, val extended: Boolean = false) : KeyOut

    /** A character, whatever the device's keyboard layout. / Un carácter, sea cual sea la distribución del equipo. */
    data class Text(val codePoint: Int) : KeyOut
}

/**
 * A physical keyboard on the remote desktop. What it writes goes as characters, so a Spanish keyboard on the phone writes
 * the same "ñ", "@" or "á" on a device set up with any other layout. Shortcuts (Ctrl, Alt or Windows held), and the keys that
 * write nothing (arrows, F1–F12, Esc…), go as real keys pressed and released, so Ctrl+C, Alt+F4 or Shift+arrows work as on the
 * device's own keyboard. Ctrl, Alt, Shift and Windows reach the device only when another key or a click needs them; pressed
 * and released alone, Alt and Windows go as they are (the Start menu).
 * Un teclado físico en el escritorio remoto. Lo que escribe va como caracteres, así un teclado español en el móvil escribe la
 * misma «ñ», «@» o «á» en un equipo configurado con cualquier otra distribución. Los atajos (con Ctrl, Alt o Windows
 * pulsadas) y las teclas que no escriben (flechas, F1–F12, Esc…) van como teclas de verdad, pulsadas y soltadas, así Ctrl+C,
 * Alt+F4 o Mayús+flechas funcionan como en el teclado del propio equipo. Ctrl, Alt, Mayús y Windows llegan al equipo solo
 * cuando otra tecla o un clic las necesita; pulsadas y soltadas solas, Alt y Windows van tal cual (el menú Inicio).
 *
 * Pure: Android's key codes in, what to send out; [combine] joins a dead accent and a letter (Android's own table).
 * Pura: entran los códigos de tecla de Android y sale lo que hay que mandar; [combine] une un acento muerto y una letra.
 */
class PhysicalKeyboard(private val combine: (accent: Int, c: Int) -> Int = { a, c -> KeyCharacterMap.getDeadChar(a, c) }) {
    /** Modifiers held on the phone's keyboard. / Modificadores pulsados en el teclado del móvil. */
    private val held = linkedSetOf<Int>()
    /** The ones already down on the device. / Los que ya están pulsados en el equipo. */
    private val sent = linkedSetOf<Int>()
    /** Another key or a click came while modifiers were held. / Vino otra tecla o un clic con los modificadores pulsados. */
    private var used = false
    /** Keys down on the device, by the phone's key code. / Teclas pulsadas en el equipo, por el código del móvil. */
    private val down = mutableMapOf<Int, Pair<Int, Boolean>>()
    /** Keys taken here whose release only needs to be swallowed. / Teclas tomadas aquí cuya suelta solo hay que tragarse. */
    private val swallowed = mutableSetOf<Int>()
    private var accent = 0

    /**
     * A key pressed; [char] is what it writes with the modifiers now held (Android's, 0 if nothing). Null: not ours, Android
     * handles it (Back, volume, media keys). / Una tecla pulsada; [char] es lo que escribe. Null: no es nuestra.
     */
    fun press(keyCode: Int, char: Int, repeat: Int): List<KeyOut>? {
        MODIFIERS[keyCode]?.let { m ->
            if (repeat == 0) { if (held.isEmpty()) used = false; held += m }
            return emptyList()
        }
        // AltGr only changes what the next key writes; Caps Lock and Num Lock, the phone's own state. / Solo cambian lo que se escribe.
        if (keyCode in LOCAL_ONLY) { swallowed += keyCode; return emptyList() }
        val shortcut = held.any { it != SHIFT }
        if (!shortcut && char != 0 && keyCode !in ALWAYS_KEYS) {
            used = true
            swallowed += keyCode
            if (char and KeyCharacterMap.COMBINING_ACCENT != 0) { accent = char and KeyCharacterMap.COMBINING_ACCENT_MASK; return emptyList() }
            val a = accent
            accent = 0
            // A dead accent and a letter it does not go on: the letter alone. / Un acento con una letra que no lo lleva: la letra sola.
            return listOf(KeyOut.Text(if (a != 0) combine(a, char).takeIf { it != 0 } ?: char else char))
        }
        val (vk, extended) = VIRTUAL_KEYS[keyCode] ?: return null
        used = true
        val out = mutableListOf<KeyOut>()
        if (repeat == 0) out += modifiersDown()
        out += KeyOut.Key(vk, true, extended)
        down[keyCode] = vk to extended
        return out
    }

    /** A key released. Null: not ours. / Una tecla soltada. Null: no es nuestra. */
    fun release(keyCode: Int): List<KeyOut>? {
        MODIFIERS[keyCode]?.let { m ->
            if (m !in held) return emptyList()
            held -= m
            return when {
                sent.remove(m) -> listOf(KeyOut.Key(m, false, m in EXTENDED_MODIFIERS))
                // Alone: Alt opens the window's menu, Windows the Start menu. / Sola: Alt abre el menú de la ventana, Windows el Inicio.
                !used && held.isEmpty() && m != SHIFT && m != CTRL -> listOf(KeyOut.Key(m, true, m in EXTENDED_MODIFIERS), KeyOut.Key(m, false, m in EXTENDED_MODIFIERS))
                else -> emptyList()
            }
        }
        down.remove(keyCode)?.let { (vk, extended) -> return listOf(KeyOut.Key(vk, false, extended)) }
        return if (swallowed.remove(keyCode)) emptyList() else null
    }

    /** Before a click: Ctrl+click, Shift+click. / Antes de un clic: Ctrl+clic, Mayús+clic. */
    fun beforeClick(): List<KeyOut> { if (held.isNotEmpty()) used = true; return modifiersDown() }

    /** The window lost the keyboard: nothing may stay held on the device. / La ventana perdió el teclado: nada se queda pulsado. */
    fun releaseAll(): List<KeyOut> {
        val out = down.values.map { (vk, extended) -> KeyOut.Key(vk, false, extended) } +
            sent.map { KeyOut.Key(it, false, it in EXTENDED_MODIFIERS) }
        down.clear(); sent.clear(); held.clear(); swallowed.clear(); accent = 0; used = false
        return out
    }

    private fun modifiersDown(): List<KeyOut> = held.filter { it !in sent }.map { sent += it; KeyOut.Key(it, true, it in EXTENDED_MODIFIERS) }

    companion object {
        private const val SHIFT = 16
        private const val CTRL = 17
        private val EXTENDED_MODIFIERS = setOf(91, 92)

        private val MODIFIERS = mapOf(
            KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT, KeyEvent.KEYCODE_SHIFT_RIGHT to SHIFT,
            KeyEvent.KEYCODE_CTRL_LEFT to CTRL, KeyEvent.KEYCODE_CTRL_RIGHT to CTRL,
            KeyEvent.KEYCODE_ALT_LEFT to 18, KeyEvent.KEYCODE_META_LEFT to 91, KeyEvent.KEYCODE_META_RIGHT to 92,
        )
        private val LOCAL_ONLY = setOf(KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK)

        /** Keys that write something on the phone but are keys on the device. / Teclas que escriben en el móvil pero son teclas en el equipo. */
        private val ALWAYS_KEYS = setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)

        /** Android's key code to Windows' virtual key, and whether it is an extended one. / Código de Android a tecla virtual de Windows. */
        internal val VIRTUAL_KEYS: Map<Int, Pair<Int, Boolean>> = buildMap {
            for (i in 0..25) put(KeyEvent.KEYCODE_A + i, 65 + i to false)
            for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, 48 + i to false)
            for (i in 0..11) put(KeyEvent.KEYCODE_F1 + i, 112 + i to false)
            for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, 96 + i to false)
            put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 106 to false); put(KeyEvent.KEYCODE_NUMPAD_ADD, 107 to false)
            put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 109 to false); put(KeyEvent.KEYCODE_NUMPAD_DOT, 110 to false)
            put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 111 to true); put(KeyEvent.KEYCODE_NUMPAD_ENTER, 13 to true)
            put(KeyEvent.KEYCODE_ENTER, 13 to false); put(KeyEvent.KEYCODE_TAB, 9 to false); put(KeyEvent.KEYCODE_DEL, 8 to false)
            put(KeyEvent.KEYCODE_ESCAPE, 27 to false); put(KeyEvent.KEYCODE_SPACE, 32 to false)
            put(KeyEvent.KEYCODE_INSERT, 45 to true); put(KeyEvent.KEYCODE_FORWARD_DEL, 46 to true)
            put(KeyEvent.KEYCODE_MOVE_HOME, 36 to true); put(KeyEvent.KEYCODE_MOVE_END, 35 to true)
            put(KeyEvent.KEYCODE_PAGE_UP, 33 to true); put(KeyEvent.KEYCODE_PAGE_DOWN, 34 to true)
            put(KeyEvent.KEYCODE_DPAD_LEFT, 37 to true); put(KeyEvent.KEYCODE_DPAD_UP, 38 to true)
            put(KeyEvent.KEYCODE_DPAD_RIGHT, 39 to true); put(KeyEvent.KEYCODE_DPAD_DOWN, 40 to true)
            put(KeyEvent.KEYCODE_SYSRQ, 44 to true); put(KeyEvent.KEYCODE_BREAK, 19 to false); put(KeyEvent.KEYCODE_SCROLL_LOCK, 145 to false)
            put(KeyEvent.KEYCODE_MENU, 93 to true)
            put(KeyEvent.KEYCODE_SEMICOLON, 186 to false); put(KeyEvent.KEYCODE_EQUALS, 187 to false); put(KeyEvent.KEYCODE_PLUS, 187 to false)
            put(KeyEvent.KEYCODE_COMMA, 188 to false); put(KeyEvent.KEYCODE_MINUS, 189 to false); put(KeyEvent.KEYCODE_PERIOD, 190 to false)
            put(KeyEvent.KEYCODE_SLASH, 191 to false); put(KeyEvent.KEYCODE_GRAVE, 192 to false); put(KeyEvent.KEYCODE_LEFT_BRACKET, 219 to false)
            put(KeyEvent.KEYCODE_BACKSLASH, 220 to false); put(KeyEvent.KEYCODE_RIGHT_BRACKET, 221 to false); put(KeyEvent.KEYCODE_APOSTROPHE, 222 to false)
        }

        /** A real keyboard: not the on-screen one nor buttons like volume. / Un teclado de verdad: ni el de pantalla ni botones como el volumen. */
        fun isPhysical(e: KeyEvent): Boolean {
            if (e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || e.source and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD) return false
            val device = e.device ?: return false
            return !device.isVirtual && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        }
    }
}
