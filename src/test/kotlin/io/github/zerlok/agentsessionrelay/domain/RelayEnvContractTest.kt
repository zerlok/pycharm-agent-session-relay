package io.github.zerlok.agentsessionrelay.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [RelayEnvContract] (task 3.1) — the pure env-var map + `${AGENT_SESSION_RELAY_*}` placeholder
 * substitution, fixture-free. Covers the agent-environments scenarios "Docker config references the project
 * dir" (PROJECT_DIR substitution) and "Only the env contract crosses to the agent" (exactly the four vars).
 */
class RelayEnvContractTest {

    private val contract = RelayEnvContract(
        url = "http://127.0.0.1:63342",
        id = "session-42",
        port = 63342,
        projectDir = "/home/me/proj",
    )

    @Test
    fun `env map is exactly the four contract vars`() {
        assertEquals(
            mapOf(
                "AGENT_SESSION_RELAY_URL" to "http://127.0.0.1:63342",
                "AGENT_SESSION_RELAY_ID" to "session-42",
                "AGENT_SESSION_RELAY_PORT" to "63342",
                "AGENT_SESSION_RELAY_PROJECT_DIR" to "/home/me/proj",
            ),
            contract.asEnvMap(),
        )
    }

    @Test
    fun `env map keys use the canonical RelayEnvVars names`() {
        val keys = contract.asEnvMap().keys
        assertEquals(setOf(RelayEnvVars.URL, RelayEnvVars.ID, RelayEnvVars.PORT, RelayEnvVars.PROJECT_DIR), keys)
    }

    @Test
    fun `substitutes the URL placeholder`() {
        assertEquals(
            "curl -sf -m 5 -X POST http://127.0.0.1:63342/relay/v1/sessions/session-42/events/turn.completed",
            contract.substitute("curl -sf -m 5 -X POST \${AGENT_SESSION_RELAY_URL}/relay/v1/sessions/\${AGENT_SESSION_RELAY_ID}/events/turn.completed"),
        )
    }

    @Test
    fun `substitutes the PROJECT_DIR placeholder in a docker command`() {
        // agent-environments spec: "Docker config references the project dir".
        assertEquals(
            "docker run -it --rm -v /home/me/proj:/project img",
            contract.substitute("docker run -it --rm -v \${AGENT_SESSION_RELAY_PROJECT_DIR}:/project img"),
        )
    }

    @Test
    fun `substitutes the PORT placeholder as a string`() {
        assertEquals("ssh -R 63342:localhost:63342 host", contract.substitute("ssh -R \${AGENT_SESSION_RELAY_PORT}:localhost:\${AGENT_SESSION_RELAY_PORT} host"))
    }

    @Test
    fun `substitutes every placeholder in one command`() {
        val command = "URL=\${AGENT_SESSION_RELAY_URL} ID=\${AGENT_SESSION_RELAY_ID} PORT=\${AGENT_SESSION_RELAY_PORT} DIR=\${AGENT_SESSION_RELAY_PROJECT_DIR}"
        assertEquals(
            "URL=http://127.0.0.1:63342 ID=session-42 PORT=63342 DIR=/home/me/proj",
            contract.substitute(command),
        )
    }

    @Test
    fun `a command with no placeholders is returned unchanged`() {
        assertEquals("claude", contract.substitute("claude"))
    }

    @Test
    fun `unrelated dollar-brace text is left untouched`() {
        // The plugin substitutes only the four names; the shell expands anything else at run time.
        assertEquals("echo \${HOME} and http://127.0.0.1:63342", contract.substitute("echo \${HOME} and \${AGENT_SESSION_RELAY_URL}"))
    }

    @Test
    fun `a repeated placeholder is substituted at every occurrence`() {
        assertEquals("session-42 session-42", contract.substitute("\${AGENT_SESSION_RELAY_ID} \${AGENT_SESSION_RELAY_ID}"))
    }
}
