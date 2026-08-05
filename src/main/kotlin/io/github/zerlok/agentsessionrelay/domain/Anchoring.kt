package io.github.zerlok.agentsessionrelay.domain

import java.security.MessageDigest

/**
 * Pure helpers for the re-anchoring seeds a comment stores at author time (ARCHITECTURE §5.2).
 * No platform imports — callers extract the surrounding text from the document and pass strings in.
 */
object Anchoring {

    /**
     * Deterministic hash of the context window around a comment's anchor, used later to relocate the
     * comment after out-of-IDE edits. SHA-256 (stable across JVMs and runs, unlike [String.hashCode])
     * truncated to 16 hex chars — collision-safe enough for a re-anchoring seed.
     */
    fun contextHash(context: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(context.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Whether the text a comment was anchored to ([recorded]) still describes the text its live marker
     * spans now ([current]) — the tier-2 anchor check run at the export sync point (ARCHITECTURE §5.2,
     * design D1). Pure comparison: it never searches for [recorded] elsewhere and never moves anything.
     *
     * A null [recorded] means there is nothing to verify (a pre-anchoring record, or a subject with no
     * line anchor), which is *not* evidence of drift — see design D6, "unverifiable is not stale".
     *
     * Comparison is normalized for whitespace only: line endings are unified and trailing whitespace is
     * dropped per line. A strip-trailing-whitespace-on-save or a CRLF round trip changes bytes without
     * changing what the referenced lines mean, and flagging those would train the user to ignore the
     * flag. Nothing beyond whitespace is normalized, because every other difference can be semantic.
     */
    fun matches(recorded: String?, current: String): Boolean =
        recorded == null || normalize(recorded) == normalize(current)

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").lines().joinToString("\n") { it.trimEnd() }
}
