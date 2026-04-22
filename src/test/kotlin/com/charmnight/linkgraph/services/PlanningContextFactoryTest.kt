package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanningContextFactoryTest {
    @Test
    fun buildAuditGraphsPreservesReferenceFactGraphWhileUsingWorkingGraphAsEditableGraph() {
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
        val snapshot = GraphEditorStateService.Snapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            referenceFactGraph = factGraph,
            workingGraph = editableGraph,
        )

        val auditGraphs = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState() },
        ).buildAuditGraphs(
            snapshot = snapshot,
            selectedNodeIds = emptyList(),
            collectSourceEvidence = false,
        )

        assertEquals(factGraph, auditGraphs.factGraph)
        assertEquals(editableGraph, auditGraphs.editableGraph)
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
        val snapshot = GraphEditorStateService.Snapshot(
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
        ).computePlanningPayload(snapshot, generationPlanOverride = generationPlan)

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
        val snapshot = GraphEditorStateService.Snapshot(workingGraph = graph)
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
        ).computePlanningPayload(snapshot, generationPlanOverride = generationPlan)

        assertTrue(payload.sourceContext.isEmpty())
    }
}
