package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.StepGranularity
import com.charmnight.linkgraph.workbench.StepKind
import com.charmnight.linkgraph.workbench.StepProjectionService
import com.charmnight.linkgraph.workbench.WorkbenchStep

/**
 * 为“链路 + 代码 -> 可读性美化/解释”预留的稳定服务接口。
 * 当前先用本地规则输出稳定可读的解释结果，后续可平滑接入远程 LLM。
 */
interface GraphBeautificationService {
    /** 基于链路讲解上下文生成说明结果。 */
    fun beautify(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GraphBeautificationResult
}

class DefaultGraphBeautificationService(
    /** 负责构造讲解提示词。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责发起远程 LLM 请求。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
    /** 负责构造稳定步骤。 */
    private val stepProjectionService: StepProjectionService = StepProjectionService(),
    /** 远程不可用时使用的本地讲解服务。 */
    private val fallbackService: GraphBeautificationService =
        PlaceholderGraphBeautificationService(promptFactory, stepProjectionService),
) : GraphBeautificationService {
    /** 负责处理结构化 JSON 响应与自动修复。 */
    private val responseSupport = RemoteStructuredResponseSupport(gateway)

    /** 优先调用远程讲解，失败时自动回退到本地规则。 */
    override fun beautify(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)?,
    ): GraphBeautificationResult {
        /** 清洗后的生成设置。 */
        val sanitized = settings.sanitized()
        /** 当前讲解步骤。 */
        val projectedSteps = stepProjectionService.buildSteps(
            factGraph = context.presentationContext.graph,
            draftEntries = emptyList(),
            granularity = context.granularity,
        ).steps
        /** 链路讲解提示词包。 */
        val promptPackage = promptFactory.buildBeautificationPromptPackage(context, sanitized, projectedSteps)
        if (!sanitized.usesRemoteProvider()) {
            return fallbackService.beautify(context, sanitized, onPreview)
        }
        /** 远程连接参数。 */
        val remoteConnection = sanitized.remoteConnectionOrNull()
        if (remoteConnection == null) {
            /** 本地讲解结果。 */
            val fallbackResult = fallbackService.beautify(context, sanitized, onPreview)
            return fallbackResult.copy(
                warnings = listOf(sanitized.remoteLlmSetupHint("本地规则讲解")) + fallbackResult.warnings,
            )
        }
        return runCatching {
            responseSupport.request(
                remoteConnection.toRequest(
                    systemPrompt = promptPackage.systemPrompt,
                    userPrompt = promptPackage.userPrompt,
                ),
                scene = "链路讲解",
                schema = BEAUTIFICATION_SCHEMA,
                preferStreaming = remoteConnection.preset.capabilities.supportsStreaming,
                onPreview = onPreview,
            ) { content ->
                RemoteGraphBeautificationResultParser.parse(content, promptPackage.preview)
            }
        }.map { remote ->
            remote.value.withPrependedWarnings(remote.warnings)
        }.getOrElse { error ->
            /** 本地讲解结果。 */
            val fallbackResult = fallbackService.beautify(context, sanitized, onPreview)
            fallbackResult.copy(
                warnings = listOf(
                    buildRemoteFallbackWarning(error),
                ) + fallbackResult.warnings,
            )
        }
    }

    /** 生成远程失败后的回退警告文案。 */
    private fun buildRemoteFallbackWarning(error: Throwable): String {
        return "远程 LLM 链路讲解失败，已回退为本地规则讲解：${LlmUserMessageFormatter.describe(error)}"
    }

    /** 把远程返回的警告插到结果前面。 */
    private fun GraphBeautificationResult.withPrependedWarnings(extraWarnings: List<String>): GraphBeautificationResult {
        if (extraWarnings.isEmpty()) {
            return this
        }
        return copy(warnings = extraWarnings + warnings)
    }

    private companion object {
        /** 远程链路讲解返回必须遵守的 JSON 结构。 */
        private const val BEAUTIFICATION_SCHEMA = """
{
  "steps": [
    {
      "stepId": "稳定ID",
      "title": "步骤标题",
      "description": "步骤说明",
      "followUpQuestions": ["可继续追问的问题"],
      "evidence": [
        {
          "id": "稳定ID",
          "claim": "一条必须可追溯的关键结论",
          "evidenceLevel": "DIRECT_SOURCE|DIRECT_GRAPH|CALLSITE_ONLY|NOT_OBSERVED",
          "references": [
            {
              "nodeId": "可选节点ID",
              "filePath": "可选源码路径",
              "startLine": 1,
              "endLine": 3
            }
          ]
        }
      ],
      "downstreamTargets": ["可继续下钻的目标ID"]
    }
  ],
  "warnings": ["可选警告"]
}
"""
    }
}

class PlaceholderGraphBeautificationService(
    /** 负责构造讲解提示词预览。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责构造稳定步骤。 */
    private val stepProjectionService: StepProjectionService = StepProjectionService(),
) : GraphBeautificationService {
    /** 基于本地规则生成稳定可读的链路讲解。 */
    override fun beautify(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)?,
    ): GraphBeautificationResult {
        /** 当前展示上下文。 */
        val presentation = context.presentationContext
        /** 当前可见图。 */
        val visibleGraph = presentation.graph
        /** 完整背景图。 */
        val fullGraph = presentation.fullGraph
        /** 当前讲解锚点节点。 */
        val anchorNode = resolveAnchorNode(context)
        /** 当前可见节点 ID 集合。 */
        val visibleNodeIds = visibleGraph.nodes.map(GraphNode::id).toSet()
        /** 当前图外仍可继续展开的跨方法节点。 */
        val hiddenCrossMethodNodes = fullGraph.nodes.filter { node ->
            node.id !in visibleNodeIds &&
                node.type == NodeType.METHOD &&
                node.signature != anchorNode?.signature
        }
        /** 当前投影出的稳定步骤。 */
        val projectedSteps = stepProjectionService.buildSteps(
            factGraph = visibleGraph,
            draftEntries = emptyList(),
            granularity = context.granularity,
        )
        /** 链路讲解提示词包，仅用于展示预览。 */
        val promptPackage = promptFactory.buildBeautificationPromptPackage(context, settings, projectedSteps.steps)
        /** 步骤化讲解结果。 */
        val steps = projectedSteps.steps.map { step ->
            toBeautificationStep(
                step = step,
                context = context,
                hiddenCrossMethodNodes = hiddenCrossMethodNodes,
            )
        }
        /** 当前讲解附带的警告列表。 */
        val warnings = buildList {
            if (presentation.hiddenCurrentMethodNodeCount > 0) {
                add("当前方法内部仍有 ${presentation.hiddenCurrentMethodNodeCount} 个节点未展开，当前讲解只覆盖已展示部分。")
            }
            if (presentation.hiddenCrossMethodNodeCount > 0) {
                add("跨方法扩展仍有 ${presentation.hiddenCrossMethodNodeCount} 个节点未展开，可继续展开后再审阅完整链路。")
            }
            context.preferredStyle?.trim()?.takeIf(String::isNotBlank)?.let { style ->
                add("当前讲解已按“$style”风格整理。")
            }
        }
        return GraphBeautificationResult(
            source = LlmResultSource.MOCK,
            granularity = context.granularity,
            steps = steps,
            promptPreview = promptPackage.preview,
            warnings = warnings,
        )
    }

    /** 在当前展示图和完整图里解析讲解锚点节点。 */
    private fun resolveAnchorNode(context: GraphBeautificationContext): GraphNode? {
        /** 当前展示上下文。 */
        val presentation = context.presentationContext
        return presentation.graph.nodes.firstOrNull { it.id == presentation.anchorNodeId }
            ?: presentation.graph.nodes.firstOrNull { it.type == NodeType.METHOD }
            ?: presentation.fullGraph.nodes.firstOrNull { it.id == presentation.anchorNodeId }
            ?: presentation.fullGraph.nodes.firstOrNull { it.type == NodeType.METHOD }
            ?: presentation.graph.nodes.firstOrNull()
    }

    /** 把投影步骤补齐成可直接展示的讲解步骤。 */
    private fun toBeautificationStep(
        step: WorkbenchStep,
        context: GraphBeautificationContext,
        hiddenCrossMethodNodes: List<GraphNode>,
    ): GraphBeautificationStep {
        /** 关联源码片段。 */
        val snippets = context.sourceContext.filter { snippet -> snippet.nodeId in step.nodeRefs }
        /** 关联图节点。 */
        val nodes = context.presentationContext.graph.nodes.filter { node -> node.id in step.nodeRefs }
        /** 可下钻目标。 */
        val downstreamTargets = step.downstreamTargets.ifEmpty {
            if (step.kind == StepKind.RETURN) {
                emptyList()
            } else {
                hiddenCrossMethodNodes.take(2).map(GraphNode::id)
            }
        }
        return GraphBeautificationStep(
            stepId = step.stepId,
            title = step.title,
            granularity = step.granularity,
            kind = step.kind,
            description = buildStepDescription(step, snippets.mapNotNull(SourceSnippetContext::snippet), nodes.map(GraphNode::title)),
            evidence = buildStepEvidence(step, snippets, nodes),
            followUpQuestions = buildFollowUpQuestions(step, downstreamTargets),
            downstreamTargets = downstreamTargets,
        )
    }

    /** 生成单步说明文本。 */
    private fun buildStepDescription(
        step: WorkbenchStep,
        snippets: List<String>,
        nodeTitles: List<String>,
    ): String {
        val snippetText = snippets
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .firstOrNull(String::isNotBlank)
        return when {
            snippetText != null -> "${step.title}。当前代码直接执行：$snippetText"
            step.kind == StepKind.RETURN -> "${step.title}。这里结束当前链路并返回结果。"
            nodeTitles.isNotEmpty() -> "${step.title}。当前步骤主要围绕 ${nodeTitles.joinToString("、")} 展开。"
            else -> "${step.title}。当前只拿到了图级步骤骨架，尚未命中更细的源码片段。"
        }
    }

    /** 为单个步骤构造证据。 */
    private fun buildStepEvidence(
        step: WorkbenchStep,
        snippets: List<SourceSnippetContext>,
        nodes: List<GraphNode>,
    ): List<ResultEvidenceFinding> {
        val sourceFindings = snippets.mapIndexed { index, snippet ->
            val node = nodes.firstOrNull { it.id == snippet.nodeId }
            ResultEvidenceFinding(
                id = "${step.stepId}-source-$index",
                claim = "当前步骤直接展示了节点“${node?.title ?: step.title}”。",
                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                references = listOf(
                    ResultEvidenceReference(
                        nodeId = snippet.nodeId,
                        filePath = snippet.filePath,
                        startLine = snippet.startLine,
                        endLine = snippet.endLine,
                    ),
                ),
            )
        }
        if (sourceFindings.isNotEmpty()) {
            return sourceFindings
        }
        return nodes.take(2).mapIndexed { index, node ->
            ResultEvidenceFinding(
                id = "${step.stepId}-graph-$index",
                claim = "当前步骤直接关联了图节点“${node.title}”。",
                evidenceLevel = ResultEvidenceLevel.DIRECT_GRAPH,
                references = listOf(ResultEvidenceReference(nodeId = node.id)),
            )
        }
    }

    /** 生成可继续追问的建议问题。 */
    private fun buildFollowUpQuestions(
        step: WorkbenchStep,
        downstreamTargets: List<String>,
    ): List<String> {
        return buildList {
            add("这一步的输入参数是从哪里来的？")
            if (step.kind != StepKind.RETURN) {
                add("这一步失败时会影响什么结果？")
            }
            if (downstreamTargets.isNotEmpty()) {
                add("这一步继续下钻后会进入哪个被调方法？")
            }
        }.distinct()
    }
}
