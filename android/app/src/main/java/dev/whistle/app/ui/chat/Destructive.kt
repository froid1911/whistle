package dev.whistle.app.ui.chat

/**
 * Spots commands in an approval prompt that can destroy data or hand the machine to someone else,
 * so the card can say so before the user answers. A warning only (false positives are cheap);
 * nothing is blocked.
 */
object DestructiveCommands {
    private val RM = Regex("""\brm((?:\s+-{1,2}[A-Za-z-]+)+)""")
    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "git push --force" to Regex("""\bgit\s+push\b[^\n]*?(?:\s--force(?:-with-lease)?\b|\s-[A-Za-z]*f[A-Za-z]*\b|\s\+[\w./-]+)"""),
        "git push --delete" to Regex("""\bgit\s+push\b[^\n]*?(?:\s--delete\b|\s-d\b|\s:[\w./-]+)"""),
        "git reset --hard" to Regex("""\bgit\s+reset\b[^\n]*\s--hard\b"""),
        "git clean -f" to Regex("""\bgit\s+clean\b[^\n]*?\s(?:-[A-Za-z]*f[A-Za-z]*|--force)\b"""),
        "DROP" to Regex("""(?i)\bdrop\s+(?:table|database|schema|index|view|collection|user|role)\b|\bdropdb\b"""),
        "TRUNCATE" to Regex("""(?i)\btruncate\s+(?:table\s+)?\w"""),
        "DELETE without WHERE" to Regex("""(?i)\bdelete\s+from\s+[\w."`\[\]]+\s*(?:;|'|"|$)""", RegexOption.MULTILINE),
        "find -delete" to Regex("""\bfind\b[^\n]*?(?:\s-delete\b|\s-exec(?:dir)?\s+(?:sudo\s+)?rm\b)"""),
        "mkfs" to Regex("""\bmkfs(?:\.\w+)?\b"""),
        "dd" to Regex("""\bdd\b[^\n]*\b(?:if|of)="""),
        "> /dev/sd*" to Regex(""">\s*/dev/(?:sd[a-z]|nvme\d|mmcblk\d|hd[a-z]|vd[a-z]|disk\d)"""),
        "shred" to Regex("""\bshred\b"""),
        "chmod/chown -R" to Regex("""\bch(?:mod|own|grp)\b[^\n]*?\s(?:-[A-Za-z]*R[A-Za-z]*|--recursive)\b[^\n]*?\s(?:/|~|~/|\${'$'}HOME/?)(?=\s|$|;|&|\|)""", RegexOption.MULTILINE),
        "curl | sh" to Regex(
            """\b(?:curl|wget)\b[^\n|]*\|\s*(?:sudo\s+)?(?:(?:ba|z|da|k)?sh|python[\d.]*|perl|ruby|node|php)\b""" +
                """|\b(?:ba|z)?sh\b[^\n]*\$\(\s*(?:curl|wget)\b|\b(?:ba|z)?sh\s+<\(\s*(?:curl|wget)\b""",
        ),
        "kubectl delete" to Regex("""\bkubectl\b[^\n]*\sdelete\b"""),
        "terraform destroy" to Regex("""\b(?:terraform|tofu)\b[^\n]*\sdestroy\b"""),
        "docker system prune" to Regex("""\bdocker\s+(?:system|volume|image|container|network|builder)\s+prune\b"""),
        "fork bomb" to Regex("""(\w+|:)\s*\(\)\s*\{\s*\1\s*\|\s*\1\s*&\s*}\s*;\s*\1"""),
        "sudo" to Regex("""\bsudo\b"""),
    )

    /** Labels of the dangerous patterns in [text], in a fixed order; empty = nothing found. */
    fun find(text: String): List<String> {
        val out = mutableListOf<String>()
        rmLabel(text)?.let { out += it }
        PATTERNS.forEach { (label, re) -> if (re.containsMatchIn(text)) out += label }
        return out
    }

    /** `rm` with a recursive flag (-r, -R, -rf, --recursive …): "rm -rf" when also forced. */
    private fun rmLabel(text: String): String? {
        for (m in RM.findAll(text)) {
            val flags = m.groupValues[1].trim().split(Regex("""\s+"""))
            val short = flags.filter { !it.startsWith("--") }.joinToString("") { it.drop(1) }
            val recursive = 'r' in short || 'R' in short || "--recursive" in flags
            val force = 'f' in short || "--force" in flags
            if (recursive) return if (force) "rm -rf" else "rm -r"
        }
        return null
    }

    /** The ember line above the answers, or null. */
    fun warning(text: String): String? =
        find(text).takeIf { it.isNotEmpty() }?.let { "Destructive command: ${it.joinToString(", ")} — read it before answering" }
}
