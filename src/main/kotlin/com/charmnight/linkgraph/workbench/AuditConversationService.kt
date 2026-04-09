package com.charmnight.linkgraph.workbench

class AuditConversationService {
    fun applyModelTurn(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
    ): AuditConversationTurnResult {
        val existingById = session.candidateChanges.associateBy(CandidateDraftChange::changeId)
        val newCandidateChanges = modelTurn.candidateChanges.filter { change -> change.changeId !in existingById }
        val mergedCandidateChanges = linkedMapOf<String, CandidateDraftChange>()
        session.candidateChanges.forEach { change ->
            mergedCandidateChanges[change.changeId] = change
        }
        modelTurn.candidateChanges.forEach { change ->
            mergedCandidateChanges[change.changeId] = change
        }
        val nextMessage = AuditConversationMessage(
            messageId = buildMessageId(session, modelTurn),
            role = AuditMessageRole.ASSISTANT,
            content = modelTurn.answer,
            focusTargetId = modelTurn.candidateChanges.firstOrNull()?.changeId ?: session.focusTargetId,
        )
        return AuditConversationTurnResult(
            session = session.copy(
                messages = session.messages + nextMessage,
                candidateChanges = mergedCandidateChanges.values.toList(),
                focusTargetId = nextMessage.focusTargetId,
            ),
            newCandidateChanges = newCandidateChanges,
            draftWrites = emptyList(),
        )
    }

    private fun buildMessageId(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
    ): String {
        val suffix = session.messages.size + 1
        val focusPart = modelTurn.candidateChanges.firstOrNull()?.changeId ?: "reply"
        return "${session.sessionId}-assistant-$suffix-$focusPart"
    }
}
