package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.agent.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.agent.runtime.RunBudget
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentToolRegistryTest : BasePlatformTestCase() {
    fun testRegistryResolvesToolByNameAndInvokesIt() {
        val tool = object : AgentTool {
            override val name: String = "fake_tool"
            override val description: String = "用于测试注册表"

            override fun invoke(
                input: Map<String, Any?>,
                context: ToolExecutionContext,
            ): ToolResult {
                return ToolResult(
                    toolName = name,
                    payload = mapOf("value" to (input["value"] ?: "missing")),
                )
            }
        }
        val registry = AgentToolRegistry(listOf(tool))

        val result = registry.require("fake_tool").invoke(
            input = mapOf("value" to "ok"),
            context = ToolExecutionContext(
                project = project,
                snapshot = testSnapshot().toToolGraphSnapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals("ok", result.payload["value"])
    }

    fun testRegistryRejectsUnknownTool() {
        val registry = AgentToolRegistry(emptyList())

        assertFailsWith<IllegalArgumentException> {
            registry.require("missing_tool")
        }
    }

    fun testRegistryRejectsToolInvocationOutsideAllowedToolContext() {
        val registry = AgentToolRegistry(
            listOf(
                fakeTool("allowed_tool"),
                fakeTool("blocked_tool"),
            ),
        )
        val context = ToolExecutionContext(
            project = project,
            snapshot = testSnapshot().toToolGraphSnapshot(),
            artifactStore = InMemoryArtifactStore(),
            runBudget = RunBudget(),
            allowedToolNames = setOf("allowed_tool"),
        )

        val allowed = registry.require("allowed_tool").invoke(emptyMap(), context)
        assertEquals("allowed_tool", allowed.toolName)
        val error = assertFailsWith<IllegalStateException> {
            registry.require("blocked_tool").invoke(emptyMap(), context)
        }
        assertEquals("工具未被当前 capability 允许: blocked_tool", error.message)
    }

    fun testRegistryRejectsDuplicateToolNames() {
        val first = object : AgentTool {
            override val name: String = "duplicate_tool"
            override val description: String = "first"

            override fun invoke(
                input: Map<String, Any?>,
                context: ToolExecutionContext,
            ): ToolResult {
                return ToolResult(toolName = name)
            }
        }
        val second = object : AgentTool {
            override val name: String = "duplicate_tool"
            override val description: String = "second"

            override fun invoke(
                input: Map<String, Any?>,
                context: ToolExecutionContext,
            ): ToolResult {
                return ToolResult(toolName = name)
            }
        }

        val error = assertFailsWith<IllegalArgumentException> {
            AgentToolRegistry(listOf(first, second))
        }

        assertEquals("重复注册的工具名: duplicate_tool", error.message)
    }

    fun testArchitectureAndReviewToolsHaveUniqueNames() {
        val registry = AgentToolRegistry(
            listOf(
                GetArchitectureIndexSummaryTool(),
                FindJvmSymbolTool(),
                FindJvmRelationsTool(),
                QueryArchitectureRelationsTool(),
                FindServiceProvidersTool(),
                FindReflectionTargetsTool(),
                FindProxyTargetsTool(),
                GetChangedSymbolsTool(),
                GetBlastRadiusTool(),
                FindRelatedTestsTool(),
                BuildReviewEvidenceBundleTool(),
            ),
        )

        assertEquals(11, registry.all().size)
        assertEquals("find_jvm_symbol", registry.require("find_jvm_symbol").name)
        assertEquals("query_architecture_relations", registry.require("query_architecture_relations").name)
        assertEquals("find_proxy_targets", registry.require("find_proxy_targets").name)
        assertEquals("build_review_evidence_bundle", registry.require("build_review_evidence_bundle").name)
    }

    private fun fakeTool(toolName: String): AgentTool =
        object : AgentTool {
            override val name: String = toolName
            override val description: String = "fake"

            override fun invoke(
                input: Map<String, Any?>,
                context: ToolExecutionContext,
            ): ToolResult = ToolResult(toolName = name)
        }
}
