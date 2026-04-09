package com.charmnight.linkgraph.semantic.model

/**
 * 定义语义分析诊断的严重级别。
 */
enum class SemanticDiagnosticSeverity {
    /** 表示提示信息。 */
    INFO,
    /** 表示告警信息。 */
    WARNING,
    /** 表示错误信息。 */
    ERROR,
}

/**
 * 表示语义分析阶段输出的一条诊断。
 */
data class SemanticDiagnostic(
    /** 保存诊断严重级别。 */
    val severity: SemanticDiagnosticSeverity,
    /** 保存诊断代码。 */
    val code: String,
    /** 保存诊断消息。 */
    val message: String,
)
