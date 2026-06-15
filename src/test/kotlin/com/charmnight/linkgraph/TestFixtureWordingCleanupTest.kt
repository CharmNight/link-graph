package com.charmnight.linkgraph

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class TestFixtureWordingCleanupTest {
    @Test
    fun qaTestsUseConsistentQuestionWording() {
        val files = listOf(
            "src/test/kotlin/com/charmnight/linkgraph/application/ReviewWorkflowAgentRuntimeTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationAsyncLifecycleTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/capability/QaCapabilityTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateServiceTest.kt",
            "src/integrationTest/kotlin/com/charmnight/linkgraph/ui/LinkGraphToolWindowIT.kt",
            "src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRendererTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationDraftWorkbenchTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/LlmPromptFactoryTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/GraphQaScopeResolverTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/mermaid/MermaidExporterTest.kt",
        )

        files.forEach { relativePath ->
            val content = Files.readString(Path.of(relativePath))
            assertFalse(
                content.contains("请复核"),
                "非兼容场景测试不应继续使用旧“复核”提问口径：$relativePath",
            )
            assertFalse(
                content.contains("人工复核"),
                "非兼容场景测试说明不应继续保留“人工复核”口径：$relativePath",
            )
            assertFalse(
                content.contains("复核建议"),
                "非兼容场景测试说明不应继续保留“复核建议”口径：$relativePath",
            )
        }
    }

    @Test
    fun runtimeAndSemanticTestsDoNotKeepObsoleteArchitectureNaming() {
        val files = listOf(
            "src/test/kotlin/com/charmnight/linkgraph/llm/capability/QaCapabilityTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/capability/PlanCapabilityTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/capability/CodegenCapabilityTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/application/ReviewWorkflowAgentRuntimeTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/application/GenerationWorkflowAgentRuntimeTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationSemanticAnalysisTest.kt",
            "src/main/kotlin/com/charmnight/linkgraph/workbench/QaConversationService.kt",
            "src/main/kotlin/com/charmnight/linkgraph/semantic/provider/code/CodeInvocationSemanticResolver.kt",
        )
        val forbiddenFragments = listOf(
            "Legacy" + charArrayOf('A', 'u', 'd', 'i', 't').concatToString(),
            "LegacyPlan",
            "LegacyCodegen",
            "BeforeLegacyExecutor",
            "legacyInvoked",
            "WithoutLegacyGraphExtractor",
            "legacyLeads",
            "legacy extract",
        )

        files.forEach { relativePath ->
            val content = Files.readString(Path.of(relativePath))
            forbiddenFragments.forEach { fragment ->
                assertFalse(
                    content.contains(fragment),
                    "主干测试不应继续保留旧架构命名 `$fragment`：$relativePath",
                )
            }
        }
    }
}
