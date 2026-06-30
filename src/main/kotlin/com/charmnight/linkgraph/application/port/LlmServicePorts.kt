package com.charmnight.linkgraph.application.port

import com.charmnight.linkgraph.agent.model.GenerationContext
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GraphBeautificationContext
import com.charmnight.linkgraph.agent.model.GraphBeautificationResult
import com.charmnight.linkgraph.agent.model.GraphDiffContext
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMode

/** 暴露给应用层的 LLM 服务端口集合，由基础设施层提供具体实现。 */
interface LlmApplicationServices {
    fun graphGenerationService(): GraphGenerationPort
    fun graphQaPatchService(): GraphQaPatchPort
    fun graphDiffPatchService(): GraphDiffPatchPort
    fun graphBeautificationService(): GraphBeautificationPort
    fun generationPlanDiscussionService(): GenerationPlanDiscussionPort
    fun codeGenerationService(): CodeGenerationService
}

interface GraphGenerationPort {
    fun generatePlan(
        context: GenerationContext,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GenerationPlan
}

interface GraphQaPatchPort {
    fun answer(
        context: GraphQaContext,
        question: String,
        settings: LinkGraphSettingsState,
        session: QaConversationSession? = null,
        sourceThreadId: String? = null,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
        onPreview: ((String, Boolean) -> Unit)? = null,
        runtimeEvidenceTrusted: Boolean = false,
    ): GraphPatchResult
}

interface GraphDiffPatchPort {
    fun review(
        context: GraphDiffContext,
        question: String,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GraphPatchResult
}

interface GraphBeautificationPort {
    fun beautify(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GraphBeautificationResult
}

interface GenerationPlanDiscussionPort {
    fun discuss(
        context: GenerationContext,
        plan: GenerationPlan,
        question: String,
        settings: LinkGraphSettingsState,
        session: GenerationPlanDiscussionSession? = null,
        focusItemId: String? = null,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GenerationPlanDiscussionResult
}
