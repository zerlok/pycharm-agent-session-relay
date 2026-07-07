package io.github.zerlok.agentsessionrelay.ui

import com.intellij.util.messages.Topic

/**
 * A presentation-only seam (design D3): [CommentDraftController] announces when the
 * currently-editing comment changes (an edit box opened or closed) so each [EditorReviewOverlay]
 * reconciles its read-only cards — suppressing the edited comment's card while its box is open and
 * restoring it on close. This carries no domain data; the overlay re-reads the editing id from the
 * controller, exactly as it re-queries the store on a `ReviewBatchListener` event.
 *
 * Fires on the EDT (ARCHITECTURE §5.3), like the store events it rides alongside.
 */
interface CommentEditingListener {

    fun editingChanged() {}

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<CommentEditingListener> =
            Topic.create("Relay comment editing", CommentEditingListener::class.java)
    }
}
