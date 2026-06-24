package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.llm.context.PromptComposer
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.WorkbenchStep

/**
 * 把当前图上下文整理成可用于问答的提示词。
 *
 * P2-1 深度拆分后，本类只是 **薄壳 facade**：所有具体 prompt 构造逻辑都抽到了
 * `llm/prompt/` 子包下的 top-level fun（6 个 builder + 共享 helper / sanitizer /
 * schema instruction）。本类只负责持有共享 [PromptComposer] 并把调用转发过去，
 * 保证既有调用点（`LlmPromptFactory().buildXxx(...)`）零改动。
 *
 * 子包文件清单：
 * - [prompt.PromptSupport]：节点 / 边 / 源码片段 / 差异 / 已确认变更 / 证据边界摘要
 * - [prompt.PromptAssembly]：USER_INPUT_CONTRACT / sanitizeUserField / buildPromptPackage / promptPreview
 * - [prompt.LlmPromptSchemaInstructions]：8 个 schema / behavior 指令文本
 * - [prompt.GenerationPromptBuilder]：buildGenerationPromptPackage / buildGenerationPrompt
 * - [prompt.GenerationPlanDiscussionPromptBuilder]：buildGenerationPlanDiscussionPromptPackage
 * - [prompt.QaPromptBuilder]：buildQaPromptPackage / buildQaPrompt
 * - [prompt.DiffReviewPromptBuilder]：buildDiffReviewPromptPackage / buildDiffReviewPrompt
 * - [prompt.CodeGenerationPromptBuilder]：buildCodeGenerationPromptPackage
 * - [prompt.BeautificationPromptBuilder]：buildBeautificationPromptPackage / buildBeautificationPrompt
 */
class LlmPromptFactory(
    private val promptComposer: PromptComposer = PromptComposer(),
) {
    /** 构造实现计划生成场景的提示词包。 */
    fun buildGenerationPromptPackage(
        snapshot: GenerationContext,
        settings: LinkGraphSettingsState,
    ): LlmPromptPackage = com.charmnight.linkgraph.llm.prompt.buildGenerationPromptPackage(promptComposer, snapshot, settings)

    /** 返回实现计划生成场景的用户提示词。 */
    fun buildGenerationPrompt(
        snapshot: GenerationContext,
        settings: LinkGraphSettingsState,
    ): String = com.charmnight.linkgraph.llm.prompt.buildGenerationPrompt(promptComposer, snapshot, settings)

    /** 构造实现建议追问场景的提示词包。 */
    fun buildGenerationPlanDiscussionPromptPackage(
        context: GenerationContext,
        plan: GenerationPlan,
        question: String,
        settings: LinkGraphSettingsState,
        session: GenerationPlanDiscussionSession? = null,
        focusItemId: String? = null,
    ): LlmPromptPackage = com.charmnight.linkgraph.llm.prompt.buildGenerationPlanDiscussionPromptPackage(
        promptComposer = promptComposer,
        context = context,
        plan = plan,
        question = question,
        settings = settings,
        session = session,
        focusItemId = focusItemId,
    )

    /** 构造链路问答场景的提示词包。 */
    fun buildQaPromptPackage(
        context: GraphQaContext,
        question: String,
        settings: LinkGraphSettingsState,
        session: QaConversationSession? = null,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
    ): LlmPromptPackage = com.charmnight.linkgraph.llm.prompt.buildQaPromptPackage(
        promptComposer = promptComposer,
        context = context,
        question = question,
        settings = settings,
        session = session,
        requestedMode = requestedMode,
        effectiveMode = effectiveMode,
    )

    /** 返回链路问答场景的用户提示词。 */
    fun buildQaPrompt(
        context: GraphQaContext,
        question: String,
        settings: LinkGraphSettingsState,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
    ): String = com.charmnight.linkgraph.llm.prompt.buildQaPrompt(
        promptComposer = promptComposer,
        context = context,
        question = question,
        settings = settings,
        requestedMode = requestedMode,
        effectiveMode = effectiveMode,
    )

    /** 构造差异审查场景的提示词包。 */
    fun buildDiffReviewPromptPackage(
        context: GraphDiffContext,
        question: String,
        settings: LinkGraphSettingsState,
    ): LlmPromptPackage = com.charmnight.linkgraph.llm.prompt.buildDiffReviewPromptPackage(promptComposer, context, question, settings)

    /** 返回差异审查场景的用户提示词。 */
    fun buildDiffReviewPrompt(
        context: GraphDiffContext,
        question: String,
        settings: LinkGraphSettingsState,
    ): String = com.charmnight.linkgraph.llm.prompt.buildDiffReviewPrompt(promptComposer, context, question, settings)

    /** 构造代码草稿生成场景的提示词包。 */
    fun buildCodeGenerationPromptPackage(
        context: GenerationContext,
        plan: GenerationPlan?,
        settings: LinkGraphSettingsState,
    ): LlmPromptPackage = com.charmnight.linkgraph.llm.prompt.buildCodeGenerationPromptPackage(promptComposer, context, plan, settings)

    /** 构造链路讲解场景的提示词包。 */
    fun buildBeautificationPromptPackage(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
        projectedSteps: List<WorkbenchStep> = emptyList(),
    ): LlmPromptPackage = com.charmnight.linkgraph.llm.prompt.buildBeautificationPromptPackage(promptComposer, context, settings, projectedSteps)

    /** 返回链路讲解场景的用户提示词。 */
    fun buildBeautificationPrompt(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
    ): String = com.charmnight.linkgraph.llm.prompt.buildBeautificationPrompt(promptComposer, context, settings)
}
