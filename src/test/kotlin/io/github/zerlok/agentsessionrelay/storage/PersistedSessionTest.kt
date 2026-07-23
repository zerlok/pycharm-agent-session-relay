package io.github.zerlok.agentsessionrelay.storage

import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.NeedsInputKind
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the [AgentSession] ↔ [PersistedSession] mapper (task 1.4). Pure Kotlin — a
 * storage-boundary translation over inert records (ARCHITECTURE §3.1). Two concerns:
 *  - registration metadata (incl. `lastEventAt`) survives the round-trip;
 *  - the live [SessionState] is **not** persisted — every restore comes back [SessionState.Unknown],
 *    while `lastEventAt` is preserved so the entry can show its last-event time (design D6).
 */
class PersistedSessionTest {

    private fun session(
        id: String = "s1",
        environment: AgentEnvironment = AgentEnvironment.LOCAL,
        capabilities: SessionCapabilities = SessionCapabilities(),
        state: SessionState = SessionState.Registered,
        lastEventAt: Long? = null,
    ) = AgentSession(
        id = SessionId(id),
        agentLabel = "Claude Code",
        environment = environment,
        projectBasePath = "/home/me/proj",
        startScriptRef = "local-claude",
        capabilities = capabilities,
        state = state,
        lastEventAt = lastEventAt,
    )

    @Test
    fun `registration metadata survives the round-trip`() {
        val original = session(
            environment = AgentEnvironment.DOCKER,
            capabilities = SessionCapabilities(turnStarted = true, needsInput = true),
            lastEventAt = 1_700_000_000_000L,
        )

        val restored = original.toPersisted().toDomain()

        assertEquals(original.id, restored.id)
        assertEquals("Claude Code", restored.agentLabel)
        assertEquals(AgentEnvironment.DOCKER, restored.environment)
        assertEquals("/home/me/proj", restored.projectBasePath)
        assertEquals("local-claude", restored.startScriptRef)
        assertEquals(SessionCapabilities(turnStarted = true, needsInput = true), restored.capabilities)
        assertEquals(1_700_000_000_000L, restored.lastEventAt)
    }

    @Test
    fun `live state is dropped but lastEventAt is kept - restore comes back unknown with its time`() {
        val working = session(state = SessionState.Working, lastEventAt = 123_456L)

        val restored = working.toPersisted().toDomain()

        assertEquals(SessionState.Unknown, restored.state)
        assertEquals(123_456L, restored.lastEventAt)
    }

    @Test
    fun `needs-input state is not persisted either`() {
        val needsInput = session(state = SessionState.NeedsInput(NeedsInputKind.PERMISSION), lastEventAt = 9L)

        val restored = needsInput.toPersisted().toDomain()
        assertEquals(SessionState.Unknown, restored.state)
        assertEquals(9L, restored.lastEventAt)
    }

    @Test
    fun `a null lastEventAt round-trips as null`() {
        assertEquals(null, session(lastEventAt = null).toPersisted().toDomain().lastEventAt)
    }

    @Test
    fun `every environment round-trips`() {
        for (env in AgentEnvironment.entries) {
            assertEquals(env, session(environment = env).toPersisted().toDomain().environment)
        }
    }

    @Test
    fun `an unknown environment falls back to custom without throwing`() {
        val dto = PersistedSession().apply {
            id = "x"
            environment = "TOTALLY_NEW_ENV"
        }

        assertEquals(AgentEnvironment.CUSTOM, dto.toDomain().environment)
    }

    @Test
    fun `a bare DTO with only defaults loads as an unknown-state session`() {
        val loaded = PersistedSession().toDomain()

        assertEquals(SessionId(""), loaded.id)
        assertEquals(AgentEnvironment.LOCAL, loaded.environment) // default bean value is a valid name
        assertEquals(SessionState.Unknown, loaded.state)
        assertEquals(null, loaded.lastEventAt)
        assertEquals(SessionCapabilities(), loaded.capabilities)
    }
}
