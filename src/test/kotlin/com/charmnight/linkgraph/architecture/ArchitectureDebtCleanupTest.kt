package com.charmnight.linkgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import com.charmnight.linkgraph.json.JsonCodec
import com.charmnight.linkgraph.ui.protocol.GraphEditorProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureDebtCleanupTest {
    private val root: Path = Path.of("").toAbsolutePath()
    private val canonicalProjectionPath = "src/main/kotlin/com/charmnight/linkgraph/projection/"

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
            read("src/main/kotlin/com/charmnight/linkgraph/llm/LlmGatewayClient.kt").contains("fun escapeJson("),
            "LlmGatewayClient should not keep unused manual JSON escaping after payload builder moved to structured JSON.",
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
        val obsoletePrefix = "Au" + "dit"
        listOf(
            "typealias ${obsoletePrefix}ConversationMessage",
            "typealias ${obsoletePrefix}ConversationSession",
            "typealias ${obsoletePrefix}ModelTurn",
            "typealias ${obsoletePrefix}ConversationTurnResult",
            "typealias ${obsoletePrefix}ConversationService",
        ).forEach { legacyAlias ->
            assertFalse(
                qaModels.contains(legacyAlias),
                "Obsolete QA compatibility alias should be physically removed: $legacyAlias",
            )
        }
        assertFalse(
            qaConversationService.contains("${obsoletePrefix}ConversationService"),
            "QaConversationService must not delegate to the obsolete conversation service shell.",
        )
        assertFalse(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/workbench/${obsoletePrefix}ConversationService.kt")),
            "Obsolete conversation service implementation/wrapper file should be physically removed.",
        )
        val productionQaOffenders = Files.walk(root.resolve("src/main/kotlin/com/charmnight/linkgraph"))
            .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
            .filter { path -> !path.endsWith("workbench/QaModels.kt") }
            .filter { path ->
                val source = Files.readString(path)
                Regex("""${obsoletePrefix}Conversation(Service|Session|Message|TurnResult)|${obsoletePrefix}ModelTurn""").containsMatchIn(source)
            }
            .map { path -> root.relativize(path).toString() }
            .toList()
        assertTrue(
            productionQaOffenders.isEmpty(),
            "Production code should use Qa conversation names; obsolete conversation aliases must not remain: " +
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
    fun springEventInvestigationResolverOnlyConsumesSharedJvmRelationIndex() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/spring/SpringEventResolver.kt")
        assertTrue(
            source.contains("JvmEvidenceIndexAdapter"),
            "Spring Event investigation resolver should enter through the shared ArchitectureGraphIndex adapter.",
        )
        assertTrue(
            source.contains("JvmRelationKind.SPRING_EVENT_LISTENS"),
            "Spring Event investigation resolver should consume SPRING_EVENT_LISTENS from JvmRelationIndex.",
        )
        listOf(
            "JavaPsiFacade",
            "PsiTreeUtil",
            "PsiClass",
            "PsiMethod",
            "allScope(",
            "projectScope(",
            "JavaRecursiveElementVisitor",
            "PsiRecursiveElementVisitor",
            "findPsiClass",
            "findPsiMethod",
            "collectElements",
        ).forEach { forbidden ->
            assertFalse(
                source.contains(forbidden),
                "Spring Event investigation resolver must not keep PSI fallback path: $forbidden",
            )
        }
    }

    @Test
    fun investigationJavaResolversDoNotKeepPsiFallbackPaths() {
        val resolverFiles = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaEnumConstantResolver.kt",
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaMethodSymbolResolver.kt",
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaOverrideResolver.kt",
            "src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JvmInvestigationEvidenceSupport.kt",
        )
        resolverFiles.forEach { relativePath ->
            val source = read(relativePath)
            assertTrue(
                source.contains("ArchitectureGraphIndex") || source.contains("JvmEvidenceIndexAdapter"),
                "Investigation resolver should consume the shared ArchitectureGraphIndex path: $relativePath",
            )
            listOf(
                "JavaPsiFacade",
                "PsiTreeUtil",
                "PsiClass",
                "PsiMethod",
                "PsiShortNamesCache",
                "FilenameIndex",
                "OverridingMethodsSearch",
            ).forEach { forbidden ->
                assertFalse(
                    source.contains(forbidden),
                    "Investigation resolver must not keep PSI fallback path: $relativePath contains $forbidden",
                )
            }
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
    fun localRuleQaResultsNeedRuntimeEvidenceTrustBeforeConfirmableCandidatePath() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/llm/GraphQaPatchService.kt")
        val classifierBlock = source.substringAfter("private fun classifyQaOutputs(")
            .substringBefore("private fun normalizeCandidateChanges(")
        val candidatePathGuardCount = Regex("""canUseConfirmableCandidatePath\(source,\s*runtimeEvidenceTrusted\)""")
            .findAll(classifierBlock)
            .count()

        assertTrue(
            classifierBlock.contains("source: LlmResultSource"),
            "QA output classification must receive result source so LOCAL_RULE cannot be treated like remote/runtime output.",
        )
        assertTrue(
            classifierBlock.contains("runtimeEvidenceTrusted: Boolean"),
            "QA output classification must know whether LOCAL_RULE came from trusted runtime evidence.",
        )
        assertTrue(
            candidatePathGuardCount >= 2,
            "Confirmable QA candidate path must be guarded both for candidate normalization and risk-thread promotion.",
        )
        assertTrue(
            source.contains("source != LlmResultSource.LOCAL_RULE || runtimeEvidenceTrusted"),
            "LOCAL_RULE QA output may enter confirmable candidate path only when runtime evidence marked it trusted.",
        )
        assertFalse(
            source.contains("buildMockCandidateChangeId("),
            "Local rule QA must not keep a helper for building confirmable candidate changes.",
        )
    }

    @Test
    fun transportEnvelopeTypesComeFromSharedProtocolContract() {
        val contractPath = root.resolve("protocol/graph-editor-transport-contract.json")
        assertTrue(
            Files.exists(contractPath),
            "Transport envelope contract must be shared outside either Kotlin or TypeScript source tree.",
        )
        val contract = JsonCodec.parseObject(Files.readString(contractPath))
        val transportTypes = (contract["incrementalTransportTypes"] as? List<*>)
            ?.map { it.toString() }
            .orEmpty()

        assertEquals(
            listOf("ARTIFACT_SLICE", "FEEDBACK_SLICE"),
            transportTypes,
            "Protocol contract must explicitly list every supported incremental transport slice.",
        )
        assertEquals<List<String>>(
            transportTypes,
            GraphEditorProtocol.INCREMENTAL_TRANSPORT_TYPES,
            "Kotlin transport constants must stay aligned with the shared protocol contract.",
        )

        val frontendTypes = read("web/src/app/types.ts")
        assertTrue(
            frontendTypes.contains("LinkGraphFeedbackSliceEnvelope"),
            "Frontend transport envelope union must include FEEDBACK_SLICE from the shared protocol.",
        )
        assertFalse(
            frontendTypes.contains("export type LinkGraphIncrementalTransportEnvelope = LinkGraphArtifactSliceEnvelope;"),
            "Frontend incremental envelope type must not collapse back to the old artifact-only contract.",
        )

        val buildScript = read("build.gradle.kts")
        assertTrue(
            buildScript.contains("""val frontendTransportContract = layout.projectDirectory.file("protocol/graph-editor-transport-contract.json")"""),
            "Gradle frontend tasks must model the shared transport contract as a named input.",
        )
        assertTrue(
            Regex("""val frontendInputs = files\([\s\S]*frontendTransportContract""").containsMatchIn(buildScript),
            "Gradle frontend inputs must include the shared transport contract so protocol edits invalidate tests/builds.",
        )
    }

    @Test
    fun productionCodeUsesTypedSourceMetadataAccessors() {
        val allowed = setOf(
            "src/main/kotlin/com/charmnight/linkgraph/model/GraphMetadataKeys.kt",
            "src/main/kotlin/com/charmnight/linkgraph/model/GraphSourceLocation.kt",
        )
        val sourceMetadataPattern = Regex(""""source\.(filePath|virtualFileUrl|startOffset|endOffset|startLine|endLine|column|origin|decompiled)"""")
        val offenders = productionKotlinSources()
            .mapNotNull { path ->
                val relativePath = root.relativize(path).toString()
                if (relativePath in allowed) {
                    return@mapNotNull null
                }
                val matches = sourceMetadataPattern.findAll(Files.readString(path))
                    .map { match -> match.value }
                    .distinct()
                    .toList()
                relativePath.takeIf { matches.isNotEmpty() }?.let { "$it: ${matches.joinToString()}" }
            }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "Production source metadata access must go through GraphSourceLocation/GraphMetadataKeys accessors.",
        )
    }

    @Test
    fun qaCapabilityUsesRuntimeEvidenceInputInsteadOfLegacyContextBridge() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/llm/capability/QaCapability.kt")

        assertTrue(
            source.contains("data class QaRuntimeEvidenceInput("),
            "QA runtime should model collected graph/code evidence before adapting it for the executor.",
        )
        assertTrue(
            source.contains("private fun buildRuntimeEvidenceInput("),
            "QA capability should build runtime-native evidence input instead of mutating the old QA context directly.",
        )
        assertTrue(
            source.contains("private fun QaRuntimeEvidenceInput.toExecutorInput("),
            "Only the executor boundary should adapt runtime evidence back into QaCapabilityInput.",
        )
        listOf(
            "旧问答执行器",
            "buildAugmentedInput(",
        ).forEach { forbidden ->
            assertFalse(
                source.contains(forbidden),
                "QA runtime context cleanup should remove legacy bridge marker: $forbidden",
            )
        }
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
    fun graphEditorApplicationServiceDelegatesWorkflowAndCommandAssemblyToCompositionRoot() {
        val service = read("src/main/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationService.kt")

        listOf(
            "src/main/kotlin/com/charmnight/linkgraph/application/composition/ApplicationWorkflowComposition.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/composition/ApplicationCommandComposition.kt",
        ).forEach { relativePath ->
            assertTrue(
                Files.exists(root.resolve(relativePath)),
                "Composition root should own application assembly helper: $relativePath",
            )
        }

        assertTrue(
            service.contains("ApplicationWorkflowComposition("),
            "GraphEditorApplicationService should delegate workflow construction to ApplicationWorkflowComposition.",
        )
        assertTrue(
            service.contains("ApplicationCommandComposition("),
            "GraphEditorApplicationService should delegate command dispatcher construction to ApplicationCommandComposition.",
        )
        assertFalse(
            service.contains("SubjectApplicationCommandHandler("),
            "GraphEditorApplicationService should not inline the command handler list.",
        )
    }

    @Test
    fun graphEditorPageRendererKeepsPresentationPayloadMappersOutOfBootstrapShell() {
        assertTrue(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPresentationPayloadMappers.kt")),
            "GraphEditorPageRenderer should delegate presentation/indexed summary payload mapping to a focused helper.",
        )
        assertTrue(
            lineCount("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRenderer.kt") < 1400,
            "GraphEditorPageRenderer should stay below the old bootstrap-plus-presentation-mapping size.",
        )
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

    @Test
    fun jvmRelationResolversUseCentralPsiFactIndexForSymbolLookup() {
        val relationDir = root.resolve("src/main/kotlin/com/charmnight/linkgraph/jvm/relation")
        val offenders = Files.walk(relationDir)
            .filter { path -> Files.isRegularFile(path) && path.toString().endsWith("Resolver.kt") }
            .filter { path ->
                val source = Files.readString(path)
                "PsiManager.getInstance" in source ||
                    "JavaPsiFacade.getInstance" in source ||
                    "VirtualFileManager.getInstance" in source
            }
            .map { path -> root.relativize(path).toString() }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "JVM relation resolvers should use JvmResolutionContext/JvmPsiFactIndex instead of reopening PSI files independently.",
        )
    }

    @Test
    fun architectureIndexSemanticResolverDoesNotBridgePsiMethodLookupInline() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/semantic/provider/code/relation/ArchitectureIndexSemanticResolver.kt")
        listOf(
            "JavaPsiFacade",
            "GlobalSearchScope",
            "findPsiMethod",
        ).forEach { forbidden ->
            assertFalse(
                source.contains(forbidden),
                "ArchitectureIndexSemanticResolver should request method signatures from RelationExtractionContext instead of reopening PSI: $forbidden",
            )
        }
        assertTrue(
            source.contains("context.methodBySignature"),
            "ArchitectureIndexSemanticResolver should use RelationExtractionContext for PSI method localization.",
        )
    }

    @Test
    fun graphProjectionTraversalStateOnlyLivesInCanonicalProjectionPackage() {
        val traversalMarkers = listOf(
            "hiddenNodeIds",
            "hiddenEdgeIds",
            "private fun overflowNode(",
        )
        val offenders = productionKotlinSources()
            .filterNot { path -> root.relativize(path).toString().startsWith(canonicalProjectionPath) }
            .filter { path ->
                val source = Files.readString(path)
                traversalMarkers.any(source::contains)
            }
            .map { path -> root.relativize(path).toString() }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "Graph-window traversal state and overflow node factories belong in the canonical projection package.",
        )
    }

    @Test
    fun graphHiddenCountsComeFromSharedProjectionApi() {
        val forbiddenHiddenCountImplementations = listOf(
            "data class GraphViewHiddenCounts(",
            "fun graphViewHiddenCounts(",
            "private fun hiddenLayerCounts(",
            "private fun hiddenNodeCount(",
            "private fun hiddenEdgeCount(",
            "fullGraph.nodes.size - visibleGraph.nodes.size",
            "fullGraph.edges.size - visibleGraph.edges.size",
            "graph.nodes.size - visibleGraph.nodes.size",
            "graph.edges.size - visibleGraph.edges.size",
        )
        val offenders = productionKotlinSources()
            .filterNot { path -> root.relativize(path).toString().startsWith(canonicalProjectionPath) }
            .filter { path ->
                val source = Files.readString(path)
                forbiddenHiddenCountImplementations.any(source::contains)
            }
            .map { path -> root.relativize(path).toString() }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "Hidden node/edge/layer counts should be calculated through the shared projection API.",
        )
    }

    @Test
    fun graphProjectionMetadataKeysAreDefinedOnce() {
        val metadataKeyPattern = Regex(""""(linkGraph\.overflow\.[^"]+|linkGraph\.hidden[^"]*|indexed\.collapsed[^"]*)"""")
        val offenders = productionKotlinSources()
            .filterNot { path -> root.relativize(path).toString() == "${canonicalProjectionPath}GraphProjectionMetadata.kt" }
            .mapNotNull { path ->
                val relativePath = root.relativize(path).toString()
                val keys = metadataKeyPattern.findAll(Files.readString(path))
                    .map { match -> match.groupValues[1] }
                    .distinct()
                    .toList()
                relativePath.takeIf { keys.isNotEmpty() }?.let { it to keys }
            }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "Projection overflow/hidden/collapsed metadata keys should be defined in GraphProjectionMetadata only.",
        )
    }

    @Test
    fun graphProjectionKernelOwnsTraversalAndLegacyProjectorsStayThin() {
        listOf(
            "GraphProjectionPolicy.kt",
            "GraphProjectionResult.kt",
            "GraphProjectionKernel.kt",
        ).forEach { fileName ->
            assertTrue(
                Files.exists(root.resolve("$canonicalProjectionPath$fileName")),
                "Canonical projection API must include $fileName.",
            )
        }

        val legacyProjectors = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/projection/GraphWindowProjector.kt",
            "src/main/kotlin/com/charmnight/linkgraph/projection/InteractiveGraphProjector.kt",
        )
        val traversalMarkers = listOf(
            "val visibleNodeIds",
            "val visibleEdgeIds",
            "hiddenNodeIds",
            "hiddenEdgeIds",
            "private fun overflowNode(",
            "ArrayDeque",
            "PriorityQueue",
        )
        val offenders = legacyProjectors.flatMap { relativePath ->
            val source = read(relativePath)
            traversalMarkers.mapNotNull { marker ->
                if (source.contains(marker)) "$relativePath contains $marker" else null
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "Legacy projector entrypoints should delegate to GraphProjectionKernel instead of owning traversal state.",
        )
    }

    @Test
    fun frontendProductionDoesNotDeriveBackendHiddenCounts() {
        val productionFrontendSources = Files.walk(root.resolve("web/src/app")).use { paths ->
            paths
                .filter { path ->
                    Files.isRegularFile(path) && Regex("""\.(ts|tsx)$""").containsMatchIn(path.fileName.toString())
                }
                .filter { path ->
                    val relative = root.relativize(path).toString()
                    relative != "web/src/app/sampleState.ts" &&
                        relative != "web/src/app/testBootstrapState.ts" &&
                        relative != "web/src/app/draftCompareProjection.ts"
                }
                .toList()
            }

        val forbiddenFragments = listOf(
            "function graphHiddenCounts(",
            "const hiddenCounts = graphHiddenCounts(",
            "hiddenNodeCount: hiddenCounts.hiddenNodeCount",
            "hiddenEdgeCount: hiddenCounts.hiddenEdgeCount",
        )
        val offenders = productionFrontendSources.flatMap { path ->
            val source = Files.readString(path)
            forbiddenFragments.mapNotNull { fragment ->
                if (source.contains(fragment)) "${root.relativize(path)} contains $fragment" else null
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "Frontend production code must preserve backend projection summaries instead of recomputing hidden counts.",
        )
    }

    @Test
    fun indexedGraphBridgeAcceptsPresetRequestsOnly() {
        val parserSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPayloadParser.kt")
        assertTrue(
            parserSource.contains("IndexedGraphRequestFactory.fromPreset"),
            "Indexed graph bridge parser should expand backend-owned presets.",
        )
        listOf(
            "root.enumValue<IndexedGraphView>(\"view\")",
            "includeProjectSources = root.booleanOrDefault",
            "refreshPolicy = root.enumValue<IndexedGraphRefreshPolicy>",
        ).forEach { legacyFragment ->
            assertFalse(
                parserSource.contains(legacyFragment),
                "Indexed graph bridge parser must not keep legacy full-request fallback: $legacyFragment",
            )
        }
    }

    @Test
    fun bridgeCommandParserDoesNotKeepUnusedLegacyBooleanHelper() {
        val parserSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/bridge/BridgeCommandParser.kt")

        assertFalse(
            parserSource.contains("private fun Map<*, *>.booleanOrDefault"),
            "Bridge command parser should not keep the unused boolean helper after command parsing moved to explicit envelopes.",
        )
    }

    @Test
    fun testFixturesDoNotUseDeletedProjectServicesPackageAsCurrentContext() {
        val obsoletePackage = "com.charmnight.linkgraph." + "services"
        val testRoots = listOf(
            "src/test/kotlin",
            "src/integrationTest/kotlin",
        ).map(root::resolve)
            .filter(Files::exists)

        val packageOrImportOffenders = testRoots.flatMap { testRoot ->
            Files.walk(testRoot)
                .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
                .flatMap { path ->
                    val source = Files.readString(path)
                    listOf(
                        "package $obsoletePackage",
                        "import $obsoletePackage.",
                    ).filter(source::contains)
                        .map { marker -> "${root.relativize(path)} contains $marker" }
                        .stream()
                }
                .toList()
        }

        assertEquals(
            emptyList(),
            packageOrImportOffenders,
            "Test fixtures should not keep the deleted production services package as their current context.",
        )
        assertFalse(
            Files.exists(root.resolve("src/test/kotlin/com/charmnight/linkgraph/" + "services")),
            "The old test services directory should be physically removed after migrating fixtures to current packages.",
        )

        val obsoleteHelpers = listOf(
            "registerLinkGraphProject" + "CommandServicesForTest",
            "linkGraph" + "ApplicationServiceForTest",
        )
        val helperOffenders = testRoots.flatMap { testRoot ->
            Files.walk(testRoot)
                .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
                .flatMap { path ->
                    val source = Files.readString(path)
                    obsoleteHelpers.filter(source::contains)
                        .map { helper -> "${root.relativize(path)} contains $helper" }
                        .stream()
                }
                .toList()
        }

        assertEquals(
            emptyList(),
            helperOffenders,
            "Application-service tests should use GraphEditorApplicationService fixture helpers, not deleted ProjectService wording.",
        )
    }

    private fun read(relativePath: String): String = Files.readString(root.resolve(relativePath))

    private fun lineCount(relativePath: String): Int = Files.readAllLines(root.resolve(relativePath)).size

    private fun productionKotlinSources(): List<Path> =
        Files.walk(root.resolve("src/main/kotlin/com/charmnight/linkgraph"))
            .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
            .toList()
}
