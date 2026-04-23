package com.charmnight.linkgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowArchitectureRegressionTest {
    @Test
    fun linkGraphProjectServiceDoesNotKeepTestOverridesInProductionState() {
        val source = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt"))

        assertFalse(source.contains("testOpenSettingsOverride"))
        assertFalse(source.contains("testSubjectLocatorOverride"))
        assertFalse(source.contains("testSemanticAnalyzerOverride"))
        assertFalse(source.contains("testAnalysisOutcomeFactoryOverride"))
        assertFalse(source.contains("testAuditExecutorOverride"))
        assertFalse(source.contains("testEffectiveGenerationSettingsOverride"))
        assertFalse(source.contains("testAsyncRequestTimeoutMillisOverride"))
        assertFalse(source.contains("testOpenCodeDraftNativeDiffOverride"))
        assertTrue(
            Files.exists(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectTestOverrides.kt")),
            "测试覆写必须迁移到独立的测试钩子对象，不能继续污染生产 service 状态。",
        )
    }

    @Test
    fun asyncWorkflowsDelegateThreadHopsToLifecycleSupport() {
        val generationWorkflow = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/GenerationWorkflow.kt"))
        val reviewWorkflow = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/ReviewWorkflow.kt"))
        val subjectWorkflow = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/SubjectGraphWorkflow.kt"))

        assertFalse(
            generationWorkflow.contains("ApplicationManager.getApplication().executeOnPooledThread"),
            "GenerationWorkflow 应通过统一生命周期支持调度后台请求，而不是继续手写线程切换模板。",
        )
        assertFalse(
            reviewWorkflow.contains("ApplicationManager.getApplication().executeOnPooledThread"),
            "ReviewWorkflow 应通过统一生命周期支持调度后台请求，而不是继续手写线程切换模板。",
        )
        assertFalse(
            subjectWorkflow.contains("ApplicationManager.getApplication().executeOnPooledThread"),
            "SubjectGraphWorkflow 应通过统一生命周期支持调度后台请求，而不是继续手写线程切换模板。",
        )
    }

    @Test
    fun graphEditorCommandRouterDoesNotUseServiceLocatorForwarding() {
        val source = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorCommandRouter.kt"))

        assertFalse(source.contains("private fun projectService()"))
        assertFalse(source.contains("projectService()."))
        assertTrue(
            source.contains("private val"),
            "GraphEditorCommandRouter 应持有明确的协作者边界，而不是每个分支动态拉取 project service。",
        )
    }
}
