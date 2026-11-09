package dev.whistle.app.terminal

import dev.whistle.app.terminal.SoftKeys.Key
import org.junit.Assert.assertEquals
import org.junit.Test

class SoftKeysTest {
    private val esc = "\u001b"

    @Test fun ctrlCEncodes0x03() {
        assertEquals("\u0003", SoftKeys.encode('c', ctrl = true, alt = false))
        assertEquals("\u0003", SoftKeys.encode('C', ctrl = true, alt = false))
    }

    @Test fun ctrlSpecialChars() {
        assertEquals("\u0000", SoftKeys.encode(' ', ctrl = true, alt = false))
        assertEquals(esc, SoftKeys.encode('[', ctrl = true, alt = false))
        assertEquals("\u001c", SoftKeys.encode('\\', ctrl = true, alt = false))
        assertEquals("\u001f", SoftKeys.encode('_', ctrl = true, alt = false))
        assertEquals("\u007f", SoftKeys.encode('?', ctrl = true, alt = false))
        assertEquals("1", SoftKeys.encode('1', ctrl = true, alt = false)) // no control form: unchanged
    }

    @Test fun arrowUpEncodes() {
        assertEquals("$esc[A", SoftKeys.encode(Key.Up, ctrl = false, alt = false))
        assertEquals("${esc}OA", SoftKeys.encode(Key.Up, ctrl = false, alt = false, appCursor = true))
        assertEquals("$esc[1;5A", SoftKeys.encode(Key.Up, ctrl = true, alt = false))
        assertEquals("$esc[1;5A", SoftKeys.encode(Key.Up, ctrl = true, alt = false, appCursor = true))
        assertEquals("$esc[D", SoftKeys.encode(Key.Left, ctrl = false, alt = false))
        assertEquals("$esc[1;7C", SoftKeys.encode(Key.Right, ctrl = true, alt = true))
    }

    @Test fun altPrefixesEsc() {
        assertEquals("${esc}x", SoftKeys.encode('x', ctrl = false, alt = true))
        assertEquals("$esc\u0003", SoftKeys.encode('c', ctrl = true, alt = true))
        assertEquals("$esc|", SoftKeys.encode(Key.Pipe, ctrl = false, alt = true))
        assertEquals("$esc[1;3B", SoftKeys.encode(Key.Down, ctrl = false, alt = true))
        assertEquals("$esc$esc", SoftKeys.encode(Key.Esc, ctrl = false, alt = true))
    }

    @Test fun pgUpEncodes() {
        assertEquals("$esc[5~", SoftKeys.encode(Key.PgUp, ctrl = false, alt = false))
        assertEquals("$esc[6~", SoftKeys.encode(Key.PgDn, ctrl = false, alt = false))
        assertEquals("$esc[5;5~", SoftKeys.encode(Key.PgUp, ctrl = true, alt = false))
    }

    @Test fun plainKeys() {
        assertEquals(esc, SoftKeys.encode(Key.Esc, ctrl = false, alt = false))
        assertEquals("\t", SoftKeys.encode(Key.Tab, ctrl = false, alt = false))
        assertEquals("$esc[Z", SoftKeys.encode(Key.Tab, ctrl = true, alt = false)) // ctrl-tab → back-tab
        assertEquals("|", SoftKeys.encode(Key.Pipe, ctrl = false, alt = false))
        assertEquals("~", SoftKeys.encode(Key.Tilde, ctrl = false, alt = false))
        assertEquals("/", SoftKeys.encode(Key.Slash, ctrl = false, alt = false))
        assertEquals("\u001f", SoftKeys.encode(Key.Slash, ctrl = true, alt = false)) // ctrl-/ = ^_
    }

    @Test fun typedTextGetsStickyModifiersOnlyWhenSingleChar() {
        assertEquals("\u0004", SoftKeys.applyModifiers("d", ctrl = true, alt = false))
        assertEquals("${esc}b", SoftKeys.applyModifiers("b", ctrl = false, alt = true))
        assertEquals("hello", SoftKeys.applyModifiers("hello", ctrl = false, alt = false))
        // A multi-char chunk (paste, IME word) is sent as is.
        assertEquals("ab", SoftKeys.applyModifiers("ab", ctrl = false, alt = true))
        assertEquals("ab", SoftKeys.applyModifiers("ab", ctrl = true, alt = false))
    }
}
