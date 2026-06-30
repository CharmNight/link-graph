package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolInputSupportTest {
    @Test
    fun requiredStringTrimsBlankValues() {
        assertEquals("node-1", mapOf("nodeId" to " node-1 ").requiredString("nodeId"))
        assertNull(mapOf("nodeId" to "   ").requiredString("nodeId"))
        assertNull(emptyMap<String, Any?>().requiredString("nodeId"))
    }

    @Test
    fun optionalStringTrimsBlankValues() {
        assertEquals("fallback", mapOf("fallbackSnippet" to " fallback ").optionalString("fallbackSnippet"))
        assertNull(mapOf("fallbackSnippet" to "   ").optionalString("fallbackSnippet"))
        assertNull(emptyMap<String, Any?>().optionalString("fallbackSnippet"))
    }

    @Test
    fun optionalIntReadsNumericValues() {
        assertEquals(2, mapOf("depth" to 2).optionalInt("depth"))
        assertEquals(3, mapOf("depth" to 3L).optionalInt("depth"))
        assertNull(mapOf("depth" to "3").optionalInt("depth"))
    }

    @Test
    fun requiredValueReadsTypedValues() {
        val marker = Any()

        assertEquals(marker, mapOf("draft" to marker).requiredValue<Any>("draft"))
        assertNull(mapOf("draft" to "not-a-number").requiredValue<Number>("draft"))
        assertNull(emptyMap<String, Any?>().requiredValue<Any>("draft"))
    }

    @Test
    fun optionalListFiltersRequestedElementType() {
        assertEquals(listOf("a", "b"), mapOf("selectedNodeIds" to listOf("a", 1, "b")).optionalList<String>("selectedNodeIds"))
        assertEquals(emptyList<String>(), mapOf("selectedNodeIds" to "a").optionalList<String>("selectedNodeIds"))
        assertEquals(emptyList<String>(), emptyMap<String, Any?>().optionalList<String>("selectedNodeIds"))
    }

    @Test
    fun missingRequiredBuildsStandardToolError() {
        val tool = object : AgentTool {
            override val name: String = "fake_tool"
            override val description: String = "fake"

            override fun invoke(
                input: Map<String, Any?>,
                context: ToolExecutionContext,
            ): ToolResult = ToolResult(toolName = name)
        }

        val result = tool.missingRequired("nodeId")

        assertEquals("fake_tool", result.toolName)
        assertEquals(false, result.success)
        assertEquals("nodeId 不能为空", result.errorMessage)
    }

    @Test
    fun failureBuildsStandardToolErrorWithPayload() {
        val tool = object : AgentTool {
            override val name: String = "fake_tool"
            override val description: String = "fake"

            override fun invoke(
                input: Map<String, Any?>,
                context: ToolExecutionContext,
            ): ToolResult = ToolResult(toolName = name)
        }

        val result = tool.failure(
            errorMessage = "未找到节点",
            payload = mapOf("nodeId" to "missing"),
        )

        assertEquals("fake_tool", result.toolName)
        assertEquals(false, result.success)
        assertEquals("未找到节点", result.errorMessage)
        assertEquals(mapOf("nodeId" to "missing"), result.payload)
    }
}
