package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.workbench.AssistantActionId

internal data class AssistantPromptMode(
    val classDescriptionGoal: Boolean,
    val classRelationshipGoal: Boolean,
)

internal object AssistantPromptModeResolver {
    fun resolve(
        context: GraphBeautificationContext,
        evidenceProfile: GraphEvidenceProfile,
    ): AssistantPromptMode {
        return AssistantPromptMode(
            classDescriptionGoal = context.assistantActionId == AssistantActionId.DESCRIBE_CLASS ||
                (context.assistantActionId == null && isLegacyClassDescriptionGoal(context.userGoal)),
            classRelationshipGoal = (context.assistantActionId == AssistantActionId.EXPLAIN_STRUCTURE && evidenceProfile.anchorNodeType == NodeType.CLASS) ||
                (context.assistantActionId == null && isLegacyClassRelationshipGoal(context.userGoal)),
        )
    }

    private fun isLegacyClassDescriptionGoal(goal: String): Boolean {
        val normalized = goal.trim()
        return normalized.contains("介绍类图节点") ||
            normalized.contains("介绍当前类图") ||
            normalized.contains("介绍这个类")
    }

    private fun isLegacyClassRelationshipGoal(goal: String): Boolean {
        val normalized = goal.trim()
        return normalized.contains("只解释图上的结构关系") ||
            normalized.contains("字段关联、构造参数、返回值、参数和类型依赖关系")
    }
}
