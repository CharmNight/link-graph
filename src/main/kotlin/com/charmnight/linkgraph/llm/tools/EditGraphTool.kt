package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.application.edit.GraphEditRequestPayloadParser
import com.charmnight.linkgraph.application.model.GraphEditResult

/**
 * 图编辑工具：让模型按结构化形式提交图编辑请求。
 *
 * 工具内部走完整的校验、权限策略与应用链路：
 * 1) 从 input 解析出 GraphEditRequest（解析失败返回 INVALID_REQUEST）；
 * 2) 通过 context 中的 graphEditRequestExecutor 应用到工作图；
 * 3) 把应用结果（成功 / 拒绝）转译为工具结果。
 *
 * 当 runtime 未提供执行入口时直接拒绝，避免模型在只读场景下越权写图。
 *
 * 实现选择：保持直接实现 [AgentTool] 而非 [TypedAgentTool]——本工具不读具体字段，
 * 而是把整个 input map 转发给 [GraphEditRequestPayloadParser]，
 * 该 parser 自己定义 schema 并返回 issues。TypedAgentTool 的字段级 parse 在这里没收益。
 */
class EditGraphTool : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "edit_graph"

    /** 工具职责说明。 */
    override val description: String = "提交结构化图编辑请求，经过统一校验、权限策略和应用链路后写入工作图"

    /**
     * @param input 工具入参，需符合 GraphEditRequestPayloadParser 的格式
     * @param context 工具执行上下文
     * @return payload 包含 status（APPLIED / REJECTED）以及相应字段
     */
    override fun invoke(
        input: ToolInputPayload,
        context: ToolExecutionContext,
    ): ToolResult {
        // 没有提供执行入口：直接拒绝，避免越权写图
        val executor = context.graphEditRequestExecutor ?: return ToolResult(
            toolName = name,
            success = false,
            payload = mapOf("status" to "REJECTED"),
            errorMessage = "当前 runtime 未提供 graph edit 执行入口。",
        )
        // 解析请求；解析器返回结构化结果，issues 非空代表畸形 payload
        val parseResult = GraphEditRequestPayloadParser.parse(input)
        if (parseResult.issues.isNotEmpty()) {
            // 解析失败：构造与 EditGraphTool 历史一致的 REJECTED 响应，但用 issues 作为可读原因
            return ToolResult(
                toolName = name,
                success = false,
                payload = mapOf(
                    "status" to "REJECTED",
                    "reason" to "INVALID_REQUEST",
                    "issues" to parseResult.issues,
                ),
                errorMessage = parseResult.issues.joinToString("; ") { issue ->
                    "${issue.code.name}: ${issue.message}"
                },
            )
        }
        // 应用结果分支：成功带图与事务信息，失败带拒绝原因与问题列表
        return when (val result = executor.apply(parseResult)) {
            is GraphEditResult.Applied -> ToolResult(
                toolName = name,
                success = true,
                payload = mapOf(
                    "status" to "APPLIED",
                    "graph" to result.graph,
                    "transaction" to result.transaction,
                    "workspaceRevisionBefore" to result.transaction.workspaceRevisionBefore,
                    "workspaceRevisionAfter" to result.transaction.workspaceRevisionAfter,
                ),
            )
            is GraphEditResult.Rejected -> ToolResult(
                toolName = name,
                success = false,
                payload = mapOf(
                    "status" to "REJECTED",
                    "rejection" to result.rejection,
                    "issues" to result.rejection.issues,
                    "currentWorkspaceRevision" to result.rejection.currentWorkspaceRevision,
                ),
                // 把所有问题拼成一行错误信息，便于模型一眼看出被拒原因
                errorMessage = result.rejection.issues.joinToString("; ") { issue ->
                    "${issue.code.name}: ${issue.message}"
                },
            )
        }
    }
}
