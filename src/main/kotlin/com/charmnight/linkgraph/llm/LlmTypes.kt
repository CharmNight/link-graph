package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewRisk

/**
 * 生成计划的上下文快照。
 * 当前不直接产出代码，而是把图、问题、diff 与同步预览整理成统一输入。
 */
data class GenerationContext(
    /** 保存当前分析或生成使用的图。 */
    val graph: GraphDocument = GraphDocument(),
    /** 保存 Mermaid 校验问题列表。 */
    val mermaidIssues: List<MermaidIssue> = emptyList(),
    /** 保存设计与事实之间的差异信息。 */
    val diff: GraphDiff = GraphDiff(),
    /** 保存同步预览条目列表。 */
    val syncPreviewItems: List<SyncPreviewItem> = emptyList(),
)

/**
 * 封装图审计场景所需的输入上下文。
 */
data class GraphAuditContext(
    /** 保存事实图。 */
    val factGraph: GraphDocument = GraphDocument(),
    /** 保存草稿图。 */
    val draftGraph: GraphDocument = GraphDocument(),
    /** 保存当前选中的节点标识列表。 */
    val selectedNodeIds: List<String> = emptyList(),
)

/**
 * 封装图差异评审所需的输入上下文。
 */
data class GraphDiffContext(
    /** 保存事实图。 */
    val factGraph: GraphDocument = GraphDocument(),
    /** 保存设计基线图。 */
    val designBaseline: GraphDocument = GraphDocument(),
    /** 保存整体差异信息。 */
    val diff: GraphDiff = GraphDiff(),
    /** 保存当前选中的差异条目标识列表。 */
    val selectedDiffItemIds: List<String> = emptyList(),
)

/**
 * 封装图讲解和生成场景使用的展示上下文。
 */
data class GraphPresentationContext(
    /** 保存当前展示图。 */
    val graph: GraphDocument = GraphDocument(),
    /** 保存完整图。 */
    val fullGraph: GraphDocument = GraphDocument(),
    /** 保存锚点节点标识。 */
    val anchorNodeId: String? = null,
    /** 保存当前选中的节点标识列表。 */
    val selectedNodeIds: List<String> = emptyList(),
    /** 记录当前方法下被隐藏的节点数量。 */
    val hiddenCurrentMethodNodeCount: Int = 0,
    /** 记录跨方法被隐藏的节点数量。 */
    val hiddenCrossMethodNodeCount: Int = 0,
)

/**
 * 描述与图节点关联的源码片段。
 */
data class SourceSnippetContext(
    /** 保存关联的节点标识。 */
    val nodeId: String,
    /** 保存源码文件路径。 */
    val filePath: String,
    /** 保存片段起始偏移量。 */
    val startOffset: Int? = null,
    /** 保存片段结束偏移量。 */
    val endOffset: Int? = null,
    /** 保存片段起始行号。 */
    val startLine: Int? = null,
    /** 保存片段结束行号。 */
    val endLine: Int? = null,
    /** 保存可直接展示的代码片段。 */
    val snippet: String? = null,
)

/**
 * 封装图讲解所需的完整上下文。
 */
data class GraphBeautificationContext(
    /** 保存图展示上下文。 */
    val presentationContext: GraphPresentationContext = GraphPresentationContext(),
    /** 保存相关源码片段上下文。 */
    val sourceContext: List<SourceSnippetContext> = emptyList(),
    /** 保存用户目标。 */
    val userGoal: String = "",
    /** 保存讲解风格偏好。 */
    val preferredStyle: String? = null,
    /** 保存讲解关注点。 */
    val explanationFocus: String? = null,
)

/**
 * 表示讲解结果中的一个章节。
 */
data class GraphBeautificationSection(
    /** 保存章节标识。 */
    val id: String,
    /** 保存章节标题。 */
    val title: String,
    /** 保存章节正文内容。 */
    val content: String,
)

/**
 * 定义结果结论的证据等级。
 */
enum class ResultEvidenceLevel {
    /** 直接来自当前已提供的源码片段。 */
    DIRECT_SOURCE,

    /** 直接来自当前图节点或图连线，但没有源码片段佐证。 */
    DIRECT_GRAPH,

    /** 当前只展示了调用点，没有展示被调目标的实现体。 */
    CALLSITE_ONLY,

    /** 当前上下文没有直接观察到该行为或该结论所需证据。 */
    NOT_OBSERVED,
}

/**
 * 指向一条结果证据的具体引用。
 */
data class ResultEvidenceReference(
    /** 保存关联节点标识。 */
    val nodeId: String? = null,
    /** 保存源码文件路径。 */
    val filePath: String? = null,
    /** 保存起始行号。 */
    val startLine: Int? = null,
    /** 保存结束行号。 */
    val endLine: Int? = null,
)

/**
 * 表示一条结构化结论及其证据边界。
 */
data class ResultEvidenceFinding(
    /** 保存结论标识。 */
    val id: String,
    /** 保存结论文本。 */
    val claim: String,
    /** 保存证据等级。 */
    val evidenceLevel: ResultEvidenceLevel,
    /** 保存结论引用。 */
    val references: List<ResultEvidenceReference> = emptyList(),
)

/**
 * 定义生成计划的来源。
 */
enum class GenerationPlanSource {
    /** 用户显式关闭远程 LLM，仅保留禁用状态说明。 */
    DISABLED,

    /** 使用本地规则和 sync preview 生成计划，不请求远程模型。 */
    MOCK,

    /** 请求远程 LLM 后解析得到的计划。 */
    REMOTE,
}

/**
 * 定义 LLM 结果的来源。
 */
enum class LlmResultSource {
    /** 表示远程能力已关闭。 */
    DISABLED,
    /** 表示结果来自本地 Mock 或规则推断。 */
    MOCK,
    /** 表示结果来自远程模型。 */
    REMOTE,
}

/** 单条计划项，表示一个候选改动或待生成文件。 */
data class GenerationPlanItem(
    /** 保存计划项标识。 */
    val id: String,
    /** 保存计划项标题。 */
    val title: String,
    /** 保存计划项描述。 */
    val description: String,
    /** 保存计划项风险等级。 */
    val risk: SyncPreviewRisk,
    /** 保存目标文件路径。 */
    val targetPath: String? = null,
)

/** 生成计划总结果，前端会据此展示摘要、风险和 prompt 预览。 */
data class GenerationPlan(
    /** 保存计划来源。 */
    val source: GenerationPlanSource,
    /** 保存计划摘要。 */
    val summary: String,
    /** 保存计划项列表。 */
    val items: List<GenerationPlanItem> = emptyList(),
    /** 保存警告列表。 */
    val warnings: List<String> = emptyList(),
    /** 保存提示词预览。 */
    val promptPreview: String = "",
)

/**
 * 表示图补丁问答或生成结果。
 */
data class GraphPatchResult(
    /** 保存结果来源。 */
    val source: LlmResultSource,
    /** 保存用户问题。 */
    val question: String,
    /** 保存模型回答。 */
    val answer: String,
    /** 保存提示词预览。 */
    val promptPreview: String,
    /** 保存生成的图补丁。 */
    val patch: GraphPatch? = null,
    /** 保存结构化证据结论。 */
    val findings: List<ResultEvidenceFinding> = emptyList(),
    /** 保存警告列表。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 表示图讲解结果。
 */
data class GraphBeautificationResult(
    /** 保存结果来源。 */
    val source: LlmResultSource,
    /** 保存摘要标题。 */
    val summaryTitle: String,
    /** 保存摘要正文。 */
    val summary: String,
    /** 保存章节列表。 */
    val sections: List<GraphBeautificationSection> = emptyList(),
    /** 保存结构化证据结论。 */
    val findings: List<ResultEvidenceFinding> = emptyList(),
    /** 保存提示词预览。 */
    val promptPreview: String = "",
    /** 保存警告列表。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 保存发往 LLM 的系统提示词、用户提示词和预览文本。
 */
data class LlmPromptPackage(
    /** 保存系统提示词。 */
    val systemPrompt: String,
    /** 保存用户提示词。 */
    val userPrompt: String,
    /** 保存用于界面展示的预览文本。 */
    val preview: String,
)

/** 描述一次远程请求期望的交付方式。 */
enum class LlmDeliveryMode {
    /** 采用一次性完整返回。 */
    FULL,
    /** 优先采用流式输出。 */
    STREAM,
}

/** 发给远程兼容接口的最小请求模型。 */
data class LlmRequest(
    /** 保存请求协议。 */
    val protocol: LlmWireProtocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
    /** 保存请求端点。 */
    val endpoint: String,
    /** 保存鉴权密钥。 */
    val apiKey: String,
    /** 保存模型名称。 */
    val model: String,
    /** 保存超时时间，单位为秒。 */
    val timeoutSeconds: Int,
    /** 保存采样温度。 */
    val temperature: Double,
    /** 保存系统提示词。 */
    val systemPrompt: String,
    /** 保存用户提示词。 */
    val userPrompt: String,
    /** 描述当前请求期望的交付方式。 */
    val deliveryMode: LlmDeliveryMode = LlmDeliveryMode.FULL,
)

/** 远程模型的最小响应模型，只保留当前一期会消费的字段。 */
data class LlmResponse(
    /** 保存模型返回的内容。 */
    val content: String,
    /** 保存实际命中的模型名。 */
    val model: String,
    /** 保存原始响应体，便于调试。 */
    val rawBody: String? = null,
)

/** 表示流式执行过程中的标准化事件。 */
sealed interface LlmStreamEvent {
    /** 表示流式请求已开始。 */
    data class Started(
        val model: String? = null,
    ) : LlmStreamEvent

    /** 表示收到一段文本增量。 */
    data class TextDelta(
        val text: String,
    ) : LlmStreamEvent

    /** 表示流式请求已完成。 */
    data class Completed(
        val response: LlmResponse,
    ) : LlmStreamEvent

    /** 表示流式请求失败。 */
    data class Failed(
        val error: Throwable,
    ) : LlmStreamEvent
}

/** LLM 网关抽象，便于在 MOCK、测试桩和真实 provider 之间切换。 */
interface LlmGateway {
    /**
     * 向目标 LLM 发起生成请求。
     */
    fun generate(request: LlmRequest): LlmResponse

    /**
     * 以流式方式执行请求，默认回退为完整返回。
     */
    fun stream(
        request: LlmRequest,
        listener: (LlmStreamEvent) -> Unit,
    ): LlmResponse {
        val response = generate(request.copy(deliveryMode = LlmDeliveryMode.FULL))
        listener(LlmStreamEvent.Started(model = response.model))
        if (response.content.isNotEmpty()) {
            listener(LlmStreamEvent.TextDelta(response.content))
        }
        listener(LlmStreamEvent.Completed(response))
        return response
    }
}
