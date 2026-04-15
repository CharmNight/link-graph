package com.charmnight.linkgraph.workbench

class AuditConversationService {
    fun applyModelTurn(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
    ): AuditConversationTurnResult {
        val existingCandidateById = session.candidateChanges.associateBy(CandidateDraftChange::changeId)
        val newCandidateChanges = modelTurn.candidateChanges.filter { change -> change.changeId !in existingCandidateById }
        val mergedCandidateChanges = linkedMapOf<String, CandidateDraftChange>()
        session.candidateChanges.forEach { change ->
            mergedCandidateChanges[change.changeId] = change
        }
        modelTurn.candidateChanges.forEach { change ->
            mergedCandidateChanges[change.changeId] = change
        }
        val mergedInvestigationLeads = linkedMapOf<String, AuditInvestigationLead>()
        val newInvestigationLeadIds = mutableListOf<String>()
        session.investigationLeads.forEach { lead ->
            mergedInvestigationLeads[lead.leadId] = lead
        }
        modelTurn.investigationLeads.forEach { lead ->
            val existingLead = mergedInvestigationLeads[lead.leadId]
            if (existingLead != null) {
                mergedInvestigationLeads[lead.leadId] = lead
                return@forEach
            }
            val mergeTargetId = resolveLeadMergeTarget(session, modelTurn, lead, mergedInvestigationLeads)
            if (mergeTargetId == null) {
                mergedInvestigationLeads[lead.leadId] = lead
                newInvestigationLeadIds += lead.leadId
                return@forEach
            }
            val mergeTarget = mergedInvestigationLeads[mergeTargetId] ?: return@forEach
            mergedInvestigationLeads[mergeTargetId] = mergeInvestigationLead(mergeTarget, lead)
        }
        val promotedLeadIds = mergedInvestigationLeads.values
            .filter { lead -> lead.status == AuditInvestigationLeadStatus.OPEN }
            .filter { lead -> overlapsWithAnyCandidate(lead, mergedCandidateChanges.values) }
            .map(AuditInvestigationLead::leadId)
            .toSet()
        if (promotedLeadIds.isNotEmpty()) {
            promotedLeadIds.forEach { leadId ->
                val lead = mergedInvestigationLeads[leadId] ?: return@forEach
                mergedInvestigationLeads[leadId] = lead.copy(status = AuditInvestigationLeadStatus.PROMOTED)
            }
        }
        val newInvestigationLeads = newInvestigationLeadIds.mapNotNull(mergedInvestigationLeads::get)
        val focusTargetId = modelTurn.candidateChanges.firstOrNull()?.changeId
            ?: newInvestigationLeads.firstOrNull()?.leadId
            ?: resolveFocusedLeadId(session, modelTurn, mergedInvestigationLeads)
            ?: session.focusTargetId
        val nextMessage = AuditConversationMessage(
            messageId = buildMessageId(session, modelTurn),
            role = AuditMessageRole.ASSISTANT,
            content = modelTurn.answer,
            focusTargetId = focusTargetId,
        )
        return AuditConversationTurnResult(
            session = session.copy(
                messages = session.messages + nextMessage,
                candidateChanges = mergedCandidateChanges.values.toList(),
                investigationLeads = mergedInvestigationLeads.values.toList(),
                focusTargetId = nextMessage.focusTargetId,
            ),
            newCandidateChanges = newCandidateChanges,
            newInvestigationLeads = newInvestigationLeads.map { lead ->
                if (lead.leadId in promotedLeadIds) {
                    lead.copy(status = AuditInvestigationLeadStatus.PROMOTED)
                } else {
                    lead
                }
            },
            draftWrites = emptyList(),
        )
    }

    private fun resolveLeadMergeTarget(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
        newLead: AuditInvestigationLead,
        mergedInvestigationLeads: Map<String, AuditInvestigationLead>,
    ): String? {
        if (newLead.status != AuditInvestigationLeadStatus.OPEN) {
            return null
        }
        val sourceLeadId = modelTurn.sourceLeadId?.takeIf { it in mergedInvestigationLeads.keys }
        if (sourceLeadId != null) {
            val sourceLead = mergedInvestigationLeads[sourceLeadId] ?: return null
            if (sourceLead.status == AuditInvestigationLeadStatus.OPEN && leadsOverlap(sourceLead, newLead)) {
                return sourceLeadId
            }
        }
        if (!isFollowUpInvestigationQuestion(session)) {
            return null
        }
        val overlappingLeadIds = mergedInvestigationLeads.values
            .filter { lead -> lead.status == AuditInvestigationLeadStatus.OPEN }
            .filter { lead -> leadsOverlap(lead, newLead) }
            .map(AuditInvestigationLead::leadId)
        if (session.focusTargetId != null && session.focusTargetId in overlappingLeadIds) {
            return session.focusTargetId
        }
        return overlappingLeadIds.singleOrNull()
    }

    private fun resolveFocusedLeadId(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
        mergedInvestigationLeads: Map<String, AuditInvestigationLead>,
    ): String? {
        val sourceLeadId = modelTurn.sourceLeadId
        if (sourceLeadId != null && sourceLeadId in mergedInvestigationLeads.keys) {
            return sourceLeadId
        }
        return if (isFollowUpInvestigationQuestion(session)) {
            session.focusTargetId?.takeIf { it in mergedInvestigationLeads.keys }
        } else {
            null
        }
    }

    private fun buildMessageId(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
    ): String {
        val suffix = session.messages.size + 1
        val focusPart = modelTurn.candidateChanges.firstOrNull()?.changeId
            ?: modelTurn.sourceLeadId
            ?: modelTurn.investigationLeads.firstOrNull()?.leadId
            ?: "reply"
        return "${session.sessionId}-assistant-$suffix-$focusPart"
    }

    private fun overlapsWithAnyCandidate(
        lead: AuditInvestigationLead,
        candidates: Collection<CandidateDraftChange>,
    ): Boolean {
        return candidates.any { change ->
            change.targetNodeIds.intersect(lead.targetNodeIds.toSet()).isNotEmpty()
                || change.targetStepIds.intersect(lead.targetStepIds.toSet()).isNotEmpty()
        }
    }

    private fun leadsOverlap(
        left: AuditInvestigationLead,
        right: AuditInvestigationLead,
    ): Boolean {
        return left.targetNodeIds.intersect(right.targetNodeIds.toSet()).isNotEmpty()
            || left.targetStepIds.intersect(right.targetStepIds.toSet()).isNotEmpty()
    }

    private fun mergeInvestigationLead(
        existing: AuditInvestigationLead,
        incoming: AuditInvestigationLead,
    ): AuditInvestigationLead {
        return existing.copy(
            status = if (incoming.status == AuditInvestigationLeadStatus.OPEN) existing.status else incoming.status,
            title = incoming.title.ifBlank { existing.title },
            targetStepIds = (existing.targetStepIds + incoming.targetStepIds).distinct(),
            targetNodeIds = (existing.targetNodeIds + incoming.targetNodeIds).distinct(),
            summary = incoming.summary.ifBlank { existing.summary },
            evidenceGap = incoming.evidenceGap.ifBlank { existing.evidenceGap },
            recommendedQuestion = incoming.recommendedQuestion.ifBlank { existing.recommendedQuestion },
            claimType = incoming.claimType ?: existing.claimType,
            evidence = (existing.evidence + incoming.evidence).distinctBy { finding -> finding.id },
        )
    }

    private fun isFollowUpInvestigationQuestion(session: AuditConversationSession): Boolean {
        val lastUserMessage = session.messages.lastOrNull { message -> message.role == AuditMessageRole.USER } ?: return false
        return lastUserMessage.content.contains("继续取证")
    }
}
