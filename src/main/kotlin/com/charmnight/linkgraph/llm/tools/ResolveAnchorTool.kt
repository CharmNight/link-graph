package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

/**
 * 根据 nodeId 或 symbolSignature 解析代码锚点。
 *
 * 锚点是"模型当前应该关注的代码位置"。模型可能只有符号签名（例如方法全名），
 * 也可能只有 nodeId；本工具把这两种输入都尝试解析为具体的节点 + 源码位置。
 *
 * @param codeReadToolFacade 代码读取外观
 */
class ResolveAnchorTool(
    private val codeReadToolFacade: CodeReadToolFacade,
) : TypedAgentTool<ResolveAnchorInput>() {
    override val name: String = "resolve_anchor"
    override val description: String = "根据 nodeId 或 symbolSignature 解析代码锚点"

    override fun parseInput(raw: ToolInputPayload): ResolveAnchorInput = ResolveAnchorInput(
        nodeId = optionalString(raw, "nodeId"),
        symbolSignature = optionalString(raw, "symbolSignature"),
    )

    override fun invokeTyped(input: ResolveAnchorInput, context: ToolExecutionContext): ToolResult {
        val resolution = codeReadToolFacade.resolveEvidenceAnchor(
            snapshot = context.snapshot,
            nodeId = input.nodeId,
            symbolSignature = input.symbolSignature,
        )
        // 解析不到节点时返回失败结果，附带 resolution 让模型理解为什么失败
        val anchor = resolution.node ?: return failure(
            errorMessage = "未解析到代码锚点",
            payload = mapOf("resolution" to resolution),
        )
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "node" to anchor,
                "resolution" to resolution,
            ),
        )
    }
}

/** [ResolveAnchorTool] 的强类型入参；nodeId 与 symbolSignature 至少一个有值（缺省都为 null）。 */
data class ResolveAnchorInput(
    val nodeId: String?,
    val symbolSignature: String?,
)
