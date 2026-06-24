package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.CandidatePatchIntent
import com.charmnight.linkgraph.workbench.CandidatePatchIntentMode
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.intellij.openapi.diagnostic.Logger

/**
 * 解析远程 LLM 返回的图补丁结果。
 * 输出内容既包含自然语言回答，也可能附带结构化补丁操作。
 */
internal object RemoteGraphPatchResultParser {
    private val logger = Logger.getInstance(RemoteGraphPatchResultParser::class.java)
    private val traceEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE")

    /** 把远程响应解析为统一的补丁结果对象。 */
    fun parse(
        content: String,
        prompt: String,
        question: String,
    ): GraphPatchResult {
        /** 解析后的 JSON 根对象。 */
        val root = LlmJsonCodec.parseObject(unwrapJson(content))
        /** LLM 对用户问题的直接回答文本。 */
        val answer = root["answer"] as? String
            ?: root["summary"] as? String
            ?: error("LLM response must contain answer.")
        /** 远程返回的警告列表。 */
        val warnings = (root["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        /** 远程返回的结构化证据。 */
        val findings = parseResultEvidenceFindings(root["findings"])
        /** 远程返回的结构化补丁。 */
        val patch = (root["patch"] as? Map<*, *>)?.let(::parsePatch)
        val rawCandidateChanges = (root["candidateChanges"] as? List<*>).orEmpty()
        val rawInvestigationThreads = (root["investigationThreads"] as? List<*>).orEmpty()
        val parsedCandidateChanges = rawCandidateChanges.mapNotNull {
            parseCandidateChange(it as? Map<*, *>, findings)
        }
        val parsedInvestigationThreads = rawInvestigationThreads.mapNotNull {
            parseInvestigationThread(it as? Map<*, *>, findings)
        }
        if (traceEnabled) {
            logger.warn(
                "远程问答结构解析: findings=${findings.size}, rawCandidateChanges=${rawCandidateChanges.size}, " +
                    "parsedCandidateChanges=${parsedCandidateChanges.size}, rawInvestigationThreads=${rawInvestigationThreads.size}, " +
                    "parsedInvestigationThreads=${parsedInvestigationThreads.size}, patchOperations=${patch?.operations?.size ?: 0}, " +
                    "candidateSummaries=${candidateSummaries(parsedCandidateChanges)}",
            )
        }
        return GraphPatchResult(
            source = LlmResultSource.REMOTE,
            question = question,
            answer = answer,
            promptPreview = prompt,
            patch = patch,
            findings = findings,
            candidateChanges = parsedCandidateChanges,
            investigationThreads = parsedInvestigationThreads,
            warnings = warnings,
        )
    }

    /** 提取可能被 Markdown 代码块包裹的纯 JSON 文本。 */
    private fun unwrapJson(content: String): String {
        return RemoteStructuredJsonExtractor.extract(content)
    }

    /** 解析图补丁主体。 */
    private fun parsePatch(raw: Map<*, *>): GraphPatch {
        return GraphPatch(
            summary = raw["summary"] as? String,
            operations = (raw["operations"] as? List<*>).orEmpty().mapNotNull { parseOperation(it as? Map<*, *>) },
            addedNodeIds = stringList(raw["addedNodeIds"]),
            removedNodeIds = stringList(raw["removedNodeIds"]),
            addedEdgeIds = stringList(raw["addedEdgeIds"]),
            removedEdgeIds = stringList(raw["removedEdgeIds"]),
        )
    }

    /** 解析单条候选变更。 */
    private fun parseCandidateChange(
        raw: Map<*, *>?,
        findings: List<ResultEvidenceFinding>,
    ): CandidateDraftChange? {
        raw ?: return null
        val changeId = raw["changeId"] as? String ?: return null
        val findingsById = findings.associateBy(ResultEvidenceFinding::id)
        val supportingEvidence = stringList(raw["supportingFindingIds"]).mapNotNull(findingsById::get)
        val embeddedEvidence = parseResultEvidenceFindings(raw["evidence"])
        return CandidateDraftChange(
            changeId = changeId,
            status = enumValue<CandidateDraftChangeStatus>(raw["status"] as? String)
                ?: CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            title = raw["title"] as? String ?: changeId,
            targetStepIds = stringList(raw["targetStepIds"]),
            targetNodeIds = stringList(raw["targetNodeIds"]),
            beforeState = raw["beforeState"] as? String,
            afterState = raw["afterState"] as? String,
            reason = raw["reason"] as? String ?: "",
            impactSummary = raw["impactSummary"] as? String ?: "",
            claimType = raw["claimType"] as? String,
            evidence = if (supportingEvidence.isNotEmpty()) supportingEvidence else embeddedEvidence,
            patchIntent = (raw["patchIntent"] as? Map<*, *>)?.let(::parsePatchIntent),
            graphPatch = (raw["graphPatch"] as? Map<*, *>)?.let(::parsePatch),
        )
    }

    /** 解析单条风险线索。 */
    private fun parseInvestigationThread(
        raw: Map<*, *>?,
        findings: List<ResultEvidenceFinding>,
    ): InvestigationThread? {
        raw ?: return null
        val threadId = raw["threadId"] as? String ?: return null
        val findingsById = findings.associateBy(ResultEvidenceFinding::id)
        val supportingEvidence = stringList(raw["supportingFindingIds"]).mapNotNull(findingsById::get)
        val embeddedEvidence = parseResultEvidenceFindings(raw["evidence"])
        return InvestigationThread(
            threadId = threadId,
            status = enumValue<InvestigationThreadStatus>(raw["status"] as? String)
                ?: InvestigationThreadStatus.OPEN,
            title = raw["title"] as? String ?: threadId,
            targetStepIds = stringList(raw["targetStepIds"]),
            targetNodeIds = stringList(raw["targetNodeIds"]),
            summary = raw["summary"] as? String ?: "",
            evidenceGap = raw["evidenceGap"] as? String ?: "",
            recommendedQuestion = raw["recommendedQuestion"] as? String ?: "",
            claimType = raw["claimType"] as? String,
            evidence = if (supportingEvidence.isNotEmpty()) supportingEvidence else embeddedEvidence,
        )
    }

    /** 解析单条补丁操作。 */
    private fun parseOperation(raw: Map<*, *>?): GraphPatchOperation? {
        raw ?: return null
        /** 补丁动作类型。 */
        val action = enumValue<GraphPatchAction>(raw["action"] as? String) ?: return null
        /** 变更元素类型，默认视为节点。 */
        val elementKind = enumValue<GraphDiffElementKind>(raw["elementKind"] as? String) ?: GraphDiffElementKind.NODE
        return GraphPatchOperation(
            id = raw["id"] as? String ?: return null,
            action = action,
            elementKind = elementKind,
            elementId = raw["elementId"] as? String ?: return null,
            title = raw["title"] as? String,
            summary = raw["summary"] as? String,
            node = parseNode(raw["node"] as? Map<*, *>),
            edge = parseEdge(raw["edge"] as? Map<*, *>),
            metadata = stringMap(raw["metadata"]),
        )
    }

    /** 解析候选变更附带的 patchIntent 对象。 */
    private fun parsePatchIntent(raw: Map<*, *>?): CandidatePatchIntent? {
        raw ?: return null
        val mode = enumValue<CandidatePatchIntentMode>(raw["mode"] as? String) ?: return null
        return CandidatePatchIntent(
            mode = mode,
            targetNodeId = raw["targetNodeId"] as? String,
            attachEdgeId = raw["attachEdgeId"] as? String,
            falseBranchTargetNodeId = raw["falseBranchTargetNodeId"] as? String,
        )
    }

    /** 解析补丁中的节点定义。 */
    private fun parseNode(raw: Map<*, *>?): GraphNode? {
        raw ?: return null
        /** 节点类型，缺失时回退到文档页。 */
        val type = enumValue<NodeType>(raw["type"] as? String) ?: NodeType.DOC_PAGE
        return GraphNode(
            id = raw["id"] as? String ?: return null,
            type = type,
            title = raw["title"] as? String ?: return null,
            location = raw["location"] as? String,
            signature = raw["signature"] as? String,
            inputs = stringList(raw["inputs"]),
            outputs = stringList(raw["outputs"]),
            doc = raw["doc"] as? String,
            bindingStatus = enumValue<BindingStatus>(raw["bindingStatus"] as? String) ?: BindingStatus.DESIGN_ONLY,
            certainty = enumValue<Certainty>(raw["certainty"] as? String) ?: Certainty.LLM_SUGGESTED,
            metadata = stringMap(raw["metadata"]),
            sourceTag = enumValue<GraphSourceTag>(raw["sourceTag"] as? String) ?: GraphSourceTag.DRAFT_AI,
        )
    }

    /** 解析补丁中的边定义。 */
    private fun parseEdge(raw: Map<*, *>?): GraphEdge? {
        raw ?: return null
        /** 边类型，缺失时回退到 `GENERATES`。 */
        val type = enumValue<EdgeType>(raw["type"] as? String) ?: EdgeType.GENERATES
        return GraphEdge(
            id = raw["id"] as? String ?: return null,
            type = type,
            fromNodeId = raw["fromNodeId"] as? String ?: return null,
            toNodeId = raw["toNodeId"] as? String ?: return null,
            label = raw["label"] as? String,
            bindingStatus = enumValue<BindingStatus>(raw["bindingStatus"] as? String) ?: BindingStatus.DESIGN_ONLY,
            certainty = enumValue<Certainty>(raw["certainty"] as? String) ?: Certainty.LLM_SUGGESTED,
            metadata = stringMap(raw["metadata"]),
            sourceTag = enumValue<GraphSourceTag>(raw["sourceTag"] as? String) ?: GraphSourceTag.DRAFT_AI,
        )
    }

    /** 把任意 JSON 数组安全转换成字符串列表。 */
    private fun stringList(raw: Any?): List<String> {
        return (raw as? List<*>).orEmpty().mapNotNull { it as? String }
    }

    /** 把任意 JSON 对象安全转换成字符串映射。 */
    private fun stringMap(raw: Any?): Map<String, String> {
        return (raw as? Map<*, *>).orEmpty().mapNotNull { (key, value) ->
            /** 当前条目的字符串键。 */
            val stringKey = key as? String ?: return@mapNotNull null
            /** 当前条目的字符串值。 */
            val stringValue = value as? String ?: return@mapNotNull null
            stringKey to stringValue
        }.toMap()
    }

    /** 按枚举名称做安全解析。 */
    private inline fun <reified T : Enum<T>> enumValue(name: String?): T? {
        return enumValueByName<T>(name)
    }

    /** 生成候选变更摘要字符串，用于 trace 日志输出。 */
    private fun candidateSummaries(candidates: List<CandidateDraftChange>): String {
        if (candidates.isEmpty()) {
            return "[]"
        }
        return candidates.take(3).joinToString(
            prefix = "[",
            postfix = if (candidates.size > 3) ", ...]" else "]",
        ) { candidate ->
            buildString {
                append(candidate.changeId)
                append(":evidence=")
                append(candidate.evidence.size)
                append(':')
                append(candidate.evidence.joinToString("|") { evidence -> evidence.evidenceLevel.name })
            }
        }
    }
}

/**
 * 判断当前设置是否具备远程补丁生成能力。
 * 仅当远程连接配置完整可用时返回 true，可用于决定是否启用相关远程功能。
 */
internal fun LinkGraphSettingsState.isRemotePatchReady(): Boolean {
    return remoteConnectionOrNull() != null
}
