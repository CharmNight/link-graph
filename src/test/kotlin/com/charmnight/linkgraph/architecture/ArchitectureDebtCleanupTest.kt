package com.charmnight.linkgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureDebtCleanupTest {
    private val root: Path = Path.of("").toAbsolutePath()

    @Test
    fun llmGatewaysDoNotKeepTestOnlyFailureMessageDelegatesOrDeadJsonEscaper() {
        val gatewayFiles = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/llm/OpenAiCompatibleLlmGateway.kt",
            "src/main/kotlin/com/charmnight/linkgraph/llm/OpenAiResponsesLlmGateway.kt",
            "src/main/kotlin/com/charmnight/linkgraph/llm/AnthropicCompatibleLlmGateway.kt",
        )

        gatewayFiles.forEach { relativePath ->
            assertFalse(
                read(relativePath).contains("fun buildFailureMessage("),
                "Gateway should not keep test-only buildFailureMessage delegate: $relativePath",
            )
        }

        assertFalse(
            read("src/main/kotlin/com/charmnight/linkgraph/llm/LlmGatewaySupport.kt").contains("fun escapeJson("),
            "LlmGatewaySupport should not keep unused manual JSON escaping after payload builder moved to structured JSON.",
        )
    }

    @Test
    fun qaConversationNamingDoesNotKeepUnusedWrapperLayer() {
        val qaConversationServicePath = root.resolve("src/main/kotlin/com/charmnight/linkgraph/workbench/QaConversationService.kt")
        assertTrue(
            Files.exists(qaConversationServicePath),
            "QaConversationService should own the conversation merge implementation.",
        )
        val qaConversationService = Files.readString(qaConversationServicePath)
        assertTrue(qaConversationService.contains("class QaConversationService"))
        val qaModels = read("src/main/kotlin/com/charmnight/linkgraph/workbench/QaModels.kt")
        listOf(
            "typealias AuditConversationMessage",
            "typealias AuditConversationSession",
            "typealias AuditModelTurn",
            "typealias AuditConversationTurnResult",
            "typealias AuditConversationService",
        ).forEach { legacyAlias ->
            assertFalse(
                qaModels.contains(legacyAlias),
                "Internal Audit compatibility alias should be physically removed: $legacyAlias",
            )
        }
        assertFalse(
            qaConversationService.contains("AuditConversationService"),
            "QaConversationService must not delegate to the old AuditConversationService shell.",
        )
        assertFalse(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/workbench/AuditConversationService.kt")),
            "AuditConversationService implementation/wrapper file should be physically removed.",
        )
        val productionQaOffenders = Files.walk(root.resolve("src/main/kotlin/com/charmnight/linkgraph"))
            .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
            .filter { path -> !path.endsWith("workbench/QaModels.kt") }
            .filter { path ->
                val source = Files.readString(path)
                Regex("""AuditConversation(Service|Session|Message|TurnResult)|AuditModelTurn""").containsMatchIn(source)
            }
            .map { path -> root.relativize(path).toString() }
            .toList()
        assertTrue(
            productionQaOffenders.isEmpty(),
            "Production code should use Qa conversation names; Audit aliases are only compatibility shims: " +
                productionQaOffenders.joinToString(),
        )
    }

    @Test
    fun javaEvidenceResolversUseSharedReadActionTemplate() {
        assertTrue(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/ReadActionEvidenceResolver.kt")),
            "ReadAction resolver template should exist.",
        )
        val resolverFiles = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaEnumConstantResolver.kt",
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaMethodSymbolResolver.kt",
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaOverrideResolver.kt",
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaReflectionResolver.kt",
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaSpiResolver.kt",
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/spring/SpringEventResolver.kt",
        )
        resolverFiles.forEach { relativePath ->
            val source = read(relativePath)
            assertTrue(
                source.contains("ReadActionEvidenceResolver"),
                "Resolver should use shared ReadAction template: $relativePath",
            )
            assertFalse(
                source.contains("ReadAction.compute<ResolutionOutcome"),
                "Resolver should not hand-roll ReadAction.compute boilerplate: $relativePath",
            )
        }
    }

    @Test
    fun llmDiagnosticsUseSharedGenerationDiagnosticsInsteadOfCopiedClass() {
        assertFalse(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/llm/diagnostics/LlmGenerationDiagnostics.kt")),
            "LLM layer should reuse shared diagnostics instead of keeping a copied LlmGenerationDiagnostics class.",
        )
        assertFalse(
            read("src/main/kotlin/com/charmnight/linkgraph/llm/GraphQaPatchService.kt").contains("LlmGenerationDiagnostics"),
            "GraphQaPatchService should use shared diagnostics.",
        )
    }

    @Test
    fun graphEditorCommandRouterDoesNotExposePublicForwardingHelpers() {
        val router = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorCommandRouter.kt")
        val forwardingMethods = listOf(
            "fun requestGenerationPlanAsync(",
            "fun requestGenerationPlanDiscussionAsync(",
            "fun requestCodeDraftsAsync(",
            "fun applyCodeDrafts(",
            "fun applySingleCodeDraft(",
            "fun openCodeDraftNativeDiff(",
            "fun requestDraftNavigation(",
        )
        forwardingMethods.forEach { method ->
            assertFalse(
                router.contains(method),
                "GraphEditorCommandRouter should route messages in dispatch instead of exposing pure forwarding helper $method",
            )
        }
    }

    @Test
    fun largeWorkflowsKeepConcernSpecificSubWorkflows() {
        assertTrue(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow/review/DiffReviewWorkflow.kt")),
            "ReviewWorkflow should delegate diff review to a concern-specific sub-workflow.",
        )
        assertTrue(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow/review/GraphBeautificationReviewWorkflow.kt")),
            "ReviewWorkflow should delegate graph beautification to a concern-specific sub-workflow.",
        )
        assertTrue(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow/subject/SubjectGraphWorkflowModels.kt")),
            "SubjectGraphWorkflow should move projection/async result models into a subject workflow package.",
        )
        listOf(
            "SubjectGraphWorkflowDependencies.kt",
            "SubjectGraphWorkflowState.kt",
            "SubjectGraphRequestCoordinator.kt",
            "SubjectResolutionWorkflow.kt",
            "SubjectAnalysisWorkflow.kt",
            "SubjectAnalysisResultApplier.kt",
            "SubjectNodeAppendWorkflow.kt",
        ).forEach { fileName ->
            assertTrue(
                Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow/subject/$fileName")),
                "SubjectGraphWorkflow should delegate a focused responsibility to subject/$fileName.",
            )
        }

        val reviewWorkflow = read("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt")
        assertTrue(
            lineCount("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt") < 950,
            "ReviewWorkflow should stay below the old 1100+ line mixed-responsibility shape.",
        )
        assertFalse(
            reviewWorkflow.contains("private fun buildDiffReviewContext("),
            "Diff review context construction belongs in DiffReviewWorkflow.",
        )
        assertFalse(
            reviewWorkflow.contains("graphDiffPatchService.review("),
            "ReviewWorkflow should not keep the diff review implementation after extracting DiffReviewWorkflow.",
        )
        assertFalse(
            reviewWorkflow.contains("graphBeautificationService.beautify("),
            "ReviewWorkflow should not keep the beautification implementation after extracting GraphBeautificationReviewWorkflow.",
        )

        assertTrue(
            lineCount("src/main/kotlin/com/charmnight/linkgraph/application/workflow/SubjectGraphWorkflow.kt") < 450,
            "SubjectGraphWorkflow should stay a facade after subject workflow extraction.",
        )
        val subjectWorkflow = read("src/main/kotlin/com/charmnight/linkgraph/application/workflow/SubjectGraphWorkflow.kt")
        listOf(
            "data class InteractiveProjectionSettings",
            "data class CurrentMethodNode",
            "data class AnalysisExecutionResult",
            "data class AnalysisOutcomeAsyncResult",
            "ReadAction.nonBlocking",
            "ReadAction.compute<",
            "SemanticCapturePolicy",
            "fun computeAnalysisResultInReadAction(",
            "fun resourceNodeForHandle(",
            "fun computeCurrentMethodNode(",
            "fun locateCurrentSubject(",
            "fun resolveCodeSubjectBySignatureAsync(",
            "fun applyAnalysisResult(",
        ).forEach { model ->
            assertFalse(
                subjectWorkflow.contains(model),
                "SubjectGraphWorkflow should not keep subject implementation detail inline: $model",
            )
        }
    }

    @Test
    fun syncMethodsDoNotBlockOnAsyncMethodGetResults() {
        val workflowFiles = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/review/DiffReviewWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/review/GraphBeautificationReviewWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/GenerationPlanWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/GenerationPlanDiscussionWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/CodeDraftGenerationWorkflow.kt",
        )
        assertFalse(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow/GenerationWorkflow.kt")),
            "GenerationWorkflow facade should be physically removed rather than kept as sync/async forwarding shell.",
        )
        val offenders = workflowFiles.filter { path -> read(path).contains(".get()") }
        assertEquals(
            emptyList(),
            offenders,
            "Sync workflow methods should not be thin .get() wrappers around async methods.",
        )
    }

    private fun read(relativePath: String): String = Files.readString(root.resolve(relativePath))

    private fun lineCount(relativePath: String): Int = Files.readAllLines(root.resolve(relativePath)).size
}
