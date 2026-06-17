package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.application.edit.GraphEditRequestPayloadParser
import com.charmnight.linkgraph.application.model.GraphEditResult

class EditGraphTool : AgentTool {
    override val name: String = "edit_graph"

    override val description: String = "提交结构化图编辑请求，经过统一校验、权限策略和应用链路后写入工作图"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val executor = context.graphEditRequestExecutor ?: return ToolResult(
            toolName = name,
            success = false,
            payload = mapOf("status" to "REJECTED"),
            errorMessage = "当前 runtime 未提供 graph edit 执行入口。",
        )
        val request = runCatching { GraphEditRequestPayloadParser.parse(input) }
            .getOrElse { error ->
                return ToolResult(
                    toolName = name,
                    success = false,
                    payload = mapOf(
                        "status" to "REJECTED",
                        "reason" to "INVALID_REQUEST",
                    ),
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
            }
        return when (val result = executor.apply(request)) {
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
                errorMessage = result.rejection.issues.joinToString("; ") { issue ->
                    "${issue.code.name}: ${issue.message}"
                },
            )
        }
    }
}
