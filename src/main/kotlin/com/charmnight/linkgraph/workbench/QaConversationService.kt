package com.charmnight.linkgraph.workbench

/**
 * 问答语义包装层。
 * 当前仅委托现有 AuditConversationService，逐步把内部命名从 Audit 收敛到 Qa。
 */
class QaConversationService(
    private val auditConversationService: AuditConversationService = AuditConversationService(),
) {
    fun applyModelTurn(
        session: QaConversationSession,
        modelTurn: QaModelTurn,
    ): AuditConversationTurnResult {
        return auditConversationService.applyModelTurn(session, modelTurn)
    }
}
