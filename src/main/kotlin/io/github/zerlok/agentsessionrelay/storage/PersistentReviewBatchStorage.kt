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
 * The durable backing for the pending review batch (design D1–D4). A [ReviewBatchStorage] whose
 * records also live on disk via [PersistentStateComponent]. [State] carries the storage config: the
 * platform reads it from the `@State` annotation (a bare `@Storage` on the class is inert — it is only
 * valid nested in `@State.storages`), persisting to `workspace.xml` ([StoragePathMacros.WORKSPACE_FILE])
 * — per-user, not committed to VCS. Registered by annotation as a project [Service]; the logic layer
 * obtains it with `project.service<PersistentReviewBatchStorage>()` (no `plugin.xml` `<service>` entry),
 * because `loadState`/`getState` are only driven when the platform owns the instance lifecycle — it
 * cannot be `new`-ed like [InMemoryReviewBatchStorage].
 *
 * `getStateRequiresEdt = true` because the batch is mutated only on the EDT (ARCHITECTURE §5.3); it
 * pins the platform's otherwise-background [getState] to the EDT too, so a save can never iterate the
 * un-synchronized [LinkedHashMap] concurrently with a mutation.
 *
 * The live batch is a [LinkedHashMap] keyed by [CommentId] to preserve insertion order for [all]
 * (mirrors [InMemoryReviewBatchStorage]). [getState] snapshots it to flat [PersistedComment] DTOs on
 * the platform's save cadence; [loadState] inflates raw records only — no `VirtualFile` resolution, no
 * document access, no re-anchoring on the load path (design D3, out of scope). A runtime reload of
 * `workspace.xml` (external edit) re-runs [loadState] without a listener event, so already-open views
 * refresh only on the next store change — acceptable: the target is restore-on-restart, not live sync.
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

    /** The serialized state holder: an insertion-ordered list of flat DTOs (mirrors `all()`). */
    class State {
        @XCollection(style = XCollection.Style.v2)
        var comments: MutableList<PersistedComment> = mutableListOf()
    }

    private val comments = LinkedHashMap<CommentId, ReviewComment>()

    // -- PersistentStateComponent --

    override fun getState(): State = State().also { state ->
        state.comments = comments.values.mapTo(mutableListOf()) { it.toPersisted() }
    }

    override fun loadState(state: State) {
        // Inflate raw records only — no file resolution or re-anchoring (design D3). Degenerate DTOs
        // map through their defaults / safe fallbacks (see PersistedComment.toDomain), never throwing.
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
