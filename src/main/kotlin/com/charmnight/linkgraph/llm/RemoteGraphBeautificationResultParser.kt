package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.workbench.StepGranularity
import com.charmnight.linkgraph.workbench.StepKind

/**
 * 解析远程 LLM 返回的链路讲解结果。
 * 当前改为解析步骤化讲解结果。
 */
internal object RemoteGraphBeautificationResultParser {
    /** 把远程返回的 JSON 文本解析为讲解结果对象。 */
    fun parse(
        content: String,
        prompt: String,
    ): GraphBeautificationResult {
        /** 解析后的 JSON 根对象。 */
        val root = LlmJsonCodec.parseObject(RemoteStructuredJsonExtractor.extract(content))
        /** 远程返回的步骤化讲解列表。 */
        val steps = (root["steps"] as? List<*>).orEmpty().mapIndexedNotNull { index, raw ->
            parseStep(raw as? Map<*, *>, index)
        }
        return GraphBeautificationResult(
            source = LlmResultSource.REMOTE,
            granularity = StepGranularity.BUSINESS,
            steps = steps,
            promptPreview = prompt,
            warnings = (root["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String },
        )
    }

    /** 解析单个步骤。 */
    private fun parseStep(
        raw: Map<*, *>?,
        index: Int,
    ): GraphBeautificationStep? {
        raw ?: return null
        /** 步骤标题。 */
        val title = raw["title"] as? String ?: return null
        /** 步骤说明。 */
        val description = raw["description"] as? String ?: return null
        /** 步骤稳定标识，缺失时回退到序号。 */
        val stepId = raw["stepId"] as? String ?: "step-$index"
        /** 步骤类型，缺失或未知时兼容旧响应。 */
        val kind = (raw["kind"] as? String)
            ?.let { runCatching { StepKind.valueOf(it) }.getOrNull() }
            ?: StepKind.BUSINESS_ACTION
        return GraphBeautificationStep(
            stepId = stepId,
            title = title,
            granularity = StepGranularity.BUSINESS,
            kind = kind,
            description = description,
            evidence = parseResultEvidenceFindings(raw["evidence"]),
            followUpQuestions = (raw["followUpQuestions"] as? List<*>).orEmpty().mapNotNull { it as? String },
            downstreamTargets = (raw["downstreamTargets"] as? List<*>).orEmpty().mapNotNull { it as? String },
        )
    }
}
