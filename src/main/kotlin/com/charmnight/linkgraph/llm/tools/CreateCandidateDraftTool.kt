package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.artifact.CandidateDraftArtifact
import com.charmnight.linkgraph.workbench.CandidateDraftChange

/**
 * 把候选草稿存入 artifact store。
 *
 * 这里只产生候选层产物，不会直接改写正式草稿箱状态。
 * 用户确认后才升级为 ConfirmedIntent；本工具仅完成"沉淀候选"这一步。
 */
class CreateCandidateDraftTool : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "create_candidate_draft"

    /** 工具职责说明。 */
    override val description: String = "创建候选草稿 artifact，不触发确认"

    /**
     * @param input 必须包含 key="candidate" 的候选对象
     * @param context 工具执行上下文，提供产物仓库
     * @return payload 包含新产物的引用
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val candidate = input.requiredValue<CandidateDraftChange>("candidate") ?: return missingRequired("candidate")
        val artifact = CandidateDraftArtifact(
            // 用 changeId 派生 artifactId，保证幂等
            artifactId = "candidate-${candidate.changeId}",
            candidate = candidate,
        )
        val ref = context.artifactStore.save(artifact)
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "artifactRef" to ref,
            ),
        )
    }
}
