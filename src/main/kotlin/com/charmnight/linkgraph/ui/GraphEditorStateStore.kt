package com.charmnight.linkgraph.ui
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

import com.charmnight.linkgraph.workbench.AssistantResultStore

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
            // 返回与内部存储分离的防御性副本；第一次 freeze 保护 store，
            // 第二次 freeze 避免调用方持有返回值后影响 store。
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
                // 返回与内部存储分离的防御性副本；第一次 freeze 保护 store，
                // 第二次 freeze 避免调用方持有返回值后影响 store。
                snapshot = next.freeze(),
            )
        }
    }

    private fun normalizeRevision(
        current: GraphEditorStateSnapshot,
        next: GraphEditorStateSnapshot,
    ): GraphEditorStateSnapshot {
        val normalizedNext = next.normalizeAssistantHistory()
        if (normalizedNext == current) {
            return current
        }
        return if (normalizedNext.snapshotRevision > current.snapshotRevision) {
            normalizedNext
        } else {
            normalizedNext.copy(snapshotRevision = current.snapshotRevision + 1)
        }
    }
}

private fun GraphEditorStateSnapshot.normalizeAssistantHistory(): GraphEditorStateSnapshot {
    val retainedTurns = assistantSessionState.turns.takeLast(AssistantResultStore.HISTORY_RETENTION_LIMIT)
    val retainedResultIds = retainedTurns.map { turn -> turn.resultId }
    val retainedResultStore = assistantResultStore.retainOnly(retainedResultIds)
    if (retainedTurns == assistantSessionState.turns && retainedResultStore == assistantResultStore) {
        return this
    }
    return copy(
        assistantSessionState = assistantSessionState.copy(turns = retainedTurns),
        assistantResultStore = retainedResultStore,
    )
}

internal fun GraphEditorStateSnapshot.withOperationFeedback(
    level: ApplicationFeedbackLevel,
    message: String,
    preservePreviousStatusKind: Boolean = false,
): GraphEditorStateSnapshot {
    return copy(
        operationFeedback = OperationFeedback(level = level, message = message),
        lastMessageType = if (preservePreviousStatusKind) lastMessageType else "operationFeedback",
    )
}
