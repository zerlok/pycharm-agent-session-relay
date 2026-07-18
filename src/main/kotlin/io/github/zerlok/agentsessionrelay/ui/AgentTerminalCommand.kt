package io.github.zerlok.agentsessionrelay.ui

/**
 * The **pure** shell line the Agent Sessions terminal host runs when it opens a session's terminal (task
 * 4.2): the four `AGENT_SESSION_RELAY_*` env vars exported, then the already-placeholder-substituted
 * command. Split from [AgentSessionsTerminalHostImpl] so the composition + quoting is unit-testable
 * without a display (the platform terminal open stays behind the seam).
 *
 * The plugin ships nothing runnable of its own here (design D8/D10): [command] is verbatim the user's
 * config command and [env] is only [io.github.zerlok.agentsessionrelay.domain.RelayEnvContract.asEnvMap].
 */
object AgentTerminalCommand {

    /**
     * Builds `export K='V'; …; <command>` for a POSIX shell, preserving [env] iteration order (the
     * contract map is ordered) and single-quoting each value so URLs, paths and ids with shell
     * metacharacters cross intact. With an empty [env] the command is returned unchanged.
     */
    fun compose(env: Map<String, String>, command: String): String {
        if (env.isEmpty()) return command
        val exports = env.entries.joinToString(separator = "; ") { (name, value) ->
            "export $name=${singleQuote(value)}"
        }
        return "$exports; $command"
    }

    /** POSIX single-quote a value, escaping embedded single quotes via the `'\''` idiom. */
    private fun singleQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
