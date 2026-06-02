package com.charmnight.linkgraph.ui.bridge

import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.json.JsonCodec
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.ui.GraphBrowserPayloadKind
import com.charmnight.linkgraph.ui.GraphBrowserPayloadParser
import com.charmnight.linkgraph.ui.GraphEditorMessage
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.StepGranularity

internal data class BridgeCommandParseResult(
    val type: String,
    val actionLabel: String,
    val async: Boolean = false,
    val message: GraphEditorMessage? = null,
    val artifactIds: List<String> = emptyList(),
)

internal object BridgeCommandParser {
    private const val SCHEMA_VERSION = 1

    private val asyncCommandTypes = setOf(
        "requestQa",
        "requestGraphBeautification",
        "requestIndexedGraph",
        "requestOpenSettings",
        "applyCodeDrafts",
        "applySingleCodeDraft",
        "openCodeDraftNativeDiff",
        "requestDraftNavigation",
    )

    fun parse(commandJson: String): BridgeCommandParseResult {
        GraphBrowserPayloadParser.validatePayloadSize(commandJson, GraphBrowserPayloadKind.STRUCTURED)
        val root = JsonCodec.parseObject(commandJson, rootDescription = "bridge command")
        val schemaVersion = (root["schemaVersion"] as? Number)?.toInt()
            ?: error("bridge command schemaVersion is required")
        require(schemaVersion == SCHEMA_VERSION) {
            "unsupported bridge command schemaVersion: $schemaVersion"
        }
        val type = root.requiredString("type", "bridge command type")
        val payload = (root["payload"] as? Map<*, *>).orEmpty()
        return BridgeCommandParseResult(
            type = type,
            actionLabel = actionLabel(type),
            async = type in asyncCommandTypes,
            message = parseMessage(type, payload),
            artifactIds = if (type == "requestArtifact") payload.stringList("artifactIds") else emptyList(),
        )
    }

    private fun parseMessage(
        type: String,
        payload: Map<*, *>,
    ): GraphEditorMessage? =
        when (type) {
            "importMermaid" -> GraphEditorMessage.ImportMermaid(payload.string("mermaid").orEmpty())
            "exportMermaid" -> GraphEditorMessage.ExportMermaid
            "showDiffMode" -> GraphEditorMessage.ShowDiffMode
            "requestSyncPreview" -> GraphEditorMessage.RequestSyncPreview
            "requestQa" -> GraphEditorMessage.RequestQa(
                question = payload.string("question").orEmpty(),
                selectedNodeIds = payload.stringList("selectedNodeIds"),
                sourceThreadId = payload.string("sourceThreadId")?.takeIf(String::isNotBlank),
                mode = payload.enumOrDefault("mode", QaMode.AUTO),
            )
            "retryLastQaRequest" -> GraphEditorMessage.RetryLastQaRequest
            "confirmQaCandidateChange" -> GraphEditorMessage.ConfirmQaCandidateChange(
                changeId = payload.requiredString("changeId", "changeId"),
            )
            "unconfirmQaCandidateChange" -> GraphEditorMessage.UnconfirmQaCandidateChange(
                changeId = payload.requiredString("changeId", "changeId"),
            )
            "resolveInvestigationThread" -> GraphEditorMessage.ResolveInvestigationThread(
                threadId = payload.requiredString("threadId", "threadId"),
                resolutionStatus = payload.enum("resolutionStatus"),
                note = payload.string("note").orEmpty(),
            )
            "requestDiffReview" -> GraphEditorMessage.RequestDiffReview(
                question = payload.string("question").orEmpty(),
                selectedDiffItemIds = payload.stringList("selectedDiffItemIds"),
            )
            "requestGraphBeautification" -> GraphEditorMessage.RequestGraphBeautification(
                goal = payload.string("goal").orEmpty(),
                preferredStyle = payload.string("preferredStyle")?.takeIf(String::isNotBlank),
                explanationFocus = payload.string("explanationFocus")?.takeIf(String::isNotBlank),
                focusNodeId = payload.string("focusNodeId")?.takeIf(String::isNotBlank),
                followUp = parseBeautificationFollowUp(payload["followUp"] as? Map<*, *>),
                granularity = payload.enumOrDefault("granularity", StepGranularity.BUSINESS),
            )
            "applyDraftPatchPreview" -> GraphEditorMessage.ApplyDraftPatchPreview(
                operationIds = payload.stringList("operationIds").toSet().takeIf(Set<String>::isNotEmpty),
            )
            "clearDraftPatchPreview" -> GraphEditorMessage.ClearDraftPatchPreview
            "restoreDraftPatchPreview" -> GraphEditorMessage.RestoreDraftPatchPreview(
                GraphEditorMessage.DraftPatchPreviewSource.valueOf(payload.requiredString("source", "source")),
            )
            "undoLastDraftPatchApply" -> GraphEditorMessage.UndoLastDraftPatchApply
            "requestGenerationPlan" -> GraphEditorMessage.RequestGenerationPlan
            "requestGenerationPlanDiscussion" -> GraphEditorMessage.RequestGenerationPlanDiscussion(
                question = payload.string("question").orEmpty(),
                focusItemId = payload.string("focusItemId")?.takeIf(String::isNotBlank),
            )
            "requestCodeDrafts" -> GraphEditorMessage.RequestCodeDrafts
            "requestCurrentEditorContextGraph" -> GraphEditorMessage.RequestCurrentEditorContextGraph
            "requestAnalysisDisplayMode" -> GraphEditorMessage.RequestAnalysisDisplayMode(
                displayMode = payload.enum("displayMode"),
            )
            "requestIndexedGraph" -> GraphEditorMessage.RequestIndexedGraph(
                GraphBrowserPayloadParser.parseIndexedGraphRequest(JsonCodec.toJson(payload)),
            )
            "updateWorkbenchSectionPreference" -> GraphEditorMessage.UpdateWorkbenchSectionPreference(
                sectionId = payload.requiredString("sectionId", "sectionId"),
                expanded = payload.booleanOrDefault("expanded", false),
            )
            "requestOpenSettings" -> GraphEditorMessage.OpenSettings
            "applyCodeDrafts" -> GraphEditorMessage.ApplyCodeDrafts
            "applySingleCodeDraft" -> GraphEditorMessage.ApplySingleCodeDraft(
                draftId = payload.requiredString("draftId", "draftId"),
            )
            "openCodeDraftNativeDiff" -> GraphEditorMessage.OpenCodeDraftNativeDiff(
                draftId = payload.requiredString("draftId", "draftId"),
            )
            "requestDraftNavigation" -> GraphEditorMessage.RequestDraftNavigation(
                targetPath = payload.requiredString("targetPath", "targetPath"),
            )
            "requestArtifact" -> null
            "frontendReady" -> GraphEditorMessage.FrontendReady(
                lastAppliedRevision = (payload["lastAppliedRevision"] as? Number)?.toLong(),
            )
            "snapshotAck" -> GraphEditorMessage.SnapshotAck(
                revision = (payload["revision"] as? Number)?.toLong()
                    ?: error("snapshotAck revision is required"),
            )
            "nodeSelected" -> GraphEditorMessage.NodeSelected(
                nodeId = payload.requiredString("nodeId", "nodeId"),
            )
            "layoutChanged" -> GraphEditorMessage.LayoutChanged(parseLayoutPositions(payload))
            "requestSourceNavigation" -> GraphEditorMessage.RequestSourceNavigation(
                nodeId = payload.requiredString("nodeId", "nodeId"),
            )
            "requestExpandOverflowNode" -> GraphEditorMessage.RequestExpandOverflowNode(
                nodeId = payload.requiredString("nodeId", "nodeId"),
            )
            "requestExpandInvocation" -> GraphEditorMessage.RequestExpandInvocation(
                nodeId = payload.requiredString("nodeId", "nodeId"),
            )
            "requestRemoveInvocationExpansion" -> GraphEditorMessage.RequestRemoveInvocationExpansion(
                expansionId = payload.requiredString("expansionId", "expansionId"),
            )
            "applyGraphEditScript" -> GraphEditorMessage.ApplyGraphEditScript(
                GraphBrowserPayloadParser.parseGraphEditScript(JsonCodec.toJson(payload)),
            )
            else -> error("unsupported bridge command type: $type")
        }

    private fun parseBeautificationFollowUp(raw: Map<*, *>?): GraphBeautificationFollowUpContext? {
        raw ?: return null
        val stepId = raw.string("stepId")?.takeIf(String::isNotBlank) ?: return null
        val stepTitle = raw.string("stepTitle")?.takeIf(String::isNotBlank) ?: return null
        val question = raw.string("question")?.takeIf(String::isNotBlank) ?: return null
        return GraphBeautificationFollowUpContext(stepId, stepTitle, question)
    }

    private fun parseLayoutPositions(payload: Map<*, *>): Map<String, GraphLayoutPosition> =
        (payload["positions"] as? List<*>)
            .orEmpty()
            .mapNotNull { raw ->
                val item = raw as? Map<*, *> ?: return@mapNotNull null
                val nodeId = item.string("nodeId")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val x = (item["x"] as? Number)?.toDouble() ?: return@mapNotNull null
                val y = (item["y"] as? Number)?.toDouble() ?: return@mapNotNull null
                nodeId to GraphLayoutPosition(x = x, y = y)
            }
            .toMap()

    private fun actionLabel(type: String): String =
        when (type) {
            "importMermaid" -> "导入 Mermaid"
            "exportMermaid" -> "导出 Mermaid"
            "showDiffMode" -> "切换差异模式"
            "requestSyncPreview" -> "请求同步预览"
            "requestQa" -> "问答"
            "retryLastQaRequest" -> "重试问答"
            "confirmQaCandidateChange" -> "确认问答候选变更"
            "unconfirmQaCandidateChange" -> "取消确认问答候选变更"
            "resolveInvestigationThread" -> "风险决策提交"
            "requestDiffReview" -> "差异分析"
            "requestGraphBeautification" -> "链路讲解"
            "applyDraftPatchPreview" -> "应用草稿补丁预览"
            "clearDraftPatchPreview" -> "清空草稿补丁预览"
            "restoreDraftPatchPreview" -> "恢复草稿补丁预览"
            "undoLastDraftPatchApply" -> "撤销草稿补丁应用"
            "requestGenerationPlan" -> "生成实现计划"
            "requestGenerationPlanDiscussion" -> "实现建议追问"
            "requestCodeDrafts" -> "请求代码草稿"
            "requestCurrentEditorContextGraph" -> "加载当前编辑器上下文链路"
            "requestAnalysisDisplayMode" -> "切换展示模式"
            "requestIndexedGraph" -> "加载 indexed 图"
            "updateWorkbenchSectionPreference" -> "更新工作台偏好"
            "requestOpenSettings" -> "打开设置"
            "applyCodeDrafts" -> "写入全部代码草稿"
            "applySingleCodeDraft" -> "写入单个代码草稿"
            "openCodeDraftNativeDiff" -> "打开代码草稿原生 Diff"
            "requestDraftNavigation" -> "代码草稿导航"
            "requestArtifact" -> "artifact 请求"
            "frontendReady" -> "前端 ready 握手"
            "snapshotAck" -> "快照确认"
            "nodeSelected" -> "节点选择"
            "layoutChanged" -> "链路图布局同步"
            "requestSourceNavigation" -> "源码导航"
            "requestExpandOverflowNode" -> "展开溢出节点"
            "requestExpandInvocation" -> "展开调用方法"
            "requestRemoveInvocationExpansion" -> "移除调用展开"
            "applyGraphEditScript" -> "链路图编辑脚本同步"
            else -> type
        }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String

    private fun Map<*, *>.requiredString(key: String, description: String): String =
        string(key)?.takeIf(String::isNotBlank) ?: error("$description is required")

    private fun Map<*, *>.stringList(key: String): List<String> =
        (this[key] as? List<*>).orEmpty().mapNotNull { value ->
            (value as? String)?.takeIf(String::isNotBlank)
        }

    private fun Map<*, *>.booleanOrDefault(key: String, defaultValue: Boolean): Boolean =
        (this[key] as? Boolean) ?: defaultValue

    private inline fun <reified T : Enum<T>> Map<*, *>.enum(key: String): T =
        enumOrNull<T>(key) ?: error("$key is required")

    private inline fun <reified T : Enum<T>> Map<*, *>.enumOrDefault(
        key: String,
        defaultValue: T,
    ): T =
        enumOrNull<T>(key) ?: defaultValue

    private inline fun <reified T : Enum<T>> Map<*, *>.enumOrNull(key: String): T? {
        val raw = string(key) ?: return null
        return enumValues<T>().firstOrNull { it.name == raw }
    }
}
