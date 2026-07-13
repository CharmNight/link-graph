package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.agent.runtime.RunBudget
import com.charmnight.linkgraph.agent.tools.ToolExecutionContext
import com.charmnight.linkgraph.agent.tools.ToolGraphSnapshot
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypedAgentToolTest : BasePlatformTestCase() {
    fun testRejectsOversizedInputBeforeParse() {
        val parsed = AtomicBoolean(false)
        val tool = EchoTypedTool(parsed)

        val result = tool.invoke(
            input = mapOf("text" to "x".repeat(513 * 1024)),
            context = context(),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("工具入参过大"))
        assertFalse(parsed.get())
    }

    fun testRejectsOversizedStringListDuringParse() {
        val result = EchoTypedTool().invoke(
            input = mapOf("items" to List(513) { "item-$it" }),
            context = context(),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("items 数量过多"))
    }

    fun testAcceptsNormalInput() {
        val result = EchoTypedTool().invoke(
            input = mapOf("text" to "hello", "items" to listOf("a", "b")),
            context = context(),
        )

        assertTrue(result.success)
        assertEquals("hello", result.payload["text"])
        assertEquals(listOf("a", "b"), result.payload["items"])
    }

    private fun context(): ToolExecutionContext =
        ToolExecutionContext(
            project = project,
            snapshot = ToolGraphSnapshot(),
            artifactStore = InMemoryArtifactStore(),
            runBudget = RunBudget(),
        )

    private data class EchoInput(
        val text: String?,
        val items: List<String>,
    )

    private class EchoTypedTool(
        private val parsed: AtomicBoolean = AtomicBoolean(false),
    ) : TypedAgentTool<EchoInput>() {
        override val name: String = "echo"
        override val description: String = "echo"

        override fun parseInput(raw: ToolInputPayload): EchoInput {
            parsed.set(true)
            return EchoInput(
                text = optionalString(raw, "text"),
                items = optionalStringList(raw, "items"),
            )
        }

        override fun invokeTyped(input: EchoInput, context: ToolExecutionContext): ToolResult =
            ToolResult(
                toolName = name,
                payload = mapOf(
                    "text" to input.text,
                    "items" to input.items,
                ),
            )
    }
}
