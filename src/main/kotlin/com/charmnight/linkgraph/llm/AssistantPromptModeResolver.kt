package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.workbench.AssistantActionId

/**
 * 助理提示词模式的解析结果。
 *
 * 把"是否需要类描述目标"与"是否需要类关系目标"分开标记，
 * 让提示词构造器按需加入相应段落，避免无差别塞入造成提示词臃肿。
 */
internal data class AssistantPromptMode(
    /** 是否需要"介绍这个类的职责"类描述。 */
    val classDescriptionGoal: Boolean,
    /** 是否需要"解释类间关系"目标。 */
    val classRelationshipGoal: Boolean,
)

/**
 * 助理提示词模式解析器。
 *
 * 把动作 ID（DESCRIBE_CLASS / EXPLAIN_STRUCTURE 等）与证据特征（锚点是否为类节点）
 * 综合判断出应当启用哪些提示目标。同时兼容旧版基于 userGoal 文本的判断，
 * 让历史用法平滑过渡。
 */
internal object AssistantPromptModeResolver {
    /**
     * @param context 链路讲解上下文，包含动作 ID 与用户目标文本
     * @param evidenceProfile 当前证据特征，例如锚点节点类型
     */
    fun resolve(
        context: GraphBeautificationContext,
        evidenceProfile: GraphEvidenceProfile,
    ): AssistantPromptMode {
        return AssistantPromptMode(
            // 显式选择 DESCRIBE_CLASS，或没有动作 ID 但 userGoal 含历史关键词
            classDescriptionGoal = context.assistantActionId == AssistantActionId.DESCRIBE_CLASS ||
                (context.assistantActionId == null && isLegacyClassDescriptionGoal(context.userGoal)),
            // 显式选择 EXPLAIN_STRUCTURE 且锚点是类节点，或没有动作 ID 但 userGoal 含历史关键词
            classRelationshipGoal = (context.assistantActionId == AssistantActionId.EXPLAIN_STRUCTURE && evidenceProfile.anchorNodeType == NodeType.CLASS) ||
                (context.assistantActionId == null && isLegacyClassRelationshipGoal(context.userGoal)),
        )
    }

    /** 旧版基于关键词判断"是否是类描述目标"。 */
    private fun isLegacyClassDescriptionGoal(goal: String): Boolean {
        val normalized = goal.trim()
        return normalized.contains("介绍类图节点") ||
            normalized.contains("介绍当前类图") ||
            normalized.contains("介绍这个类")
    }

    /** 旧版基于关键词判断"是否是类关系目标"。 */
    private fun isLegacyClassRelationshipGoal(goal: String): Boolean {
        val normalized = goal.trim()
        return normalized.contains("只解释图上的结构关系") ||
            normalized.contains("字段关联、构造参数、返回值、参数和类型依赖关系")
    }
}
