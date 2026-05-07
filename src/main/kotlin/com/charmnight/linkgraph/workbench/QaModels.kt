package com.charmnight.linkgraph.workbench

enum class QaMode {
    AUTO,
    ANSWER,
    REVIEW,
    CHANGE,
    INVESTIGATE,
}

/**
 * 对外提供 Qa 语义口径。
 * 第一阶段先以 typealias 包装现有 Audit 模型，避免一次性重命名带来高风险回归。
 */
typealias QaConversationMessage = AuditConversationMessage
typealias QaConversationSession = AuditConversationSession
typealias QaModelTurn = AuditModelTurn
