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
                schema = LlmStructuredSchemas.BEAUTIFICATION,
                preferStreaming = remoteConnection.preset.capabilities.supportsStreaming,
                onPreview = onPreview,
            ) { content ->
                RemoteGraphBeautificationResultParser.parse(content, promptPackage.preview)
            }
        }.map { remote ->
            val localBaseline = fallbackService.beautify(context, sanitized, onPreview = null)
            remote.value
                .hydrateStepMetadata(localBaseline, context.granularity)
                .withPrependedWarnings(remote.warnings)
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

    /**
     * 用本地规则已知的源码和节点元数据补齐远程步骤，避免展示字段在远程链路中丢失。
     */
    private fun GraphBeautificationResult.hydrateStepMetadata(
        baseline: GraphBeautificationResult,
        requestedGranularity: StepGranularity,
    ): GraphBeautificationResult {
        val baselineByStepId = baseline.steps.associateBy(GraphBeautificationStep::stepId)
        return copy(
            granularity = requestedGranularity,
            steps = steps.map { step ->
                val localStep = baselineByStepId[step.stepId]
                step.copy(
                    granularity = localStep?.granularity ?: requestedGranularity,
                    kind = localStep?.kind ?: step.kind,
                    primaryNodeId = step.primaryNodeId ?: localStep?.primaryNodeId,
                    codeSnippet = step.codeSnippet ?: localStep?.codeSnippet,
                    evidence = step.evidence.ifEmpty { localStep?.evidence.orEmpty() },
                    followUpQuestions = step.followUpQuestions.ifEmpty { localStep?.followUpQuestions.orEmpty() },
                    downstreamTargets = step.downstreamTargets.ifEmpty { localStep?.downstreamTargets.orEmpty() },
                )
            },
        )
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
        /** 追问场景下优先聚焦当前步骤。 */
        val focusedSteps = context.followUp?.let { followUp ->
            projectedSteps.steps.filter { step -> step.stepId == followUp.stepId }
        }?.takeIf { it.isNotEmpty() } ?: projectedSteps.steps
        /** 链路讲解提示词包，仅用于展示预览。 */
        val promptPackage = promptFactory.buildBeautificationPromptPackage(context, settings, projectedSteps.steps)
        /** 步骤化讲解结果。 */
        val steps = focusedSteps.map { step ->
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
            context.followUp?.let { followUp ->
                add("当前结果已聚焦步骤“${followUp.stepTitle}”的追问：${followUp.question}")
            }
        }
        return GraphBeautificationResult(
            source = LlmResultSource.LOCAL_RULE,
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
            description = buildStepDescription(
                step = step,
                snippets = snippets.mapNotNull(SourceSnippetContext::snippet),
                nodeTitles = nodes.map(GraphNode::title),
                followUp = context.followUp?.takeIf { followUp -> followUp.stepId == step.stepId },
            ),
            primaryNodeId = step.nodeRefs.firstOrNull(),
            codeSnippet = snippets.mapNotNull(SourceSnippetContext::snippet).firstOrNull(),
            evidence = buildStepEvidence(step, snippets, nodes),
            followUpQuestions = buildFollowUpQuestions(
                step = step,
                downstreamTargets = downstreamTargets,
                followUp = context.followUp?.takeIf { followUp -> followUp.stepId == step.stepId },
            ),
            downstreamTargets = downstreamTargets,
        )
    }

    /** 生成单步说明文本。 */
    private fun buildStepDescription(
        step: WorkbenchStep,
        snippets: List<String>,
        nodeTitles: List<String>,
        followUp: GraphBeautificationFollowUpContext?,
    ): String {
        val snippetText = snippets
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .firstOrNull(String::isNotBlank)
        if (followUp != null) {
            return when {
                snippetText != null ->
                    "针对追问“${followUp.question}”，当前步骤直接执行：$snippetText"
                step.kind == StepKind.RETURN ->
                    "针对追问“${followUp.question}”，这里结束当前链路并返回结果；是否存在额外分支，当前证据不足以确认。"
                nodeTitles.isNotEmpty() ->
                    "针对追问“${followUp.question}”，当前只确认这一步围绕 ${nodeTitles.joinToString("、")} 展开；更细的条件和异常处理不足以确认。"
                else ->
                    "针对追问“${followUp.question}”，当前证据不足以确认具体逻辑，需要继续展开源码或下钻实现。"
            }
        }
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
        followUp: GraphBeautificationFollowUpContext?,
    ): List<String> {
        if (followUp != null) {
            return buildList {
                add("这一步对应的代码位置具体在哪里？")
                add("这里真正的判断条件或前置校验是什么？")
                if (downstreamTargets.isNotEmpty()) {
                    add("如果继续下钻，这一步会进入哪个被调方法？")
                }
            }.distinct()
        }
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
