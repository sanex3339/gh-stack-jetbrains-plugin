package com.github.sanex3339.ghstack.cli

/** POSIX shell quoting for commands shown in the console or sent to a terminal tab. */
object ShellQuote {
    private val safe = Regex("""[A-Za-z0-9_./:@%+=,-]+""")

    fun quote(arg: String): String = if (safe.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"
}
