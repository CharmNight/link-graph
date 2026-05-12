package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
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
}
