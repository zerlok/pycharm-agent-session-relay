package io.github.zerlok.agentsessionrelay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Anchoring.contextHash] (task 8.1). Pure — the re-anchoring seed must be stable
 * across JVMs and runs (ARCHITECTURE §5.2), so it is deterministic and format-checked here.
 */
class AnchoringTest {

    @Test
    fun `same input yields the same hash`() {
        val ctx = "def foo():\n    return 42\n"

        assertEquals(Anchoring.contextHash(ctx), Anchoring.contextHash(ctx))
    }

    @Test
    fun `different input yields a different hash`() {
        assertNotEquals(Anchoring.contextHash("alpha"), Anchoring.contextHash("beta"))
    }

    @Test
    fun `a one-character difference changes the hash`() {
        assertNotEquals(Anchoring.contextHash("line a"), Anchoring.contextHash("line b"))
    }

    @Test
    fun `the hash is 16 lowercase hex characters`() {
        val hash = Anchoring.contextHash("anything at all")

        // SHA-256 truncated to 8 bytes -> 16 hex chars (KDoc contract).
        assertEquals(16, hash.length)
        assertTrue("expected lowercase hex, got '$hash'", hash.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `the empty string hashes to a stable known value`() {
        // First 8 bytes of SHA-256("") == e3b0c44298fc1c14. Pins the algorithm + truncation choice.
        assertEquals("e3b0c44298fc1c14", Anchoring.contextHash(""))
    }

    @Test
    fun `a known non-empty input hashes to its documented prefix`() {
        // First 8 bytes of SHA-256("abc") == ba7816bf8f01cfea. Pins UTF-8 encoding + truncation.
        assertEquals("ba7816bf8f01cfea", Anchoring.contextHash("abc"))
    }

    @Test
    fun `hashing is order-sensitive across the context window`() {
        assertNotEquals(Anchoring.contextHash("ab"), Anchoring.contextHash("ba"))
    }
}
