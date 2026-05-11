package com.charmnight.linkgraph.workbench

/**
 * 绑定一轮可重放 QA 请求与该请求分类后的实际模式。
 *
 * 这个类型只表达请求模式语义，不依赖 LLM 结果模型，避免 workbench 层反向依赖 llm 层。
 */
internal data class QaModeContext(
    /** 保存原始可重放请求，确保请求字段和模式分类不会被拆散传递。 */
    val request: ReplayableQaRequest,
    /** 保存本轮请求经过分类后的实际执行模式。 */
    val effectiveMode: QaMode,
) {
    /** 保存前端或调用方请求的原始模式。 */
    val requestedMode: QaMode get() = request.mode

    /** 保存本轮用户问题。 */
    val question: String get() = request.question

    /** 保存本轮选中的图节点标识。 */
    val selectedNodeIds: List<String> get() = request.selectedNodeIds

    /** 保存继续取证来源线程标识。 */
    val sourceThreadId: String? get() = request.sourceThreadId

    /** 保存本轮请求复用的问答会话基线。 */
    val baseSession: QaConversationSession? get() = request.baseSession

    /** 判断本轮是否应走确定性继续取证流水线。 */
    val isDeterministicInvestigation: Boolean
        get() = !sourceThreadId.isNullOrBlank() && effectiveMode == QaMode.INVESTIGATE

    /**
     * 判断线程是否属于当前继续取证来源线程。
     *
     * 没有来源线程时表示普通调查模式，允许保留所有本轮线程。
     */
    fun matchesSourceThread(threadId: String): Boolean {
        return sourceThreadId == null || threadId == sourceThreadId
    }
}
