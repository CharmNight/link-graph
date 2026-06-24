package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.llm.GenerationPlan

/**
 * 图谱浏览器诊断工具：把图谱编辑器当前快照、异步请求状态和生成计划
 * 等关键运行时信息压缩成可读字符串，便于在日志、错误诊断、追踪上下文中输出。
 */
internal object GraphBrowserDiagnostics {
    /**
     * 把图谱编辑器快照整体压缩为诊断字符串：包含消息类型、当前场景、
     * 各类图规模、生成计划与异步请求状态等核心字段。
     *
     * @param snapshot 图谱编辑器当前状态快照
     * @return 可读的诊断字符串，由多个键值对拼接而成
     */
    fun snapshotSummary(snapshot: GraphEditorStateSnapshot): String {
        // 把单个图压缩为节点数/边数/前若干节点 ID 的字符串
        fun graphSummary(document: GraphDocument?): String {
            if (document == null) {
                return "0/0"
            }
            return "${document.nodes.size}/${document.edges.size} sample=${document.nodes.take(6).map { it.id }}"
        }

        // 把异步请求状态压缩成包含阶段、ID、流式标记、消息、错误等的诊断片段
        fun requestStateSummary(state: AsyncRequestState): String {
            return buildString {
                append("phase=").append(state.phase)
                append(", requestId=").append(state.requestId)
                append(", streaming=").append(state.streaming)
                append(", status=").append(state.statusMessage)
                append(", detail=").append(state.detailMessage)
                append(", error=").append(state.errorMessage)
                append(", provider=").append(state.providerLabel)
                append(", model=").append(state.model)
            }
        }

        // 把生成计划压缩成来源、计划项数、警告数和摘要文本的诊断片段
        fun generationPlanSummary(plan: GenerationPlan?): String {
            if (plan == null) {
                return "null"
            }
            return "source=${plan.source}, items=${plan.items.size}, warnings=${plan.warnings.size}, summary=${summarizePayloadText(plan.summary)}"
        }

        return buildString {
            val effectiveVisibleGraph = currentVisibleGraph(snapshot)
            val effectiveWorkspaceGraph = currentWorkingGraph(snapshot)
            val currentSceneState = snapshot.currentSceneState()
            append("lastMessageType=").append(snapshot.lastMessageType)
            append(", lastGraphSource=").append(snapshot.lastGraphSource)
            append(", analysisDisplayMode=").append(snapshot.analysisDisplayMode)
            append(", currentSceneId=").append(snapshot.currentSceneId)
            append(", semanticRevision=").append(snapshot.semanticRevision)
            append(", workspaceRevision=").append(snapshot.workspaceRevision)
            append(", layoutRevision=").append(currentSceneState.layoutRevision)
            append(", snapshotRevision=").append(snapshot.snapshotRevision)
            append(", selectedNodeId=").append(currentSceneState.selectedNodeId)
            append(", visibleGraph=").append(graphSummary(effectiveVisibleGraph))
            append(", workspaceGraph=").append(graphSummary(effectiveWorkspaceGraph))
            append(", semanticFactGraph=").append(graphSummary(snapshot.semanticFactGraph))
            append(", generationPlan=").append(generationPlanSummary(snapshot.generationPlan))
            append(", generationPlanRequestState=").append(requestStateSummary(snapshot.generationPlanRequestState))
            append(", feedback=").append(snapshot.operationFeedback?.message)
        }
    }

    /**
     * 对比前后两个图谱编辑器快照的差异：重点关注可见图与工作区图的节点增删与标题变化。
     *
     * @param previous 前一个快照
     * @param next 后一个快照
     * @return 描述差异的诊断字符串
     */
    fun snapshotDeltaSummary(
        previous: GraphEditorStateSnapshot,
        next: GraphEditorStateSnapshot,
    ): String {
        return buildString {
            append("visible{").append(graphDeltaSummary(currentVisibleGraph(previous), currentVisibleGraph(next))).append("}")
            append(", workspace{").append(graphDeltaSummary(currentWorkingGraph(previous), currentWorkingGraph(next))).append("}")
        }
    }

    /**
     * 把任意文本压缩为短诊断表示：超过最大长度时截断并附上裁剪标记，
     * 空白字符串统一返回空引号，便于在诊断输出中清晰区分。
     *
     * @param value 原始文本，可能为 null
     * @param maxLength 允许的最大长度，超出后会被裁剪
     * @return 适合日志输出的简短文本
     */
    fun summarizePayloadText(
        value: String?,
        maxLength: Int = 160,
    ): String {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) {
            return "\"\""
        }
        return if (normalized.length <= maxLength) {
            normalized
        } else {
            normalized.take(maxLength) + "...(trimmed)"
        }
    }

    /**
     * 计算两个图之间的差异摘要：节点新增、删除、标题变更三类，
     * 每类只取前若干条用于诊断，避免输出过长。
     *
     * @param previous 前一个图文档，可能为空
     * @param next 后一个图文档，可能为空
     * @return 描述差异的字符串
     */
    private fun graphDeltaSummary(
        previous: GraphDocument?,
        next: GraphDocument?,
    ): String {
        val previousNodes = previous?.nodes?.associateBy { it.id }.orEmpty()
        val nextNodes = next?.nodes?.associateBy { it.id }.orEmpty()
        val added = nextNodes.keys.subtract(previousNodes.keys)
        val removed = previousNodes.keys.subtract(nextNodes.keys)
        val retitled = nextNodes.keys.intersect(previousNodes.keys)
            .mapNotNull { nodeId ->
                val before = previousNodes[nodeId] ?: return@mapNotNull null
                val after = nextNodes[nodeId] ?: return@mapNotNull null
                if (before.title == after.title) {
                    null
                } else {
                    "$nodeId:${summarizePayloadText(before.title)} -> ${summarizePayloadText(after.title)}"
                }
            }
        return buildString {
            append("added=").append(added.take(4))
            append(", removed=").append(removed.take(4))
            append(", retitled=").append(retitled.take(4))
        }
    }
}
