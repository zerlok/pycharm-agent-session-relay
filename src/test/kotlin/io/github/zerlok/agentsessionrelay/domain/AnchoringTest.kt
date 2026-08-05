package io.github.zerlok.agentsessionrelay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure anchoring helpers (ARCHITECTURE §5.2): [Anchoring.contextHash], whose seed
 * must be stable across JVMs and runs (so it is deterministic and format-checked here), and
 * [Anchoring.matches], the anchor check the export sync point runs.
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

    // -- matches(): the tier-2 anchor check (design D1) --

    @Test
    fun `identical anchor text matches`() {
        val text = "def foo():\n    return 42"

        assertTrue(Anchoring.matches(text, text))
    }

    @Test
    fun `a trailing-whitespace-only difference still matches`() {
        // Strip-trailing-whitespace-on-save changes bytes without changing what the lines mean.
        assertTrue(Anchoring.matches("def foo():   \n    return 42\t", "def foo():\n    return 42"))
    }

    @Test
    fun `a CRLF-only difference still matches`() {
        assertTrue(Anchoring.matches("def foo():\r\n    return 42", "def foo():\n    return 42"))
    }

    @Test
    fun `a changed word does not match`() {
        assertFalse(Anchoring.matches("    return 42", "    return 43"))
    }

    @Test
    fun `leading indentation is significant`() {
        // Only TRAILING whitespace is normalized: a re-indent is a real change to the referenced lines.
        assertFalse(Anchoring.matches("    return 42", "        return 42"))
    }

    @Test
    fun `a null recorded anchor matches anything`() {
        // "Nothing to verify" is not evidence of drift (design D6).
        assertTrue(Anchoring.matches(null, "whatever is there now"))
    }

    @Test
    fun `an empty recorded anchor is not the same as a null one`() {
        assertFalse(Anchoring.matches("", "still here"))
        assertTrue(Anchoring.matches("", ""))
    }
}
