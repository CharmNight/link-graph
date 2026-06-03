package com.charmnight.linkgraph.ui

data class GraphEditorStateCommitResult(
    val committed: Boolean,
    val snapshot: GraphEditorStateSnapshot,
)

class GraphEditorStateStore(
    initialState: GraphEditorStateSnapshot = GraphEditorStateSnapshot(),
) {
    private val lock = Any()
    private var state: GraphEditorStateSnapshot = initialState.freeze()

    fun snapshot(): GraphEditorStateSnapshot = synchronized(lock) { state.freeze() }

    fun mutate(transform: (GraphEditorStateSnapshot) -> GraphEditorStateSnapshot): GraphEditorStateSnapshot {
        return synchronized(lock) {
            val current = state
            val transformed = transform(current)
            val next = normalizeRevision(current, transformed).freeze()
            state = next
            // Return a defensive copy separate from the stored state. The first freeze protects
            // the store; this second freeze protects the store from callers holding the result.
            next.freeze()
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
                    snapshot = current.freeze(),
                )
            }
            val next = normalizeRevision(current, transform(current)).freeze()
            state = next
            GraphEditorStateCommitResult(
                committed = true,
                // Return a defensive copy separate from the stored state. The first freeze protects
                // the store; this second freeze protects the store from callers holding the result.
                snapshot = next.freeze(),
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
    preservePreviousStatusKind: Boolean = false,
): GraphEditorStateSnapshot {
    return copy(
        operationFeedback = OperationFeedback(level = level, message = message),
        lastMessageType = if (preservePreviousStatusKind) lastMessageType else "operationFeedback",
    )
}
