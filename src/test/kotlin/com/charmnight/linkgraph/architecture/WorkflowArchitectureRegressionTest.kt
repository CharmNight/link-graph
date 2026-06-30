package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowArchitectureRegressionTest {
    @Test
    fun deletedFacadeDoesNotKeepTestHooksInProductionState() {
        assertFalse(
            Files.exists(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt")),
            "Legacy project-service facade must stay deleted instead of carrying test overrides.",
        )
        val applicationRoot = Path.of("src/main/kotlin/com/charmnight/linkgraph/application")
        val offenders = Files.walk(applicationRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                Files.readString(path).lineSequence().mapIndexedNotNull { index, line ->
                    if (line.contains("LinkGraphProjectTestOverrides") || line.contains("OverrideProvider")) {
                        "${applicationRoot.relativize(path)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }.toList()
            }
        assertTrue(
            offenders.isEmpty(),
            "application production code must not expose test override hooks: " + offenders.joinToString(),
        )
    }

    @Test
    fun confirmedDraftDoesNotKeepLegacySnapshotWorkflowBesideUseCase() {
        assertFalse(
            Files.exists(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/ConfirmedDraftChangeWorkflow.kt")),
            "Confirmed draft must use ConfirmDraftChangeUseCase; the legacy GraphEditorStateSnapshot workflow must be deleted.",
        )
        assertFalse(
            Files.exists(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/ConfirmedDraftChangeSyncWorkflow.kt")),
            "Confirmed draft must not keep a SyncWorkflow mixed-responsibility entrypoint.",
        )
        val coordinator = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ConfirmedDraftChangeCoordinator.kt"))
        assertFalse(
            coordinator.contains("GraphEditorStateSnapshot"),
            "Confirmed draft coordinator should adapt through application snapshots/results, not UI snapshots.",
        )
    }

    @Test
    fun asyncWorkflowsDelegateThreadHopsToLifecycleSupport() {
        val generationWorkflows = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/GenerationPlanWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/GenerationPlanDiscussionWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/CodeDraftGenerationWorkflow.kt",
        ).map { path -> Files.readString(Path.of(path)) }
        val reviewWorkflow = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt"))
        val subjectWorkflow = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow/SubjectGraphWorkflow.kt"))

        assertFalse(
            generationWorkflows.any { workflow -> workflow.contains("ApplicationManager.getApplication().executeOnPooledThread") },
            "Generation sub-workflows 应通过统一生命周期支持调度后台请求，而不是继续手写线程切换模板。",
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
    fun workflowLayerUsesTaskRunnerPortForThreadingPrimitives() {
        val workflowRoot = Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow")
        val forbiddenPatterns = listOf(
            Regex("""\bApplicationManager\b""") to "ApplicationManager",
            Regex("""\bModalityState\b""") to "ModalityState",
            Regex("""\bAppExecutorUtil\b""") to "AppExecutorUtil",
            Regex("""\binvokeAndWait\b""") to "invokeAndWait",
            Regex("""\binvokeLater\b""") to "invokeLater",
            Regex("""\bexecuteOnPooledThread\b""") to "executeOnPooledThread",
        )
        val offenders = Files.walk(workflowRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                val source = Files.readString(path)
                forbiddenPatterns.mapNotNull { (pattern, label) ->
                    if (pattern.containsMatchIn(source)) {
                        "${workflowRoot.relativize(path)} contains $label"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "application/workflow must route IntelliJ threading primitives through TaskRunner: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun applicationLayerDoesNotImportLlmImplementationPackages() {
        val applicationRoot = Path.of("src/main/kotlin/com/charmnight/linkgraph/application")
        val offenders = Files.walk(applicationRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                Files.readString(path).lineSequence().mapIndexedNotNull { index, line ->
                    if (line.trim().startsWith("import com.charmnight.linkgraph.llm")) {
                        "${applicationRoot.relativize(path)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }.toList()
            }

        assertTrue(
            offenders.isEmpty(),
            "application layer must depend on neutral/application contracts, not llm implementation packages: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun architectureWorkflowDoesNotHoldReadActionAcrossCompleteIndexBuild() {
        val workflow = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow/architecture/ArchitectureGraphWorkflow.kt"))

        assertFalse(
            workflow.contains("ReadAction\n            .nonBlocking<ArchitectureGraphViewResult>"),
            "Architecture graph workflow must not keep the full symbol, relation and graph build inside one non-blocking read action.",
        )
        assertFalse(
            workflow.contains(".inSmartMode(project)"),
            "Architecture graph workflow should let the runtime bound its PSI read sections instead of wrapping the whole request in smart-mode read action plumbing.",
        )
    }

    @Test
    fun architectureRuntimeUsesWritePriorityReadActionsForPsiIndexing() {
        val runtime = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/architecture/ArchitectureIndexRuntime.kt"))

        assertFalse(
            runtime.contains("ReadAction.compute<T, RuntimeException>"),
            "Architecture index runtime must not hold a synchronous read action across large project PSI scans.",
        )
        assertTrue(
            runtime.contains("ReadAction.nonBlocking<T>"),
            "Architecture index runtime should run PSI reads through non-blocking read actions so pending IDE writes can interrupt and resume indexing.",
        )
        assertTrue(
            runtime.contains(".executeSynchronously()"),
            "Synchronous runtime callers may wait on the background thread, but the read action itself must remain write-priority cancellable.",
        )
    }

    @Test
    fun architectureRuntimeDoesNotHashFileContentsInsideSliceManifestReadAction() {
        val runtime = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/architecture/ArchitectureIndexRuntime.kt"))
        val method = Regex(
            """private fun currentProjectSliceInputFiles[\s\S]*?sourceComponents\.attachedJarIndex\.fingerprints""",
        ).find(runtime)?.value ?: error("currentProjectSliceInputFiles method body not found")

        assertFalse(
            method.contains("fileFingerprint(") ||
                method.contains("virtualFileContentSha256") ||
                method.contains("contentsToByteArray()") ||
                method.contains("Files.readAllBytes"),
            "Slice manifest read action must collect VFS metadata only; content hashing must run outside the read action.",
        )
    }

    @Test
    fun evidenceResolversDoNotBuildFullIndexInsideSynchronousReadActions() {
        val resolvingRoot = Path.of("src/main/kotlin/com/charmnight/linkgraph/investigation/resolving")
        val readActionIndexBuild = Regex(
            """override\s+fun\s+resolveInReadAction[\s\S]*?buildIndex\(context\.project\)""",
        )
        val offenders = Files.walk(resolvingRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .toList()
            .filter { path -> readActionIndexBuild.containsMatchIn(Files.readString(path)) }
        val adapter = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JvmEvidenceIndexAdapter.kt"),
        )

        assertTrue(
            offenders.isEmpty(),
            "Evidence resolvers must acquire/build ArchitectureGraphIndex before entering synchronous read actions: $offenders",
        )
        assertTrue(
            adapter.contains("isReadAccessAllowed") && adapter.contains("currentIndexProvider"),
            "JvmEvidenceIndexAdapter must reuse an existing index during read access instead of starting a full build.",
        )
    }

    @Test
    fun typeUsageResolverDoesNotRescanEveryIndexedFieldForEveryClass() {
        val resolver = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/jvm/relation/TypeUsageRelationResolver.kt"))
        val extractor = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/jvm/relation/ClassDiagramRelationExtractor.kt"))
        val repeatedGlobalFieldScan = Regex(
            """context\.symbolIndex\.fieldsByQualifiedName\.values\s*\.asSequence\(\)\s*\.filter\s*\{\s*field -> field\.ownerClassName == classSymbol\.qualifiedName""",
        )

        assertFalse(
            repeatedGlobalFieldScan.containsMatchIn(resolver),
            "Type usage resolution must pre-group indexed fields by owner; rescanning all fields for every class makes large project graphs take minutes.",
        )
        assertTrue(
            resolver.contains("ClassDiagramRelationExtractor.extractPsiTypeRelations(context)"),
            "Type usage resolver should delegate class-diagram type semantics to the unified extractor.",
        )
        assertTrue(
            extractor.contains("fieldsByOwnerClassName"),
            "Unified class diagram extraction should keep an owner -> fields index for per-class lookup.",
        )
    }

    @Test
    fun typeUsageResolverDoesNotResolveEveryJavaReferenceExpression() {
        val resolver = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/jvm/relation/TypeUsageRelationResolver.kt"))

        assertFalse(
            resolver.contains("visitReferenceElement"),
            "Type usage resolution should not walk every Java reference expression; that duplicates call aggregation and makes large project graphs take minutes.",
        )
        assertFalse(
            resolver.contains("reference.resolve()"),
            "Type usage resolution should rely on structural type evidence instead of resolving every reference expression.",
        )
    }

    @Test
    fun architectureWorkflowSupportUsesOverviewIndexForProjectStructureRequests() {
        val support = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow/architecture/ArchitectureIndexWorkflowSupport.kt"))

        assertTrue(
            support.contains("buildArchitectureOverviewIndex(request)"),
            "Default project-structure requests must use a bounded overview index instead of waiting for complete method-call relation indexing.",
        )
        assertTrue(
            support.contains("IndexedGraphView.ARCHITECTURE"),
            "The overview index decision must be scoped to architecture graph requests, not class diagram or review requests.",
        )
    }

    @Test
    fun architectureGraphBuilderDoesNotClassifyProjectClassesAsDependencyGroups() {
        val builder = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/architecture/ArchitectureGraphBuilder.kt"))
        val dependencyGroupMethod = builder.substringAfter("private fun dependencyGroupNodes(")
            .substringBefore("private fun layerNodes(")

        assertTrue(
            dependencyGroupMethod.contains(".filterNot(classifier::isProjectSourceClass)"),
            "Dependency grouping should skip project source classes before calling projectNodeForClass; otherwise graph build repeats expensive component classification for every source class.",
        )
    }

    @Test
    fun architectureGraphBuilderPrecomputesProjectComponentTargetsForOverviewProjection() {
        val builder = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/architecture/ArchitectureGraphBuilder.kt"))
        val targetCache = builder.substringAfter("private class ArchitectureProjectionTargetCache(")

        assertTrue(
            targetCache.contains("componentTargetsByClassName"),
            "Overview projection should precompute source class -> component targets once; per-relation component classification makes large project structure graphs slow.",
        )
        assertFalse(
            targetCache.contains("classifier.componentGroupFor("),
            "Overview projection cache must not call componentGroupFor from the hot lookup path.",
        )
    }

    @Test
    fun architectureOverviewTextIndexerUsesClassNameNotKeywordAsJavaSymbolName() {
        val builder = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/architecture/ArchitectureOverviewSymbolIndexBuilder.kt"))

        assertFalse(
            builder.contains("val simpleName = match.groupValues[3]"),
            "Overview text indexing must read the Java class name group, not the keyword/header group; otherwise project structure opens with no component nodes.",
        )
        assertTrue(
            builder.contains("val simpleName = match.groupValues[2]"),
            "Overview text indexing should use the Java class-name capture group for JvmClassSymbol.simpleName.",
        )
    }

    @Test
    fun reviewWorkflowUsesQaModeContextInsteadOfNakedEffectiveModePlumbing() {
        val reviewWorkflow = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt"))

        assertFalse(
            reviewWorkflow.contains("effectiveMode(request)"),
            "ReviewWorkflow 必须把 ReplayableQaRequest 分类成一次 QaModeContext，不能重复调用 effectiveMode(request)。",
        )
        assertFalse(
            Regex("""private fun \w+\([^)]*effectiveMode: QaMode""").containsMatchIn(reviewWorkflow),
            "ReviewWorkflow 私有辅助方法应接收 QaModeContext，而不是裸 effectiveMode 参数。",
        )
        assertFalse(
            reviewWorkflow.contains("private fun normalizeQaResult("),
            "问答结果归一化应放在 QaResultNormalizer 中。",
        )
        assertFalse(
            reviewWorkflow.contains("private fun applyModeBoundary("),
            "模式边界逻辑应放在 QaResultNormalizer 中。",
        )
    }

    @Test
    fun graphEditorCommandRouterDoesNotUseServiceLocatorForwarding() {
        val source = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorCommandRouter.kt"))

        assertFalse(source.contains("private fun projectService()"))
        assertFalse(source.contains("projectService()."))
        assertTrue(
            source.contains("private val"),
            "GraphEditorCommandRouter 应持有明确的协作者边界，而不是每个分支动态拉取 project service。",
        )
    }

    @Test
    fun workflowTestsUseProductionApplicationEventProjection() {
        val adapters = Files.readString(Path.of("src/test/kotlin/com/charmnight/linkgraph/testing/WorkflowBoundaryTestAdapters.kt"))

        assertTrue(
            adapters.contains("GraphEditorApplicationEventProjector"),
            "Workflow tests should reuse production application-event projection instead of shadowing event handling.",
        )
        assertFalse(
            adapters.contains("when (event)"),
            "Workflow test adapters must not duplicate production event-to-state projection logic.",
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
