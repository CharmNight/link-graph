package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.toWorkflowEditorSnapshot
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanningContextFactoryTest {
    @Test
    fun buildQaGraphsPreservesReferenceFactGraphWhileUsingWorkingGraphAsEditableGraph() {
        val factGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                ),
            ),
        )
        val editableGraph = GraphDocument(
            nodes = factGraph.nodes + GraphNode(
                id = "scope:file-download-if",
                type = NodeType.FLOW_SCOPE,
                title = "if (delete)",
            ),
        )
        val snapshot = testSnapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            referenceFactGraph = factGraph,
            workingGraph = editableGraph,
        )

        val qaGraphs = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState() },
        ).buildQaGraphs(
            snapshot = snapshot.toWorkflowEditorSnapshot(),
            selectedNodeIds = emptyList(),
            collectSourceEvidence = false,
        )

        assertEquals(factGraph, qaGraphs.factGraph)
        assertEquals(editableGraph, qaGraphs.editableGraph)
    }

    @Test
    fun buildGraphBeautificationContextIncludesExpandedInvocationNodesFromWorkingGraph() {
        val callerSignature = "com.example.Caller.run():void"
        val targetSignature = "com.example.SystemService.createInfo():void"
        val expansionId = "invocation:expansion-1"
        val callerMethod = GraphNode(
            id = "method:caller",
            type = NodeType.METHOD,
            title = "Caller.run",
            signature = callerSignature,
            metadata = mapOf("flowchart.kind" to "ENTRY"),
        )
        val invocationNode = GraphNode(
            id = "invoke:create-info",
            type = NodeType.FLOW_ACTION,
            title = "systemService.createInfo()",
            signature = targetSignature,
            metadata = mapOf(
                "flow.kind" to "INVOCATION",
                "flow.ownerMethod" to callerSignature,
                "flowchart.kind" to "SUBROUTINE",
            ),
        )
        val expandedMethod = GraphNode(
            id = "method:create-info",
            type = NodeType.METHOD,
            title = "SystemService.createInfo",
            signature = targetSignature,
            metadata = mapOf(
                "flow.ownerMethod" to targetSignature,
                "flowchart.kind" to "ENTRY",
                "linkGraph.expansion.id" to expansionId,
                "linkGraph.expansion.sourceInvocationNodeId" to invocationNode.id,
                "linkGraph.expansion.kind" to "INVOCATION",
            ),
        )
        val expandedAction = GraphNode(
            id = "action:save-info",
            type = NodeType.FLOW_ACTION,
            title = "saveInfo()",
            metadata = mapOf(
                "flow.kind" to "ACTION",
                "flow.ownerMethod" to targetSignature,
                "flowchart.kind" to "PROCESS",
                "linkGraph.expansion.id" to expansionId,
                "linkGraph.expansion.sourceInvocationNodeId" to invocationNode.id,
                "linkGraph.expansion.kind" to "INVOCATION",
            ),
        )
        val workingGraph = GraphDocument(
            nodes = listOf(callerMethod, invocationNode, expandedMethod, expandedAction),
            edges = listOf(
                GraphEdge(
                    id = "control:caller-to-invoke",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = callerMethod.id,
                    toNodeId = invocationNode.id,
                ),
                GraphEdge(
                    id = "call:invoke-to-expanded-method",
                    type = EdgeType.CALL,
                    fromNodeId = invocationNode.id,
                    toNodeId = expandedMethod.id,
                    metadata = mapOf("linkGraph.expansion.id" to expansionId),
                ),
                GraphEdge(
                    id = "control:expanded-method-to-save",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = expandedMethod.id,
                    toNodeId = expandedAction.id,
                    metadata = mapOf(
                        "linkGraph.expansion.id" to expansionId,
                        "linkGraph.expansion.sourceInvocationNodeId" to invocationNode.id,
                    ),
                ),
            ),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(callerMethod, invocationNode),
            edges = listOf(workingGraph.edges.first()),
        )
        val snapshot = testSnapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            visibleGraph = visibleGraph,
            workingGraph = workingGraph,
            selectedMethodSignature = callerSignature,
            selectedNodeId = callerMethod.id,
            workingGraphDirty = true,
        )

        val context = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState() },
        ).buildGraphBeautificationContext(
            snapshot = snapshot.toWorkflowEditorSnapshot(),
            goal = "解释展开后的调用链",
            preferredStyle = null,
            explanationFocus = null,
            followUp = null,
            granularity = com.charmnight.linkgraph.workbench.StepGranularity.BUSINESS,
        )

        assertEquals(
            setOf(callerMethod.id, invocationNode.id, expandedMethod.id, expandedAction.id),
            context.presentationContext.graph.nodes.map(GraphNode::id).toSet(),
        )
        assertTrue(context.presentationContext.graph.edges.any { edge ->
            edge.fromNodeId == expandedMethod.id && edge.toNodeId == expandedAction.id
        })
    }

    @Test
    fun computePlanningPayloadDoesNotPreloadGenerationSourceSnippetsFromConfirmedChangesAndPlanScopes() {
        val sourceFile = Files.createTempFile("generation-source-context", ".java")
        val sourceCode = """
            package com.example;

            class CommonController {
                public String fileDownload(String baseUrl) {
                    if (baseUrl.startsWith("/usr")) {
                        return baseUrl.replaceFirst("/usr", "/tmp");
                    }
                    return baseUrl;
                }

                public void uploadFile(String file) {
                    validate(file);
                }

                private void validate(String file) {
                }
            }
        """.trimIndent()
        Files.writeString(sourceFile, sourceCode)

        val downloadSnippet = """
            public String fileDownload(String baseUrl) {
                if (baseUrl.startsWith("/usr")) {
                    return baseUrl.replaceFirst("/usr", "/tmp");
                }
                return baseUrl;
            }
        """.trimIndent()
        val uploadSnippet = """
            public void uploadFile(String file) {
                validate(file);
            }
        """.trimIndent()
        val uploadStartOffset = sourceCode.indexOf(uploadSnippet)
        val uploadEndOffset = uploadStartOffset + uploadSnippet.length

        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                    metadata = mapOf(
                        "source.filePath" to sourceFile.toString(),
                        "source.startLine" to "4",
                        "source.endLine" to "9",
                    ),
                ),
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "CommonController.uploadFile",
                    signature = "com.example.CommonController.uploadFile(java.lang.String):void",
                    metadata = mapOf(
                        "source.filePath" to sourceFile.toString(),
                        "source.startOffset" to uploadStartOffset.toString(),
                        "source.endOffset" to uploadEndOffset.toString(),
                        "source.startLine" to "11",
                        "source.endLine" to "13",
                    ),
                ),
            ),
        )
        val snapshot = testSnapshot(
            workingGraph = graph,
            draftWorkbenchState = DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-file-download",
                        kind = DraftEntryKind.CHANGE,
                        sourceChangeId = "change-file-download",
                        title = "修改 fileDownload",
                        targetNodeIds = listOf("method:file-download"),
                        beforeState = "原逻辑直接返回 baseUrl。",
                        afterState = "增加 /usr 到 /tmp 的改写。",
                        reason = "统一 Linux 临时目录。",
                        impactSummary = "影响下载路径。",
                        editScopes = listOf(
                            EditScope(
                                scopeId = "scope-file-download",
                                targetNodeId = "method:file-download",
                                filePath = sourceFile.toString(),
                                language = "JAVA",
                                symbolKind = "METHOD",
                                symbolSignature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                                startLine = 4,
                                endLine = 9,
                                allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val generationPlan = GenerationPlan(
            source = GenerationPlanSource.REMOTE,
            summary = "修改 CommonController 的两个方法。",
            items = listOf(
                GenerationPlanItem(
                    id = "plan-upload-file",
                    title = "修改 uploadFile",
                    description = "补上传校验。",
                    risk = SyncPreviewRisk.MEDIUM,
                    targetPath = sourceFile.toString(),
                ),
            ),
        )

        val payload = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState() },
        ).computePlanningPayload(snapshot.toWorkflowEditorSnapshot(), generationPlanOverride = generationPlan)

        assertTrue(payload.sourceContext.isEmpty())
    }

    @Test
    fun computePlanningPayloadDoesNotReadProjectRelativePlanScopeAgainstProjectBasePath() {
        val projectDir = Files.createTempDirectory("planning-context-project-base")
        val sourceFile = projectDir.resolve("src/main/java/com/example/CommonController.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
                package com.example;

                class CommonController {
                    public void uploadFile(String file) {
                        validate(file);
                    }

                    private void validate(String file) {
                    }
                }
            """.trimIndent(),
        )

        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "CommonController.uploadFile",
                    signature = "com.example.CommonController.uploadFile(java.lang.String):void",
                    metadata = mapOf(
                        "source.filePath" to sourceFile.toString(),
                        "source.startLine" to "4",
                        "source.endLine" to "6",
                    ),
                ),
            ),
        )
        val snapshot = testSnapshot(workingGraph = graph)
        val generationPlan = GenerationPlan(
            source = GenerationPlanSource.REMOTE,
            summary = "修改 uploadFile",
            items = listOf(
                GenerationPlanItem(
                    id = "plan-upload-file",
                    title = "修改 uploadFile",
                    description = "补上传校验。",
                    risk = SyncPreviewRisk.MEDIUM,
                    targetPath = "src/main/java/com/example/CommonController.java",
                ),
            ),
        )

        val payload = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState(llmEnabled = true, provider = "MOCK") },
            projectBasePathProvider = { projectDir.toString() },
        ).computePlanningPayload(snapshot.toWorkflowEditorSnapshot(), generationPlanOverride = generationPlan)

        assertTrue(payload.sourceContext.isEmpty())
    }
}
