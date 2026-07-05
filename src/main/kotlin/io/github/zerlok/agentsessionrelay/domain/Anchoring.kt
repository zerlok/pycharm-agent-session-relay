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
}
