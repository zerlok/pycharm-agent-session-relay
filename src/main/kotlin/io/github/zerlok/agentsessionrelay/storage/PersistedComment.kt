package io.github.zerlok.agentsessionrelay.storage

import com.intellij.util.xmlb.annotations.XCollection
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject

/**
 * The on-disk form of a [ReviewComment] — a flat, `xmlb`-serializable bean living in the storage
 * layer (design D2). `xmlb` needs a no-arg constructor and mutable `var` bean properties and has no
 * story for a Kotlin sealed hierarchy, immutable `data class`, or `value class`, so the domain types
 * (`sealed Subject`, `ReviewComment`, `value class CommentId`) stay inert (ARCHITECTURE §3.1) and this
 * DTO absorbs every serialization concession. The [PersistedComment.toDomain] / [ReviewComment.toPersisted]
 * mapper is the storage-boundary translation, switching on [subjectKind] to rebuild each [Subject] variant.
 *
 * Line numbers are the domain's 0-based convention on disk too; the 1-based conversion stays a
 * user-facing/export concern (ARCHITECTURE §3.2).
 */
class PersistedComment {
    var id: String = ""

    /** One of [SubjectKind]; drives which [Subject] variant the mapper rebuilds. */
    var subjectKind: String = ""

    /** Single-file subjects ([SubjectKind.LINE], [LINE_RANGE][SubjectKind.LINE_RANGE], [FILE][SubjectKind.FILE]). */
    var fileUrl: String? = null

    /** 0-based, domain convention. -1 means "no line anchor" (non-line subjects). */
    var startLine: Int = -1
    var endLine: Int = -1

    /** Only for [SubjectKind.FILES]. */
    @XCollection(style = XCollection.Style.v2)
    var fileUrls: MutableList<String> = mutableListOf()

    var body: String = ""
    var status: String = CommentStatus.ACTIVE.name
    var anchorText: String? = null
    var contextHash: String? = null
}

/** The persisted [Subject] discriminators — one per sealed variant. */
private object SubjectKind {
    const val LINE = "LINE"
    const val LINE_RANGE = "LINE_RANGE"
    const val FILE = "FILE"
    const val FILES = "FILES"
    const val PROJECT = "PROJECT"
}

/**
 * Domain → DTO. Flattens the [Subject] variant into [PersistedComment.subjectKind] plus the fields
 * that variant carries; every other field is losslessly copied.
 */
fun ReviewComment.toPersisted(): PersistedComment = PersistedComment().also { dto ->
    dto.id = id.value
    dto.body = body
    dto.status = status.name
    dto.anchorText = anchorText
    dto.contextHash = contextHash
    when (val s = subject) {
        is Subject.Line -> {
            dto.subjectKind = SubjectKind.LINE
            dto.fileUrl = s.fileUrl
            dto.startLine = s.line
            dto.endLine = s.line
        }

        is Subject.LineRange -> {
            dto.subjectKind = SubjectKind.LINE_RANGE
            dto.fileUrl = s.fileUrl
            dto.startLine = s.startLine
            dto.endLine = s.endLine
        }

        is Subject.File -> {
            dto.subjectKind = SubjectKind.FILE
            dto.fileUrl = s.fileUrl
        }

        is Subject.Files -> {
            dto.subjectKind = SubjectKind.FILES
            dto.fileUrls = s.fileUrls.toMutableList()
        }

        Subject.Project -> {
            dto.subjectKind = SubjectKind.PROJECT
        }
    }
}

/**
 * DTO → domain. Rebuilds the exact [Subject] variant by switching on [PersistedComment.subjectKind].
 *
 * Degenerate input is tolerated (design "Risks", tasks 1.3): missing fields already carry their bean
 * defaults, and an unrecognized [subjectKind] falls back to [Subject.Project] (a safe, file-less
 * subject) rather than throwing — so a schema-drifted or partial record loads instead of aborting the
 * whole batch restore. An unrecognized [status] falls back to [CommentStatus.ACTIVE].
 */
fun PersistedComment.toDomain(): ReviewComment = ReviewComment(
    id = CommentId(id),
    subject = toSubject(),
    body = body,
    status = runCatching { CommentStatus.valueOf(status) }.getOrDefault(CommentStatus.ACTIVE),
    anchorText = anchorText,
    contextHash = contextHash,
)

private fun PersistedComment.toSubject(): Subject = when (subjectKind) {
    SubjectKind.LINE -> Subject.Line(fileUrl.orEmpty(), startLine)
    SubjectKind.LINE_RANGE -> Subject.LineRange(fileUrl.orEmpty(), startLine, endLine)
    SubjectKind.FILE -> Subject.File(fileUrl.orEmpty())
    SubjectKind.FILES -> Subject.Files(fileUrls.toList())
    SubjectKind.PROJECT -> Subject.Project
    // Unknown/blank kind (old or corrupt state): safe file-less fallback rather than throwing on load.
    else -> Subject.Project
}
