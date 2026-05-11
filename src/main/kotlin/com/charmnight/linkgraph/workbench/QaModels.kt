package com.charmnight.linkgraph.workbench

enum class QaMode {
    AUTO,
    ANSWER,
    REVIEW,
    CHANGE,
    INVESTIGATE,
}

typealias AuditConversationMessage = QaConversationMessage
typealias AuditConversationSession = QaConversationSession
typealias AuditModelTurn = QaModelTurn
typealias AuditConversationTurnResult = QaConversationTurnResult
typealias AuditConversationService = QaConversationService
