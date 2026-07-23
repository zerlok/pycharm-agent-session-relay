package io.github.zerlok.agentsessionrelay.domain

/**
 * The env-var names of the launch contract (design D2) — the **single** canonical source, referenced by
 * the placeholder substitution here, the launch service's exported-env map (task 3.2), and
 * `docs/ADAPTERS.md` (user rule: no restating). The `AGENT_SESSION_RELAY_` prefix is deliberately long to
 * avoid collisions with any other tooling's `RELAY_*` variables (design D2).
 */
object RelayEnvVars {
    /** Base gateway URL reachable locally (`http://127.0.0.1:<port>`, design D2). */
    const val URL = "AGENT_SESSION_RELAY_URL"

    /** Opaque per-session id — the sole route key (design D5), not a secret (design D8). */
    const val ID = "AGENT_SESSION_RELAY_ID"

    /** IDE loopback port, so the connection tool can build the `ssh -R` reverse tunnel (design D2). */
    const val PORT = "AGENT_SESSION_RELAY_PORT"

    /** The launching project's base path (for `-v ${...}:/project` etc., design D2). */
    const val PROJECT_DIR = "AGENT_SESSION_RELAY_PROJECT_DIR"
}

/**
 * The concrete values of the launch env contract for one session (design D2) — pure, platform-free data
 * so both the exported-env map and the `${AGENT_SESSION_RELAY_*}` placeholder substitution are unit-testable
 * without the IDE. The launch service (task 3.2) builds this from the resolved gateway endpoint + the minted
 * session id + the launching project's base path, then feeds [asEnvMap] into the terminal and [substitute]
 * over the config's command.
 *
 * Registrations and events never carry anything runnable (design D8/D10): this contract is the *only*
 * Relay-originated data that crosses into the agent's environment (agent-environments spec: "Only the env
 * contract crosses to the agent").
 */
data class RelayEnvContract(
    val url: String,
    val id: String,
    val port: Int,
    val projectDir: String,
) {
    /**
     * The four env vars exactly as exported into the launched terminal (agent-environments spec: the only
     * Relay-originated values the command receives). Keyed by the [RelayEnvVars] names; no other variable is
     * ever added, so nothing else leaks to the agent (design D8).
     */
    fun asEnvMap(): Map<String, String> = linkedMapOf(
        RelayEnvVars.URL to url,
        RelayEnvVars.ID to id,
        RelayEnvVars.PORT to port.toString(),
        RelayEnvVars.PROJECT_DIR to projectDir,
    )

    /**
     * Replaces every `${AGENT_SESSION_RELAY_*}` placeholder in [command] with its contract value (design
     * D2), leaving all other text — including unrelated `${...}` the shell will expand — untouched. Pure:
     * literal (non-regex) replacement of each of the four names, so it is deterministic and fixture-free
     * testable. A name that never appears is simply a no-op.
     */
    fun substitute(command: String): String {
        var result = command
        for ((name, value) in asEnvMap()) {
            result = result.replace("\${$name}", value)
        }
        return result
    }
}
