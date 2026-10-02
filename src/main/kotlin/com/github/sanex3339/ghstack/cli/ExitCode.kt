package com.github.sanex3339.ghstack.cli

/** Exit codes documented by `gh stack` (reference page "Stacked pull requests CLI commands"). */
enum class ExitCode(val code: Int) {
    OK(0),
    GENERIC(1),
    NOT_IN_STACK(2),
    CONFLICT(3),
    API_FAILURE(4),
    INVALID_ARGS(5),
    DISAMBIGUATE(6),
    REBASE_ACTIVE(7),
    LOCKED(8),
    STACKS_UNAVAILABLE(9),
    MODIFY_RECOVERY(10),
    UNKNOWN(-1);

    companion object {
        fun fromInt(code: Int): ExitCode = entries.firstOrNull { it != UNKNOWN && it.code == code } ?: UNKNOWN
    }
}
