package io.github.zerlok.agentsessionrelay.ui

import io.github.zerlok.agentsessionrelay.domain.RelayEnvContract
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests of the terminal launch line the host runs (task 4.2) — the composition + shell quoting,
 * exercised without a display (the platform terminal open stays behind the seam).
 */
class AgentTerminalCommandTest {

    @Test
    fun `an empty env returns the command unchanged`() {
        assertEquals("claude", AgentTerminalCommand.compose(emptyMap(), "claude"))
    }

    @Test
    fun `each env var is exported in order before the command`() {
        val env = linkedMapOf("A" to "1", "B" to "2")
        assertEquals("export A='1'; export B='2'; run", AgentTerminalCommand.compose(env, "run"))
    }

    @Test
    fun `the four contract vars are exported ahead of the command`() {
        val env = RelayEnvContract(url = "http://127.0.0.1:63342", id = "abc", port = 63342, projectDir = "/p")
            .asEnvMap()
        assertEquals(
            "export AGENT_SESSION_RELAY_URL='http://127.0.0.1:63342'; " +
                "export AGENT_SESSION_RELAY_ID='abc'; " +
                "export AGENT_SESSION_RELAY_PORT='63342'; " +
                "export AGENT_SESSION_RELAY_PROJECT_DIR='/p'; " +
                "claude --hook",
            AgentTerminalCommand.compose(env, "claude --hook"),
        )
    }

    @Test
    fun `values with shell metacharacters are single-quoted intact`() {
        val env = linkedMapOf("DIR" to "/a b/c\$d")
        assertEquals("export DIR='/a b/c\$d'; ls", AgentTerminalCommand.compose(env, "ls"))
    }

    @Test
    fun `an embedded single quote is escaped`() {
        val env = linkedMapOf("K" to "a'b")
        assertEquals("export K='a'\\''b'; run", AgentTerminalCommand.compose(env, "run"))
    }
}
