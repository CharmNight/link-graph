package com.charmnight.linkgraph.semantic.model

/**
 * 定义语义分析诊断的严重级别。
 *
 * 用于让 UI 按不同视觉强度提示用户：INFO 一般是说明，WARNING 是潜在问题，
 * ERROR 是阻断性问题。
 */
enum class SemanticDiagnosticSeverity {
    /** 表示提示信息。一般用于说明性诊断，不影响功能。 */
    INFO,

    /** 表示告警信息。可能影响完整性，建议用户关注。 */
    WARNING,

    /** 表示错误信息。通常是阻断性的，需要用户解决后才能继续。 */
    ERROR,
}

/**
 * 表示语义分析阶段输出的一条诊断。
 *
 * 诊断不直接打断流程，而是收集后由 UI 统一展示。
 * 通过 [code] 字段做机器侧分类，[message] 字段供人类阅读。
 */
data class SemanticDiagnostic(
    /** 保存诊断严重级别。 */
    val severity: SemanticDiagnosticSeverity,
    /** 保存诊断代码。稳定的机器可读标识，便于过滤与去重。 */
    val code: String,
    /** 保存诊断消息。人类可读的具体说明。 */
    val message: String,
)
