package com.charmnight.linkgraph

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class TestFixtureWordingCleanupTest {
    @Test
    fun nonCompatibilityTestsUseQaWordingInsteadOfObsoleteAuditPrompt() {
        val files = listOf(
            "src/test/kotlin/com/charmnight/linkgraph/services/ReviewWorkflowAgentRuntimeTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectServiceAsyncLifecycleTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/capability/QaCapabilityTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateServiceTest.kt",
            "src/integrationTest/kotlin/com/charmnight/linkgraph/ui/LinkGraphToolWindowIT.kt",
            "src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRendererTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectServiceDraftWorkbenchTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/LlmPromptFactoryTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/GraphQaScopeResolverTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/mermaid/MermaidExporterTest.kt",
        )

        files.forEach { relativePath ->
            val content = Files.readString(Path.of(relativePath))
            assertFalse(
                content.contains("请审计"),
                "非兼容场景测试不应继续使用旧“审计”提问口径：$relativePath",
            )
            assertFalse(
                content.contains("人工审计"),
                "非兼容场景测试说明不应继续保留“人工审计”口径：$relativePath",
            )
            assertFalse(
                content.contains("审计建议"),
                "非兼容场景测试说明不应继续保留“审计建议”口径：$relativePath",
            )
        }
    }

    @Test
    fun runtimeAndSemanticTestsDoNotKeepObsoleteArchitectureNaming() {
        val files = listOf(
            "src/test/kotlin/com/charmnight/linkgraph/llm/capability/QaCapabilityTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/capability/PlanCapabilityTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/llm/capability/CodegenCapabilityTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/services/ReviewWorkflowAgentRuntimeTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/services/GenerationWorkflowAgentRuntimeTest.kt",
            "src/test/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectServiceSemanticAnalysisTest.kt",
            "src/main/kotlin/com/charmnight/linkgraph/workbench/QaConversationService.kt",
            "src/main/kotlin/com/charmnight/linkgraph/semantic/provider/code/CodeInvocationSemanticResolver.kt",
        )
        val forbiddenFragments = listOf(
            "LegacyAudit",
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
