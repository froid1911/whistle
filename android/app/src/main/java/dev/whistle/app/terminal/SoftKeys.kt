package dev.whistle.app.terminal

/**
 * The soft-key row and its mapping to the bytes a terminal (xterm) expects. Pure Kotlin.
 *
 * Modifiers follow xterm: Ctrl turns a char into its C0 control code; Alt prefixes ESC; cursor and
 * paging keys carry modifiers as the CSI parameter `1 + (alt ? 2 : 0) + (ctrl ? 4 : 0)`.
 */
object SoftKeys {
    enum class Key(val label: String) {
        Esc("Esc"), Tab("Tab"),
        Up("↑"), Down("↓"), Left("←"), Right("→"),
        PgUp("PgUp"), PgDn("PgDn"),
        Pipe("|"), Tilde("~"), Slash("/"),
    }

    private const val ESC = "\u001b"

    /** Bytes for [key]; [appCursor] = the terminal's DECCKM (application cursor keys) mode. */
    fun encode(key: Key, ctrl: Boolean, alt: Boolean, appCursor: Boolean = false): String {
        val mod = 1 + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        fun cursor(c: Char) = when {
            mod > 1 -> "$ESC[1;$mod$c"
            appCursor -> "${ESC}O$c"
            else -> "$ESC[$c"
        }
        fun tilde(n: Int) = if (mod > 1) "$ESC[$n;$mod~" else "$ESC[$n~"
        return when (key) {
            Key.Up -> cursor('A')
            Key.Down -> cursor('B')
            Key.Right -> cursor('C')
            Key.Left -> cursor('D')
            Key.PgUp -> tilde(5)
            Key.PgDn -> tilde(6)
            Key.Esc -> if (alt) ESC + ESC else ESC
            Key.Tab -> (if (alt) ESC else "") + (if (ctrl) "$ESC[Z" else "\t")
            Key.Pipe -> encode('|', ctrl, alt)
            Key.Tilde -> encode('~', ctrl, alt)
            Key.Slash -> encode('/', ctrl, alt)
        }
    }

    /** Bytes for a typed char with sticky modifiers; chars without a control form keep Ctrl off. */
    fun encode(c: Char, ctrl: Boolean, alt: Boolean): String {
        val base = if (ctrl) control(c)?.toString() ?: c.toString() else c.toString()
        return if (alt) ESC + base else base
    }

    /** Applies sticky modifiers to text typed on the keyboard: only a single char is modified. */
    fun applyModifiers(text: String, ctrl: Boolean, alt: Boolean): String =
        if (text.length == 1 && (ctrl || alt)) encode(text[0], ctrl, alt) else text

    private fun control(c: Char): Char? = when (c) {
        in 'a'..'z' -> (c - 'a' + 1).toChar()
        in '@'..'_' -> (c.code - 0x40).toChar() // @ A-Z [ \ ] ^ _
        ' ', '2' -> '\u0000'
        '/' -> '\u001f'
        '?', '8' -> '\u007f'
        '3' -> '\u001b'
        '4' -> '\u001c'
        '5' -> '\u001d'
        '6' -> '\u001e'
        '7' -> '\u001f'
        else -> null
    }
}
