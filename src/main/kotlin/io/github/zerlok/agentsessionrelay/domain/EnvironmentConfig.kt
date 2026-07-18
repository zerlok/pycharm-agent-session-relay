package io.github.zerlok.agentsessionrelay.domain

/**
 * A named **start-script config** (design D10, agent-environments spec) — inert, serializable-shaped data
 * (ARCHITECTURE §3.1/§3.2), edited in the Relay Settings page (task 3.3) and persisted app-level (task 3.1).
 *
 * A config is the **only** source of anything the plugin ever executes (design D8/D10): it is never derived
 * from, or mutated by, a registration or a received event. [command] is a literal shell command that MAY
 * reference the `${AGENT_SESSION_RELAY_*}` placeholders ([RelayEnvVars]), substituted by [RelayEnvContract]
 * at launch. [name] doubles as the human-facing agent label and the [AgentSession.startScriptRef] the
 * launched session records. [capabilities] is what the config *declares* the agent can report, kept honest
 * in rendering (design D11).
 */
data class EnvironmentConfig(
    val name: String,
    val command: String,
    val environment: AgentEnvironment = AgentEnvironment.LOCAL,
    val capabilities: SessionCapabilities = SessionCapabilities(),
)
