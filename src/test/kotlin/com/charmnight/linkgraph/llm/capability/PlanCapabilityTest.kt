package com.charmnight.linkgraph.llm.capability

import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.llm.tools.AgentTool
import com.charmnight.linkgraph.llm.tools.AgentToolRegistry
import com.charmnight.linkgraph.llm.tools.ToolExecutionContext
import com.charmnight.linkgraph.llm.tools.ToolResult
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.services.PlanningPayload
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class PlanCapabilityTest : BasePlatformTestCase() {
    fun testAllowsPlanGenerationWhenNoConfirmedIntentExists() {
        val capability = PlanCapability(
            defaultBudget = RunBudget(),
            planExecutor = { _, _, _ ->
                GenerationPlan(
                    source = GenerationPlanSource.MOCK,
                    summary = "plan without confirmed intent",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = PlanCapabilityInput(
                planningPayload = PlanningPayload(
                    planningGraph = GraphDocument(),
                    diff = com.charmnight.linkgraph.model.GraphDiff(),
                    previewItems = emptyList(),
                    snapshot = com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(),
                    sourceContext = emptyList(),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { com.charmnight.linkgraph.ui.GraphEditorStateSnapshot() },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("plan without confirmed intent", result.output?.summary)
        assertNull(result.finalState.failureReason)
        assertEquals(5, result.finalState.stepIndex)
    }

    fun testGeneratesPlanAfterReadingConfirmedIntent() {
        val capability = PlanCapability(
            defaultBudget = RunBudget(),
            planExecutor = { input, _, _ ->
                GenerationPlan(
                    source = GenerationPlanSource.MOCK,
                    summary = "生成计划",
                    promptPreview = input.planningPayload.planningGraph.nodes.joinToString { it.title },
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = PlanCapabilityInput(
                planningPayload = PlanningPayload(
                    planningGraph = GraphDocument(
                        nodes = listOf(
                            GraphNode(
                                id = "method:upload-file",
                                type = NodeType.METHOD,
                                title = "CommonController.uploadFile",
                            ),
                        ),
                    ),
                    diff = com.charmnight.linkgraph.model.GraphDiff(),
                    previewItems = emptyList(),
                    snapshot = com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        draftWorkbenchState = DraftWorkbenchState(
                            draftChanges = listOf(
                                DraftWorkbenchEntry(
                                    entryId = "draft-1",
                                    kind = DraftEntryKind.CHANGE,
                                    title = "修改上传逻辑",
                                ),
                            ),
                        ),
                    ),
                    sourceContext = emptyList(),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        draftWorkbenchState = DraftWorkbenchState(
                            draftChanges = listOf(
                                DraftWorkbenchEntry(
                                    entryId = "draft-1",
                                    kind = DraftEntryKind.CHANGE,
                                    title = "修改上传逻辑",
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("生成计划", result.output?.summary)
        assertEquals(5, result.finalState.stepIndex)
        assertEquals(
            listOf(ArtifactType.CONFIRMED_INTENT, ArtifactType.GRAPH_DIFF, ArtifactType.PLAN),
            result.finalState.artifactRefs.map { it.type },
        )
    }

    fun testBuildsPlanInputFromRuntimeArtifactsAndToolsInsteadOfInitialPayload() {
        val runtimeScope = EditScope(
            scopeId = "scope-runtime",
            targetNodeId = "method:upload-file",
            filePath = "src/main/java/com/example/CommonController.java",
            language = "JAVA",
            symbolKind = "METHOD",
            symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):void",
            startLine = 10,
            endLine = 20,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        val runtimeEntry = DraftWorkbenchEntry(
            entryId = "draft-runtime",
            kind = DraftEntryKind.CHANGE,
            title = "runtime confirmed",
            editScopes = listOf(runtimeScope),
        )
        val staleEntry = DraftWorkbenchEntry(
            entryId = "draft-stale",
            kind = DraftEntryKind.CHANGE,
            title = "stale snapshot confirmed",
        )
        val runtimeDiff = GraphDiff(
            status = DiffStatus.MODIFIED,
            entries = listOf(
                GraphDiffEntry(
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "method:upload-file",
                    status = DiffStatus.MODIFIED,
                    message = "runtime diff",
                ),
            ),
        )
        var sawDraftWorkbench = false
        var sawGraphDiff = false
        var sawReadSourceSnippet = false
        var capturedPayload: PlanningPayload? = null
        val capability = PlanCapability(
            defaultBudget = RunBudget(),
            planExecutor = { input, _, _ ->
                capturedPayload = input.planningPayload
                GenerationPlan(
                    source = GenerationPlanSource.MOCK,
                    summary = "runtime plan",
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_draft_workbench"
                        override val description: String = "fake draft workbench"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            sawDraftWorkbench = true
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "candidateCount" to 0,
                                    "confirmedCount" to 1,
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_confirmed_intent"
                        override val description: String = "fake confirmed intent"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            val ref = context.artifactStore.save(
                                ConfirmedIntentArtifact(
                                    artifactId = "confirmed-runtime",
                                    entry = runtimeEntry,
                                ),
                            )
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "confirmedCount" to 1,
                                    "confirmedIntents" to listOf(runtimeEntry),
                                    "artifactRefs" to listOf(ref),
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_graph_diff"
                        override val description: String = "fake graph diff"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            sawGraphDiff = true
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "diff" to runtimeDiff,
                                    "nodeChanges" to 1,
                                    "edgeChanges" to 0,
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source snippet"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            sawReadSourceSnippet = true
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "snippet" to "RUNTIME_SNIPPET",
                                ),
                            )
                        }
                    },
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = PlanCapabilityInput(
                planningPayload = PlanningPayload(
                    planningGraph = GraphDocument(),
                    diff = GraphDiff(),
                    previewItems = emptyList(),
                    snapshot = com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        draftWorkbenchState = DraftWorkbenchState(
                            draftChanges = listOf(staleEntry),
                        ),
                    ),
                    sourceContext = listOf(
                        SourceSnippetContext(
                            nodeId = "method:upload-file",
                            filePath = runtimeScope.filePath,
                            startLine = runtimeScope.startLine,
                            endLine = runtimeScope.endLine,
                            snippet = "STALE_PRELOADED_SNIPPET",
                        ),
                    ),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { com.charmnight.linkgraph.ui.GraphEditorStateSnapshot() },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("runtime plan", result.output?.summary)
        assertTrue(sawDraftWorkbench)
        assertTrue(sawGraphDiff)
        assertTrue(sawReadSourceSnippet)
        assertEquals(listOf(runtimeEntry), capturedPayload?.snapshot?.draftWorkbenchState?.draftChanges)
        assertEquals(runtimeDiff, capturedPayload?.diff)
        assertEquals("RUNTIME_SNIPPET", capturedPayload?.sourceContext?.singleOrNull()?.snippet)
    }

    fun testStopsReadingAdditionalFilesWithinSamePlanStepAfterBudgetIsExhausted() {
        var readCount = 0
        var executorInvoked = false
        val firstScope = EditScope(
            scopeId = "scope-1",
            targetNodeId = "method:first",
            filePath = "src/main/java/com/example/First.java",
            language = "JAVA",
            symbolKind = "METHOD",
            startLine = 1,
            endLine = 2,
        )
        val secondScope = EditScope(
            scopeId = "scope-2",
            targetNodeId = "method:second",
            filePath = "src/main/java/com/example/Second.java",
            language = "JAVA",
            symbolKind = "METHOD",
            startLine = 1,
            endLine = 2,
        )
        val capability = PlanCapability(
            defaultBudget = RunBudget(maxFilesRead = 1),
            planExecutor = { _, _, _ ->
                executorInvoked = true
                GenerationPlan(
                    source = GenerationPlanSource.MOCK,
                    summary = "不应该执行到这里",
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_draft_workbench"
                        override val description: String = "fake draft workbench"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("candidateCount" to 0, "confirmedCount" to 1))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_confirmed_intent"
                        override val description: String = "fake confirmed intent"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            val refs = listOf(firstScope, secondScope).mapIndexed { index, scope ->
                                context.artifactStore.save(
                                    ConfirmedIntentArtifact(
                                        artifactId = "confirmed-$index",
                                        entry = DraftWorkbenchEntry(
                                            entryId = "draft-$index",
                                            kind = DraftEntryKind.CHANGE,
                                            title = "change-$index",
                                            editScopes = listOf(scope),
                                        ),
                                    ),
                                )
                            }
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "confirmedCount" to 2,
                                    "confirmedIntents" to emptyList<Any>(),
                                    "artifactRefs" to refs,
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_graph_diff"
                        override val description: String = "fake graph diff"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf("diff" to GraphDiff()),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source snippet"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            readCount += 1
                            return ToolResult(toolName = name, payload = mapOf("snippet" to "class PlanBudget {}"))
                        }
                    },
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = PlanCapabilityInput(
                planningPayload = PlanningPayload(
                    planningGraph = GraphDocument(),
                    diff = GraphDiff(),
                    previewItems = emptyList(),
                    snapshot = com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(),
                    sourceContext = emptyList(),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { com.charmnight.linkgraph.ui.GraphEditorStateSnapshot() },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertFalse(executorInvoked)
        assertEquals(1, readCount)
        assertEquals(null, result.output)
        assertEquals(AgentRunFailureReason.MAX_FILES_READ_EXCEEDED, result.finalState.failureReason)
    }

    fun testSkipsProjectExternalConfirmedIntentEvidence() {
        val sourceFile = Files.createTempFile("plan-external-evidence", ".java")
        Files.writeString(
            sourceFile,
            """
            class PlanExternalEvidence {
                void submit() {
                    validate();
                }
                void validate() {}
            }
            """.trimIndent(),
        )
        val externalScope = EditScope(
            scopeId = "scope-external",
            targetNodeId = "method:submit",
            filePath = sourceFile.toString(),
            language = "JAVA",
            symbolKind = "METHOD",
            symbolSignature = "com.example.PlanExternalEvidence.submit():void",
            startLine = 1,
            endLine = 4,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        var capturedPayload: PlanningPayload? = null
        val capability = PlanCapability(
            defaultBudget = RunBudget(),
            planExecutor = { input, _, _ ->
                capturedPayload = input.planningPayload
                GenerationPlan(
                    source = GenerationPlanSource.MOCK,
                    summary = "skip external evidence",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = PlanCapabilityInput(
                planningPayload = PlanningPayload(
                    planningGraph = GraphDocument(),
                    diff = GraphDiff(),
                    previewItems = emptyList(),
                    snapshot = com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        draftWorkbenchState = DraftWorkbenchState(
                            draftChanges = listOf(
                                DraftWorkbenchEntry(
                                    entryId = "draft-runtime",
                                    kind = DraftEntryKind.CHANGE,
                                    title = "runtime confirmed",
                                    editScopes = listOf(externalScope),
                                ),
                            ),
                        ),
                    ),
                    sourceContext = emptyList(),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        draftWorkbenchState = DraftWorkbenchState(
                            draftChanges = listOf(
                                DraftWorkbenchEntry(
                                    entryId = "draft-runtime",
                                    kind = DraftEntryKind.CHANGE,
                                    title = "runtime confirmed",
                                    editScopes = listOf(externalScope),
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("skip external evidence", result.output?.summary)
        assertNotNull(capturedPayload)
        assertTrue(capturedPayload!!.sourceContext.isEmpty())
    }
}
