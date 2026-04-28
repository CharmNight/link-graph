package com.charmnight.linkgraph.ui

data class GraphEditorStateCommitResult(
    val committed: Boolean,
    val snapshot: GraphEditorStateSnapshot,
)

class GraphEditorStateStore(
    initialState: GraphEditorStateSnapshot = GraphEditorStateSnapshot(),
) {
    private val lock = Any()
    private var state: GraphEditorStateSnapshot = initialState

    fun snapshot(): GraphEditorStateSnapshot = synchronized(lock) { state.copy() }

    fun mutate(transform: (GraphEditorStateSnapshot) -> GraphEditorStateSnapshot): GraphEditorStateSnapshot {
        return synchronized(lock) {
            val current = state
            val transformed = transform(current)
            val next = normalizeRevision(current, transformed)
            state = next
            next
        }
    }

    fun tryCommit(
        expectedRevision: Long,
        transform: (GraphEditorStateSnapshot) -> GraphEditorStateSnapshot,
    ): GraphEditorStateCommitResult {
        return synchronized(lock) {
            val current = state
            if (current.snapshotRevision != expectedRevision) {
                return@synchronized GraphEditorStateCommitResult(
                    committed = false,
                    snapshot = current,
                )
            }
            val next = normalizeRevision(current, transform(current))
            state = next
            GraphEditorStateCommitResult(
                committed = true,
                snapshot = next,
            )
        }
    }

    private fun normalizeRevision(
        current: GraphEditorStateSnapshot,
        next: GraphEditorStateSnapshot,
    ): GraphEditorStateSnapshot {
        if (next == current) {
            return current
        }
        return if (next.snapshotRevision > current.snapshotRevision) {
            next
        } else {
            next.copy(snapshotRevision = current.snapshotRevision + 1)
        }
    }
}

internal fun GraphEditorStateSnapshot.withOperationFeedback(
    level: OperationFeedbackLevel,
    message: String,
    preserveLastMessageType: Boolean = false,
): GraphEditorStateSnapshot {
    return copy(
        operationFeedback = OperationFeedback(level = level, message = message),
        lastMessageType = if (preserveLastMessageType) lastMessageType else "operationFeedback",
    )
}
