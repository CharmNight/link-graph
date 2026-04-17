package com.charmnight.linkgraph.llm.tools

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
                snapshot = GraphEditorStateService.Snapshot(),
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
}
