package com.charmnight.linkgraph.ui.bridge

import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.json.JsonCodec
import com.charmnight.linkgraph.ui.GraphBrowserPayloadKind
import com.charmnight.linkgraph.ui.GraphBrowserPayloadParser
import com.charmnight.linkgraph.ui.GraphEditorMessage
import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
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
    const val MAX_LAYOUT_POSITIONS: Int = 1024
    const val MAX_STRING_LIST_ITEMS: Int = 256

    private val asyncCommandTypes = setOf(
        "requestAssistantTask",
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
            "requestAssistantTask" -> parseAssistantTask(payload)
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
            "applyDraftPatchPreview" -> GraphEditorMessage.ApplyDraftPatchPreview(
                operationIds = payload.stringList("operationIds").toSet().takeIf(Set<String>::isNotEmpty),
            )
            "clearDraftPatchPreview" -> GraphEditorMessage.ClearDraftPatchPreview
            "restoreDraftPatchPreview" -> GraphEditorMessage.RestoreDraftPatchPreview(
                GraphEditorMessage.DraftPatchPreviewSource.valueOf(payload.requiredString("source", "source")),
            )
            "undoLastDraftPatchApply" -> GraphEditorMessage.UndoLastDraftPatchApply
            "requestCodeDrafts" -> GraphEditorMessage.RequestCodeDrafts
            "requestCurrentEditorContextGraph" -> GraphEditorMessage.RequestCurrentEditorContextGraph
            "requestAnalysisDisplayMode" -> GraphEditorMessage.RequestAnalysisDisplayMode(
                displayMode = payload.enum("displayMode"),
            )
            "requestIndexedGraph" -> GraphEditorMessage.RequestIndexedGraph(
                GraphBrowserPayloadParser.parseIndexedGraphRequest(JsonCodec.toJson(payload)),
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
                frontendRequestedAtMs = (payload["frontendRequestedAtMs"] as? Number)?.toLong(),
            )
            "requestRemoveInvocationExpansion" -> GraphEditorMessage.RequestRemoveInvocationExpansion(
                expansionId = payload.requiredString("expansionId", "expansionId"),
            )
            "collapseInvocationExpansion" -> GraphEditorMessage.CollapseInvocationExpansion(
                expansionId = payload.requiredString("expansionId", "expansionId"),
            )
            "openInvocationExpansion" -> GraphEditorMessage.OpenInvocationExpansion(
                expansionId = payload.requiredString("expansionId", "expansionId"),
            )
            "applyGraphEditScript" -> GraphEditorMessage.ApplyGraphEditRequest(
                GraphBrowserPayloadParser.parseGraphEditRequest(JsonCodec.toJson(payload)),
            )
            else -> error("unsupported bridge command type: $type")
        }

    private fun parseAssistantTask(payload: Map<*, *>): GraphEditorMessage.RequestAssistantTask {
        val actionId = payload.enum<AssistantActionId>("actionId")
        val routedIntent = actionId.toIntent()
        val declaredIntent = payload.enumOrNull<AssistantIntent>("intent")
        if (declaredIntent != null && declaredIntent != routedIntent) {
            error("intent ${declaredIntent.name} does not match actionId ${actionId.name}")
        }
        return GraphEditorMessage.RequestAssistantTask(
            intent = routedIntent,
            actionId = actionId,
            sceneId = payload.string("sceneId")?.takeIf(String::isNotBlank),
            prompt = payload.string("prompt").orEmpty(),
            selectedNodeIds = payload.stringList("selectedNodeIds"),
            selectedDiffItemIds = payload.stringList("selectedDiffItemIds"),
            target = parseAssistantComposerTarget(payload["target"] as? Map<*, *>),
            mode = payload.enumOrDefault("mode", QaMode.AUTO),
            explanationGranularity = payload.enumOrDefault("explanationGranularity", StepGranularity.BUSINESS),
        )
    }

    private fun parseAssistantComposerTarget(raw: Map<*, *>?): AssistantComposerTarget {
        raw ?: return AssistantComposerTarget.NewTask
        return when (val kind = raw.requiredString("kind", "assistant target kind")) {
            "NewTask" -> AssistantComposerTarget.NewTask
            "QaRecovery" -> AssistantComposerTarget.QaRecovery(
                requestId = raw.requiredString("requestId", "requestId"),
                selectedNodeIds = raw.stringList("selectedNodeIds"),
                sourceThreadId = raw.string("sourceThreadId")?.takeIf(String::isNotBlank),
                mode = raw.enumOrNull<QaMode>("mode"),
            )
            "ExplanationFollowUp" -> AssistantComposerTarget.ExplanationFollowUp(
                stepId = raw.requiredString("stepId", "stepId"),
                stepTitle = raw.string("stepTitle")?.takeIf(String::isNotBlank),
                focusNodeId = raw.string("focusNodeId")?.takeIf(String::isNotBlank),
            )
            "GenerationDiscussion" -> AssistantComposerTarget.GenerationDiscussion(
                planItemId = raw.string("planItemId")?.takeIf(String::isNotBlank),
            )
            "RiskInvestigation" -> AssistantComposerTarget.RiskInvestigation(
                threadId = raw.requiredString("threadId", "threadId"),
                targetNodeIds = raw.stringList("targetNodeIds"),
            )
            else -> error("unsupported assistant target kind: $kind")
        }
    }

    private fun parseLayoutPositions(payload: Map<*, *>): Map<String, GraphLayoutPosition> =
        payload.boundedList("positions", MAX_LAYOUT_POSITIONS)
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
            "requestAssistantTask" -> "AI 代码工作台"
            "retryLastQaRequest" -> "重试问答"
            "confirmQaCandidateChange" -> "确认问答候选变更"
            "unconfirmQaCandidateChange" -> "取消确认问答候选变更"
            "resolveInvestigationThread" -> "风险决策提交"
            "applyDraftPatchPreview" -> "应用草稿补丁预览"
            "clearDraftPatchPreview" -> "清空草稿补丁预览"
            "restoreDraftPatchPreview" -> "恢复草稿补丁预览"
            "undoLastDraftPatchApply" -> "撤销草稿补丁应用"
            "requestCodeDrafts" -> "请求代码草稿"
            "requestCurrentEditorContextGraph" -> "加载当前编辑器上下文链路"
            "requestAnalysisDisplayMode" -> "切换展示模式"
            "requestIndexedGraph" -> "加载 indexed 图"
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
            "collapseInvocationExpansion" -> "折叠调用展开"
            "openInvocationExpansion" -> "打开调用展开"
            "applyGraphEditScript" -> "链路图编辑请求同步"
            else -> type
        }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String

    private fun Map<*, *>.requiredString(key: String, description: String): String =
        string(key)?.takeIf(String::isNotBlank) ?: error("$description is required")

    private fun Map<*, *>.stringList(key: String): List<String> =
        boundedList(key, MAX_STRING_LIST_ITEMS).mapNotNull { value ->
            (value as? String)?.takeIf(String::isNotBlank)
        }

    private fun Map<*, *>.boundedList(key: String, maxItems: Int): List<*> {
        val values = (this[key] as? List<*>).orEmpty()
        require(values.size <= maxItems) {
            "$key contains too many items: ${values.size} > $maxItems"
        }
        return values
    }

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
