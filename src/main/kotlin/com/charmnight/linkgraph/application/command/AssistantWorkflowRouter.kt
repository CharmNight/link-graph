package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.agent.model.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.QaMode

/**
 * 助手任务路由器：根据应用命令携带的动作标识将请求分发给执行器中对应的工作流。
 *
 * 内部把"用户意图（actionId）+ 上下文 target"映射到具体执行入口，
 * 同时通过若干辅助函数从 target 与 prompt 中抽出执行所需参数，避免执行器内重复判断。
 *
 * @property executor 实际承载各类助手工作流的执行器
 */
internal class AssistantWorkflowRouter(
    private val executor: AssistantTaskExecutor,
) {
    /**
     * 根据 [command] 的动作类型将其路由到相应的助手工作流。
     *
     * 不同动作会从命令上下文中提取不同参数（焦点节点、追问上下文、模式等），
     * 然后转发给执行器执行。
     *
     * @param command 来自应用层、用户触发的助手任务请求
     */
    fun route(command: ApplicationCommand.RequestAssistantTask) {
        // 去掉首尾空白后的提示词，避免空格干扰
        val prompt = command.prompt.trim()
        // 当前请求所归属的动作标识
        val actionId = command.actionId
        // 由动作映射而来的助手意图，供下游语义化处理
        val routedIntent = actionId.toIntent()
        when (actionId) {
            AssistantActionId.DESCRIBE_CLASS -> executor.executeExplanation(
                goal = prompt,
                focusNodeId = command.selectedNodeIds.firstOrNull(),
                followUp = null,
                granularity = command.explanationGranularity,
                intent = routedIntent,
                actionId = actionId,
            )
            AssistantActionId.EXPLAIN_STRUCTURE,
            AssistantActionId.EXPLAIN_FLOW -> executor.executeExplanation(
                goal = prompt,
                focusNodeId = command.explanationFocusNodeId(),
                followUp = command.explanationFollowUp(prompt),
                granularity = command.explanationGranularity,
                intent = routedIntent,
                actionId = actionId,
            )
            AssistantActionId.ASK_CONTEXT -> executor.executeQa(
                question = prompt,
                selectedNodeIds = command.qaSelectedNodeIds(),
                sourceThreadId = command.assistantTargetSourceThreadId(),
                mode = command.qaMode(),
            )
            AssistantActionId.GENERATE_IMPLEMENTATION -> {
                // 区分"针对某个生成计划项的讨论"与"全新生成计划"两种入口
                val discussionTarget = command.target as? AssistantComposerTarget.GenerationDiscussion
                if (discussionTarget != null) {
                    executor.executeGenerationDiscussion(prompt, discussionTarget.planItemId)
                } else {
                    executor.executeGenerationPlan(prompt)
                }
            }
            AssistantActionId.CHECK_CHANGE -> {
                val diffItemIds = command.selectedDiffItemIds
                // 选中条目为空时跳过 Review Graph：构造空 review graph 既无意义也浪费 LLM 调用。
                // executeDiffReview 仍正常执行（用户可能就是想要一个通用 diff review）。
                if (diffItemIds.isNotEmpty()) {
                    executor.executeReviewGraph(diffItemIds)
                }
                executor.executeDiffReview(
                    question = prompt.ifBlank { DEFAULT_CHECK_CHANGE_PROMPT },
                    selectedDiffItemIds = diffItemIds,
                )
            }
        }
    }

    private companion object {
        // 当用户未输入提示词时的默认复核问句
        const val DEFAULT_CHECK_CHANGE_PROMPT: String = "请检查当前改动的风险、影响范围和相关测试。"
    }
}

/**
 * 从命令上下文中提取"结构/流程讲解追问"所需的上下文。
 *
 * 只有当 target 是讲解追问类型且具备有效的 stepId、stepTitle、question 时才返回非空，
 * 任一字段缺失都视为没有有效追问上下文。
 */
private fun ApplicationCommand.RequestAssistantTask.explanationFollowUp(
    prompt: String,
): GraphBeautificationFollowUpContext? {
    val target = target as? AssistantComposerTarget.ExplanationFollowUp ?: return null
    // 步骤 ID 必填，缺则不构成有效追问
    val stepId = target.stepId.takeIf(String::isNotBlank) ?: return null
    val stepTitle = target.stepTitle?.takeIf(String::isNotBlank) ?: return null
    val question = prompt.takeIf(String::isNotBlank) ?: return null
    return GraphBeautificationFollowUpContext(stepId, stepTitle, question)
}

/**
 * 解析讲解任务使用的焦点节点 ID：优先取追问上下文中显式提供的焦点节点，
 * 否则回退到当前选中的第一个节点。
 */
private fun ApplicationCommand.RequestAssistantTask.explanationFocusNodeId(): String? =
    (target as? AssistantComposerTarget.ExplanationFollowUp)?.focusNodeId
        ?: selectedNodeIds.firstOrNull()

/**
 * 解析问答任务应携带的选中节点列表。
 *
 * 对问答恢复与风险取证两类 target，优先采用 target 自身指定的节点，
 * 若为空则回退到命令的全局选中节点，确保执行器始终能拿到节点上下文。
 */
private fun ApplicationCommand.RequestAssistantTask.qaSelectedNodeIds(): List<String> =
    when (val currentTarget = target) {
        is AssistantComposerTarget.QaRecovery ->
            currentTarget.selectedNodeIds.takeIf(List<String>::isNotEmpty) ?: selectedNodeIds
        is AssistantComposerTarget.RiskInvestigation ->
            currentTarget.targetNodeIds.takeIf(List<String>::isNotEmpty) ?: selectedNodeIds
        else -> selectedNodeIds
    }

/**
 * 解析问答任务的来源线程 ID，便于把问答结果挂回到原线程上下文。
 *
 * 仅当 target 是问答恢复或风险取证时存在来源线程，其他 target 返回 null。
 */
private fun ApplicationCommand.RequestAssistantTask.assistantTargetSourceThreadId(): String? =
    when (val currentTarget = target) {
        is AssistantComposerTarget.QaRecovery -> currentTarget.sourceThreadId
        is AssistantComposerTarget.RiskInvestigation -> currentTarget.threadId
        else -> null
    }

/**
 * 解析问答任务最终采用的 QA 模式。
 *
 * 问答恢复 target 的显式模式优先（缺省回退 AUTO）；风险取证 target 固定走 INVESTIGATE；
 * 其他 target 直接采用命令上层的 mode 字段。
 */
private fun ApplicationCommand.RequestAssistantTask.qaMode(): QaMode =
    when (val currentTarget = target) {
        is AssistantComposerTarget.QaRecovery -> currentTarget.mode ?: QaMode.AUTO
        is AssistantComposerTarget.RiskInvestigation -> QaMode.INVESTIGATE
        else -> mode
    }
