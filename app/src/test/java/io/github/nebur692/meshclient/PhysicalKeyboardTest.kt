package io.github.nebur692.meshclient

import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.nebur692.meshclient.KeyOut.Key
import io.github.nebur692.meshclient.KeyOut.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A physical keyboard on the remote desktop, key by key as a person types: what reaches the device.
 * Un teclado físico en el escritorio remoto, tecla a tecla como escribe una persona: lo que llega al equipo.
 */
class PhysicalKeyboardTest {
    // Android's own table, for the two accents these cases use. / La tabla de Android, para los dos acentos de estos casos.
    private val acute = 0x301
    private val k = PhysicalKeyboard { a, c -> if (a == acute && c == 'a'.code) 'á'.code else 0 }

    private fun t(c: Char) = Text(c.code)

    @Test fun `letters and a Spanish key go as characters, and their release sends nothing`() {
        assertEquals(listOf(t('a')), k.press(KeyEvent.KEYCODE_A, 'a'.code, 0))
        assertEquals(emptyList<KeyOut>(), k.release(KeyEvent.KEYCODE_A))
        // The key right of L on a Spanish keyboard. / La tecla a la derecha de la L en un teclado español.
        assertEquals(listOf(t('ñ')), k.press(KeyEvent.KEYCODE_SEMICOLON, 'ñ'.code, 0))
        assertEquals(emptyList<KeyOut>(), k.release(KeyEvent.KEYCODE_SEMICOLON))
    }

    @Test fun `Shift writes the capital without reaching the device`() {
        assertEquals(emptyList<KeyOut>(), k.press(KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
        assertEquals(listOf(t('A')), k.press(KeyEvent.KEYCODE_A, 'A'.code, 0))
        k.release(KeyEvent.KEYCODE_A)
        assertEquals(emptyList<KeyOut>(), k.release(KeyEvent.KEYCODE_SHIFT_LEFT))
    }

    @Test fun `AltGr writes the at sign as a character`() {
        assertEquals(emptyList<KeyOut>(), k.press(KeyEvent.KEYCODE_ALT_RIGHT, 0, 0))
        assertEquals(listOf(t('@')), k.press(KeyEvent.KEYCODE_2, '@'.code, 0))
        assertEquals(emptyList<KeyOut>(), k.release(KeyEvent.KEYCODE_2))
        assertEquals(emptyList<KeyOut>(), k.release(KeyEvent.KEYCODE_ALT_RIGHT))
    }

    @Test fun `a dead accent joins the next letter, or is dropped before one it does not go on`() {
        assertEquals(emptyList<KeyOut>(), k.press(KeyEvent.KEYCODE_APOSTROPHE, KeyCharacterMap.COMBINING_ACCENT or acute, 0))
        k.release(KeyEvent.KEYCODE_APOSTROPHE)
        assertEquals(listOf(t('á')), k.press(KeyEvent.KEYCODE_A, 'a'.code, 0))
        k.press(KeyEvent.KEYCODE_APOSTROPHE, KeyCharacterMap.COMBINING_ACCENT or acute, 0)
        assertEquals(listOf(t('x')), k.press(KeyEvent.KEYCODE_X, 'x'.code, 0))
        // Spent: the next letter is plain. / Gastado: la siguiente letra va sola.
        assertEquals(listOf(t('a')), k.press(KeyEvent.KEYCODE_A, 'a'.code, 0))
    }

    @Test fun `Ctrl+C is Ctrl and C pressed and released on the device`() {
        assertEquals(emptyList<KeyOut>(), k.press(KeyEvent.KEYCODE_CTRL_LEFT, 0, 0))
        assertEquals(listOf(Key(17, true), Key(67, true)), k.press(KeyEvent.KEYCODE_C, 'c'.code, 0))
        assertEquals(listOf(Key(67, false)), k.release(KeyEvent.KEYCODE_C))
        // Ctrl+V next, Ctrl still held: not pressed twice. / Ctrl+V después, con Ctrl aún pulsada: no se pulsa dos veces.
        assertEquals(listOf(Key(86, true)), k.press(KeyEvent.KEYCODE_V, 'v'.code, 0))
        k.release(KeyEvent.KEYCODE_V)
        assertEquals(listOf(Key(17, false)), k.release(KeyEvent.KEYCODE_CTRL_LEFT))
    }

    @Test fun `Shift with an arrow selects, and a held arrow repeats without pressing Shift again`() {
        k.press(KeyEvent.KEYCODE_SHIFT_RIGHT, 0, 0)
        assertEquals(listOf(Key(16, true), Key(39, true, true)), k.press(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 0))
        assertEquals(listOf(Key(39, true, true)), k.press(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 1))
        assertEquals(listOf(Key(39, false, true)), k.release(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf(Key(16, false)), k.release(KeyEvent.KEYCODE_SHIFT_RIGHT))
    }

    @Test fun `Enter, Tab, Space and Backspace are keys though they write on the phone`() {
        for ((code, char, vk) in listOf(Triple(KeyEvent.KEYCODE_ENTER, '\n'.code, 13), Triple(KeyEvent.KEYCODE_TAB, '\t'.code, 9),
            Triple(KeyEvent.KEYCODE_SPACE, ' '.code, 32), Triple(KeyEvent.KEYCODE_DEL, 0, 8), Triple(KeyEvent.KEYCODE_ESCAPE, 0, 27))) {
            assertEquals(listOf(Key(vk, true)), k.press(code, char, 0))
            assertEquals(listOf(Key(vk, false)), k.release(code))
        }
    }

    @Test fun `Windows or Alt alone go as they are, Ctrl alone sends nothing`() {
        k.press(KeyEvent.KEYCODE_META_LEFT, 0, 0)
        assertEquals(listOf(Key(91, true, true), Key(91, false, true)), k.release(KeyEvent.KEYCODE_META_LEFT))
        k.press(KeyEvent.KEYCODE_ALT_LEFT, 0, 0)
        assertEquals(listOf(Key(18, true), Key(18, false)), k.release(KeyEvent.KEYCODE_ALT_LEFT))
        k.press(KeyEvent.KEYCODE_CTRL_LEFT, 0, 0)
        assertEquals(emptyList<KeyOut>(), k.release(KeyEvent.KEYCODE_CTRL_LEFT))
        // Windows+E: the shortcut, not the Start menu after it. / Windows+E: el atajo, no el menú Inicio después.
        k.press(KeyEvent.KEYCODE_META_LEFT, 0, 0)
        assertEquals(listOf(Key(91, true, true), Key(69, true)), k.press(KeyEvent.KEYCODE_E, 'e'.code, 0))
        k.release(KeyEvent.KEYCODE_E)
        assertEquals(listOf(Key(91, false, true)), k.release(KeyEvent.KEYCODE_META_LEFT))
    }

    @Test fun `Ctrl held reaches the device before a click, once, and is released after`() {
        k.press(KeyEvent.KEYCODE_CTRL_LEFT, 0, 0)
        assertEquals(listOf(Key(17, true)), k.beforeClick())
        assertEquals(emptyList<KeyOut>(), k.beforeClick())
        assertEquals(listOf(Key(17, false)), k.release(KeyEvent.KEYCODE_CTRL_LEFT))
        // Without modifiers a click sends nothing more. / Sin modificadores un clic no manda nada más.
        assertEquals(emptyList<KeyOut>(), k.beforeClick())
        // Alt+click is not Alt alone afterwards. / Alt+clic no es luego Alt sola.
        k.press(KeyEvent.KEYCODE_ALT_LEFT, 0, 0)
        assertEquals(listOf(Key(18, true)), k.beforeClick())
        assertEquals(listOf(Key(18, false)), k.release(KeyEvent.KEYCODE_ALT_LEFT))
    }

    @Test fun `losing the window releases on the device everything held, and nothing after`() {
        k.press(KeyEvent.KEYCODE_CTRL_LEFT, 0, 0)
        k.press(KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0)
        k.press(KeyEvent.KEYCODE_DPAD_LEFT, 0, 0)
        assertEquals(setOf(Key(37, false, true), Key(17, false), Key(16, false)), k.releaseAll().toSet())
        assertEquals(emptyList<KeyOut>(), k.releaseAll())
        // The keys released later on the phone send nothing. / Las teclas soltadas luego en el móvil no mandan nada.
        assertNull(k.release(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(emptyList<KeyOut>(), k.release(KeyEvent.KEYCODE_CTRL_LEFT))
    }

    @Test fun `keys that are not for the device are left to Android`() {
        assertNull(k.press(KeyEvent.KEYCODE_VOLUME_UP, 0, 0))
        assertNull(k.press(KeyEvent.KEYCODE_BACK, 0, 0))
        assertNull(k.release(KeyEvent.KEYCODE_BACK))
        // Caps Lock is the phone's: taken, nothing sent. / Bloq Mayús es del móvil: se toma y no se manda nada.
        assertEquals(emptyList<KeyOut>(), k.press(KeyEvent.KEYCODE_CAPS_LOCK, 0, 0))
        assertEquals(emptyList<KeyOut>(), k.release(KeyEvent.KEYCODE_CAPS_LOCK))
    }

    @Test fun `every function key and the numbers have their Windows key`() {
        for (i in 0..11) assertEquals(112 + i to false, PhysicalKeyboard.VIRTUAL_KEYS[KeyEvent.KEYCODE_F1 + i])
        for (i in 0..9) assertEquals(48 + i to false, PhysicalKeyboard.VIRTUAL_KEYS[KeyEvent.KEYCODE_0 + i])
        assertEquals(90 to false, PhysicalKeyboard.VIRTUAL_KEYS[KeyEvent.KEYCODE_Z])
    }
}
