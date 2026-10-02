package com.github.sanex3339.ghstack.cli

/** Quoting for commands shown in the console or typed into a terminal tab. */
object ShellQuote {
    private val safe = Regex("""[A-Za-z0-9_./:@%+=,-]+""")

    /** POSIX shells (bash, zsh, fish). */
    fun quote(arg: String): String = if (safe.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"

    /** PowerShell, the default terminal shell on Windows: single quotes escape by doubling. */
    fun quotePowerShell(arg: String): String = if (safe.matches(arg)) arg else "'" + arg.replace("'", "''") + "'"
}
