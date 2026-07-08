package io.github.zerlok.agentsessionrelay.storage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.util.xmlb.annotations.XCollection
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.ReviewComment

/**
 * The durable backing for the pending review batch (design D1–D4): a [ReviewBatchStorage] whose
 * records also persist via [PersistentStateComponent].
 *
 * Storage config is read only from `@State`: a bare `@Storage` on the class is inert (valid only
 * nested in `@State.storages`), so without `@State` nothing would ever be written. It lives in
 * `workspace.xml` because the batch is a private, per-user draft, not a VCS artifact.
 *
 * The logic layer obtains this as a service, never `new`-ing it: `loadState`/`getState` fire only when
 * the platform owns the instance ([InMemoryReviewBatchStorage] remains the constructible test backing).
 *
 * `getStateRequiresEdt = true` pins the otherwise-background [getState] to the EDT, where all batch
 * mutations already run (ARCHITECTURE §5.3), so a save can never iterate [comments] mid-mutation.
 *
 * A runtime reload of `workspace.xml` (external edit) re-runs [loadState] with no listener event, so
 * open views refresh only on the next store change — accepted: the target is restore-on-restart.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "RelayReviewBatch",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
    getStateRequiresEdt = true,
)
class PersistentReviewBatchStorage :
    ReviewBatchStorage,
    PersistentStateComponent<PersistentReviewBatchStorage.State> {

    class State {
        @XCollection(style = XCollection.Style.v2)
        var comments: MutableList<PersistedComment> = mutableListOf()
    }

    // LinkedHashMap: all() must return comments in authored (insertion) order.
    private val comments = LinkedHashMap<CommentId, ReviewComment>()

    // -- PersistentStateComponent --

    override fun getState(): State = State().also { state ->
        state.comments = comments.values.mapTo(mutableListOf()) { it.toPersisted() }
    }

    override fun loadState(state: State) {
        // No file resolution or re-anchoring here: loadState runs pre-index (design D3), and degenerate
        // DTOs fall back safely in toDomain rather than aborting the whole restore.
        comments.clear()
        for (dto in state.comments) {
            val comment = dto.toDomain()
            comments[comment.id] = comment
        }
    }

    // -- ReviewBatchStorage CRUD (semantics identical to InMemoryReviewBatchStorage) --

    override fun all(): List<ReviewComment> = comments.values.toList()

    override fun get(id: CommentId): ReviewComment? = comments[id]

    override fun add(comment: ReviewComment) {
        comments[comment.id] = comment
    }

    override fun update(comment: ReviewComment) {
        if (comments.containsKey(comment.id)) comments[comment.id] = comment
    }

    override fun remove(id: CommentId): ReviewComment? = comments.remove(id)

    override fun clear() = comments.clear()
}
