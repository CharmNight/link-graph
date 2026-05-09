package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.testing.*

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
    fun reviewWorkflowUsesQaModeContextInsteadOfNakedEffectiveModePlumbing() {
        val reviewWorkflow = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/ReviewWorkflow.kt"))

        assertFalse(
            reviewWorkflow.contains("effectiveMode(request)"),
            "ReviewWorkflow 必须把 ReplayableQaRequest 分类成一次 QaModeContext，不能重复调用 effectiveMode(request)。",
        )
        assertFalse(
            Regex("""private fun \w+\([^)]*effectiveMode: QaMode""").containsMatchIn(reviewWorkflow),
            "ReviewWorkflow 私有辅助方法应接收 QaModeContext，而不是裸 effectiveMode 参数。",
        )
        assertFalse(
            reviewWorkflow.contains("private fun normalizeAuditResult("),
            "问答结果归一化应放在 AuditResultNormalizer 中。",
        )
        assertFalse(
            reviewWorkflow.contains("private fun applyModeBoundary("),
            "模式边界逻辑应放在 AuditResultNormalizer 中。",
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

    @Test
    fun llmResultSourceMockOnlyAppearsInBootstrapMigrationBoundary() {
        val allowed = setOf(
            "web/src/app/types.ts",
            "web/src/app/bootstrapStateMigration.ts",
            "web/src/test/app/bootstrapStateMigration.test.ts",
        )
        val legacyName = "MO" + "CK"
        val quotedLegacyName = "\"$legacyName\""
        val deletedResultSourceFragments = listOf(
            "LlmResultSource.$legacyName",
            "case $quotedLegacyName",
            "llmResultSourceLabel($quotedLegacyName",
            "patchResultBoundaryDescription($quotedLegacyName",
            "beautificationBoundaryDescription($quotedLegacyName",
            "generatedCodeDraftSource: $quotedLegacyName",
        )
        val sourceFixtureRegex = Regex("""source:\s*"$legacyName"""")
        val scannedRoots = listOf("src/main/kotlin", "web/src/app", "web/src/test/app")
        val offenders = scannedRoots.flatMap { root ->
            Files.walk(Path.of(root))
                .filter { path ->
                    path.toString().endsWith(".kt") ||
                        path.toString().endsWith(".ts") ||
                        path.toString().endsWith(".tsx")
                }
                .use { paths ->
                    paths.toList().filter { path ->
                        val normalizedPath = path.toString()
                        if (normalizedPath in allowed) {
                            return@filter false
                        }
                        val source = Files.readString(path)
                        deletedResultSourceFragments.any(source::contains) ||
                            (sourceFixtureRegex.containsMatchIn(source) && !isGenerationPlanMockFixture(path))
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "旧 LLM 结果来源只能存在于 bootstrap 迁移边界：${offenders.joinToString()}",
        )
    }

    private fun isGenerationPlanMockFixture(path: Path): Boolean {
        val source = Files.readString(path)
        return source.contains("generationPlan:") ||
            source.contains("implementationSuggestion:") ||
            source.contains("GenerationPlanSource")
    }

    @Test
    fun productionCodeAvoidsVerifierWarnedIntellijApis() {
        val productionSources = Files.walk(Path.of("src/main/kotlin"))
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }

        val warnedApis = listOf(
            "WriteIntentReadAction",
            "FilenameIndex.getVirtualFilesByName(project, name, projectScope)",
        )
        val offenders = productionSources.flatMap { path ->
            val source = Files.readString(path)
            warnedApis.filter(source::contains).map { api -> "$path uses $api" }
        }

        assertTrue(
            offenders.isEmpty(),
            "Production code should avoid IntelliJ APIs reported by verifier warnings: ${offenders.joinToString()}",
        )
    }
}
