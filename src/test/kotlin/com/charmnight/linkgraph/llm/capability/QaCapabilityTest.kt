package com.charmnight.linkgraph.llm.capability

import com.charmnight.linkgraph.llm.GraphAuditContext
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.llm.tools.AgentTool
import com.charmnight.linkgraph.llm.tools.AgentToolRegistry
import com.charmnight.linkgraph.llm.tools.ToolExecutionContext
import com.charmnight.linkgraph.llm.tools.ToolResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.AuditConversationMessage
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.AuditMessageRole
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaCapabilityTest : BasePlatformTestCase() {
    fun testBuildsQaInitialStateFromQuestionAndUsesQaCapabilityId() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            auditExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "当前证据不足，需要继续读取代码。",
                    promptPreview = "prompt",
                    warnings = listOf("远程失败，已回退。"),
                )
            },
        )

        val state = capability.buildInitialState(
            input = QaCapabilityInput(
                question = "解释上传链路",
                auditContext = GraphAuditContext(),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { null },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("qa", capability.capabilityId)
        assertEquals("解释上传链路", state.userGoal)
        assertEquals("qa", state.capabilityId)
    }

    fun testPreservesFallbackWarningsWhenAuditExecutorFallsBack() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            auditExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "已回退到本地规则。",
                    promptPreview = "prompt",
                    warnings = listOf("远程 LLM 问答失败，已回退到本地规则。"),
                )
            },
        )

        val result = capability.executeAudit(
            input = QaCapabilityInput(
                question = "请围绕当前链路进行问答",
                auditContext = GraphAuditContext(),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { null },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.warnings.single().contains("已回退"))
    }

    fun testUsesGraphToolBeforeRunningAuditExecutor() {
        var toolInvoked = false
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            auditExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "先读图，再执行旧问答。",
                    promptPreview = "prompt",
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_draft_workbench"
                        override val description: String = "fake draft workbench reader"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "candidateCount" to 0,
                                    "confirmedCount" to 0,
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_selected_scope"
                        override val description: String = "fake selected scope reader"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "selectedNodeIds" to emptyList<String>(),
                                    "selectedCount" to 0,
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_current_graph"
                        override val description: String = "fake graph reader"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            toolInvoked = true
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "graph" to GraphDocument(
                                        nodes = listOf(
                                            GraphNode(
                                                id = "method:upload-file",
                                                type = NodeType.METHOD,
                                                title = "CommonController.uploadFile",
                                            ),
                                        ),
                                    ),
                                    "nodeCount" to 1,
                                    "selectedNodeIds" to emptyList<String>(),
                                ),
                            )
                        }
                    },
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请围绕当前链路进行问答",
                auditContext = GraphAuditContext(),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:upload-file",
                                    type = NodeType.METHOD,
                                    title = "CommonController.uploadFile",
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertTrue(toolInvoked)
        assertEquals("先读图，再执行旧问答。", result.output?.answer)
        assertEquals(4, result.finalState.stepIndex)
        assertEquals("get_draft_workbench", result.finalState.stepRecords.first().toolName)
        assertEquals("get_selected_scope", result.finalState.stepRecords[1].toolName)
    }

    fun testReadsCodeEvidenceBeforeRunningAuditExecutorWhenSourceContextIsMissing() {
        val sourceFile = Path.of(requireNotNull(project.basePath))
            .resolve("src/main/java/com/example/QaCapabilityUploadService.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            class UploadService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            auditExecutor = { input, _, _ ->
                capturedSourceContext = input.auditContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "已读取代码证据。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请结合代码解释这里为什么会走 fallback",
                auditContext = GraphAuditContext(
                    selectedNodeIds = listOf("method:upload-file"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:upload-file",
                                    type = NodeType.METHOD,
                                    title = "UploadService.submit",
                                    signature = "com.example.UploadService.submit(java.lang.String):java.lang.String",
                                    metadata = mapOf(
                                        "source.filePath" to sourceFile.toString(),
                                        "source.startLine" to "1",
                                        "source.endLine" to "5",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("已读取代码证据。", result.output?.answer)
        assertEquals(4, result.finalState.stepIndex)
        assertEquals("read_symbol", result.finalState.stepRecords[2].toolName)
        assertTrue(result.finalState.artifactRefs.any { it.type == ArtifactType.CODE_EVIDENCE })
        val snippet = requireNotNull(capturedSourceContext.singleOrNull())
        assertTrue(snippet.snippet?.contains("fallback(request)") == true)
    }

    fun testStopsBeforeAuditExecutionWhenCodeReadExceedsBudget() {
        val sourceFile = Path.of(requireNotNull(project.basePath))
            .resolve("src/main/java/com/example/QaCapabilityBudgetGuard.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            class BudgetGuard {
                String submit(String request) {
                    return request.trim();
                }
            }
            """.trimIndent(),
        )
        var executorInvoked = false
        val capability = QaCapability(
            defaultBudget = RunBudget(maxFilesRead = 0),
            auditExecutor = { input, _, _ ->
                executorInvoked = true
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "不应该执行到这里。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请解释这里的字符串处理逻辑",
                auditContext = GraphAuditContext(
                    selectedNodeIds = listOf("method:submit"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:submit",
                                    type = NodeType.METHOD,
                                    title = "BudgetGuard.submit",
                                    signature = "com.example.BudgetGuard.submit(java.lang.String):java.lang.String",
                                    metadata = mapOf(
                                        "source.filePath" to sourceFile.toString(),
                                        "source.startLine" to "1",
                                        "source.endLine" to "5",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertFalse(executorInvoked)
        assertNull(result.output)
        assertEquals(AgentRunFailureReason.MAX_FILES_READ_EXCEEDED, result.finalState.failureReason)
    }

    fun testStopsReadingAdditionalFilesWithinSameQaStepAfterBudgetIsExhausted() {
        var readCount = 0
        var executorInvoked = false
        val capability = QaCapability(
            defaultBudget = RunBudget(maxFilesRead = 1),
            auditExecutor = { input, _, _ ->
                executorInvoked = true
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "不应该执行到这里。",
                    promptPreview = "prompt",
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_draft_workbench"
                        override val description: String = "fake draft workbench reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "candidateCount" to 0,
                                    "confirmedCount" to 0,
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_selected_scope"
                        override val description: String = "fake selected scope reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "selectedNodeIds" to listOf("method:first", "method:second"),
                                    "selectedCount" to 2,
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_current_graph"
                        override val description: String = "fake graph reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "graph" to GraphDocument(
                                        nodes = listOf(
                                            GraphNode(
                                                id = "method:first",
                                                type = NodeType.METHOD,
                                                title = "first",
                                                metadata = mapOf(
                                                    "source.filePath" to "src/main/java/com/example/First.java",
                                                    "source.startLine" to "1",
                                                    "source.endLine" to "2",
                                                ),
                                            ),
                                            GraphNode(
                                                id = "method:second",
                                                type = NodeType.METHOD,
                                                title = "second",
                                                metadata = mapOf(
                                                    "source.filePath" to "src/main/java/com/example/Second.java",
                                                    "source.startLine" to "1",
                                                    "source.endLine" to "2",
                                                ),
                                            ),
                                        ),
                                    ),
                                    "nodeCount" to 2,
                                    "selectedNodeIds" to listOf("method:first", "method:second"),
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "resolve_anchor"
                        override val description: String = "fake anchor resolver"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            val nodeId = input["nodeId"]?.toString()
                            val node = context.snapshot.workingGraph?.nodes?.firstOrNull { it.id == nodeId }
                            return ToolResult(
                                toolName = name,
                                payload = mapOf("node" to node),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source snippet reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            readCount += 1
                            return ToolResult(
                                toolName = name,
                                payload = mapOf("snippet" to "class BudgetGuard {}"),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_symbol"
                        override val description: String = "unused fake symbol reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = emptyMap())
                        }
                    },
                    object : AgentTool {
                        override val name: String = "create_candidate_draft"
                        override val description: String = "unused fake candidate draft writer"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = emptyMap())
                        }
                    },
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请解释这两个节点",
                auditContext = GraphAuditContext(
                    selectedNodeIds = listOf("method:first", "method:second"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:first",
                                    type = NodeType.METHOD,
                                    title = "first",
                                    metadata = mapOf(
                                        "source.filePath" to "src/main/java/com/example/First.java",
                                        "source.startLine" to "1",
                                        "source.endLine" to "2",
                                    ),
                                ),
                                GraphNode(
                                    id = "method:second",
                                    type = NodeType.METHOD,
                                    title = "second",
                                    metadata = mapOf(
                                        "source.filePath" to "src/main/java/com/example/Second.java",
                                        "source.startLine" to "1",
                                        "source.endLine" to "2",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertFalse(executorInvoked)
        assertEquals(1, readCount)
        assertNull(result.output)
        assertEquals(AgentRunFailureReason.MAX_FILES_READ_EXCEEDED, result.finalState.failureReason)
    }

    fun testStopsReadingAdditionalSnippetsWithinSameQaStepAfterSnippetBudgetIsExhausted() {
        var readCount = 0
        var executorInvoked = false
        val capability = QaCapability(
            defaultBudget = RunBudget(maxFilesRead = 5, maxSnippets = 1),
            auditExecutor = { input, _, _ ->
                executorInvoked = true
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "不应该执行到这里。",
                    promptPreview = "prompt",
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_draft_workbench"
                        override val description: String = "fake draft workbench reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("candidateCount" to 0, "confirmedCount" to 0))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_selected_scope"
                        override val description: String = "fake selected scope reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("selectedNodeIds" to listOf("method:first", "method:second")))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_current_graph"
                        override val description: String = "fake graph reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "graph" to GraphDocument(
                                        nodes = listOf(
                                            GraphNode(
                                                id = "method:first",
                                                type = NodeType.METHOD,
                                                title = "first",
                                                metadata = mapOf(
                                                    "source.filePath" to "src/main/java/com/example/First.java",
                                                    "source.startLine" to "1",
                                                    "source.endLine" to "2",
                                                ),
                                            ),
                                            GraphNode(
                                                id = "method:second",
                                                type = NodeType.METHOD,
                                                title = "second",
                                                metadata = mapOf(
                                                    "source.filePath" to "src/main/java/com/example/Second.java",
                                                    "source.startLine" to "1",
                                                    "source.endLine" to "2",
                                                ),
                                            ),
                                        ),
                                    ),
                                    "selectedNodeIds" to listOf("method:first", "method:second"),
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "resolve_anchor"
                        override val description: String = "fake anchor resolver"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            val nodeId = input["nodeId"]?.toString()
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "node" to context.snapshot.workingGraph?.nodes?.firstOrNull { it.id == nodeId },
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source snippet reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            readCount += 1
                            return ToolResult(toolName = name, payload = mapOf("snippet" to "class SnippetBudget {}"))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_symbol"
                        override val description: String = "unused fake symbol reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = emptyMap())
                        }
                    },
                    object : AgentTool {
                        override val name: String = "create_candidate_draft"
                        override val description: String = "unused fake candidate draft writer"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = emptyMap())
                        }
                    },
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请解释这两个节点",
                auditContext = GraphAuditContext(selectedNodeIds = listOf("method:first", "method:second")),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:first",
                                    type = NodeType.METHOD,
                                    title = "first",
                                    metadata = mapOf(
                                        "source.filePath" to "src/main/java/com/example/First.java",
                                        "source.startLine" to "1",
                                        "source.endLine" to "2",
                                    ),
                                ),
                                GraphNode(
                                    id = "method:second",
                                    type = NodeType.METHOD,
                                    title = "second",
                                    metadata = mapOf(
                                        "source.filePath" to "src/main/java/com/example/Second.java",
                                        "source.startLine" to "1",
                                        "source.endLine" to "2",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertFalse(executorInvoked)
        assertEquals(1, readCount)
        assertNull(result.output)
        assertEquals(AgentRunFailureReason.MAX_SNIPPETS_EXCEEDED, result.finalState.failureReason)
    }

    fun testStopsBeforeAuditExecutionWhenSingleSnippetExceedsLineBudget() {
        var executorInvoked = false
        val capability = QaCapability(
            defaultBudget = RunBudget(maxSnippetLines = 1),
            auditExecutor = { input, _, _ ->
                executorInvoked = true
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "不应该执行到这里。",
                    promptPreview = "prompt",
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_draft_workbench"
                        override val description: String = "fake draft workbench reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("candidateCount" to 0, "confirmedCount" to 0))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_selected_scope"
                        override val description: String = "fake selected scope reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("selectedNodeIds" to listOf("method:only")))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "get_current_graph"
                        override val description: String = "fake graph reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "graph" to GraphDocument(
                                        nodes = listOf(
                                            GraphNode(
                                                id = "method:only",
                                                type = NodeType.METHOD,
                                                title = "only",
                                                metadata = mapOf(
                                                    "source.filePath" to "src/main/java/com/example/Only.java",
                                                    "source.startLine" to "1",
                                                    "source.endLine" to "2",
                                                ),
                                            ),
                                        ),
                                    ),
                                    "selectedNodeIds" to listOf("method:only"),
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "resolve_anchor"
                        override val description: String = "fake anchor resolver"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "node" to context.snapshot.workingGraph?.nodes?.firstOrNull { it.id == "method:only" },
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source snippet reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("snippet" to "line1\nline2"))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_symbol"
                        override val description: String = "unused fake symbol reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = emptyMap())
                        }
                    },
                    object : AgentTool {
                        override val name: String = "create_candidate_draft"
                        override val description: String = "unused fake candidate draft writer"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = emptyMap())
                        }
                    },
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请解释这个节点",
                auditContext = GraphAuditContext(selectedNodeIds = listOf("method:only")),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:only",
                                    type = NodeType.METHOD,
                                    title = "only",
                                    metadata = mapOf(
                                        "source.filePath" to "src/main/java/com/example/Only.java",
                                        "source.startLine" to "1",
                                        "source.endLine" to "2",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertFalse(executorInvoked)
        assertNull(result.output)
        assertEquals(AgentRunFailureReason.MAX_SNIPPET_LINES_EXCEEDED, result.finalState.failureReason)
    }

    fun testPersistsCandidateDraftArtifactsFromQaResult() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            auditExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "建议形成候选草稿。",
                    promptPreview = "prompt",
                    candidateChanges = listOf(
                        CandidateDraftChange(
                            changeId = "change-upload-condition",
                            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                            title = "修改上传条件判断",
                            reason = "原条件错误。",
                        ),
                    ),
                    newCandidateChanges = listOf(
                        CandidateDraftChange(
                            changeId = "change-upload-condition",
                            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                            title = "修改上传条件判断",
                            reason = "原条件错误。",
                        ),
                    ),
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请给出候选修改建议",
                auditContext = GraphAuditContext(),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:upload-file",
                                    type = NodeType.METHOD,
                                    title = "CommonController.uploadFile",
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("建议形成候选草稿。", result.output?.answer)
        assertTrue(result.finalState.artifactRefs.any { it.type == ArtifactType.CANDIDATE_DRAFT })
        assertTrue(result.artifactSummaries.any { it.artifactType == ArtifactType.CANDIDATE_DRAFT.name })
    }

    fun testPreloadedSourceContextStillConsumesRuntimeBudget() {
        var executorInvoked = false
        val capability = QaCapability(
            defaultBudget = RunBudget(maxFilesRead = 0),
            auditExecutor = { input, _, _ ->
                executorInvoked = true
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "不应该执行到这里。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请解释预读源码的行为",
                auditContext = GraphAuditContext(
                    sourceContext = listOf(
                        SourceSnippetContext(
                            nodeId = "method:upload-file",
                            filePath = "src/main/java/com/example/CommonController.java",
                            startLine = 10,
                            endLine = 20,
                            snippet = "void uploadFile(String file) {}",
                        ),
                    ),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:upload-file",
                                    type = NodeType.METHOD,
                                    title = "CommonController.uploadFile",
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertFalse(executorInvoked)
        assertNull(result.output)
        assertEquals(AgentRunFailureReason.MAX_FILES_READ_EXCEEDED, result.finalState.failureReason)
    }

    fun testRebuildsAuditInputFromRuntimeArtifactsWithoutOverwritingGraphContext() {
        val sourceFile = Path.of(requireNotNull(project.basePath))
            .resolve("src/main/java/com/example/QaRuntimeArtifactsController.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            class UploadService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val runtimeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "UploadService.submit",
                    signature = "com.example.UploadService.submit(java.lang.String):java.lang.String",
                    metadata = mapOf(
                        "source.filePath" to sourceFile.toString(),
                        "source.startLine" to "1",
                        "source.endLine" to "5",
                    ),
                ),
                GraphNode(
                    id = "call:fallback",
                    type = NodeType.FLOW_ACTION,
                    title = "fallback(request)",
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge-submit-fallback",
                    type = EdgeType.CALL,
                    fromNodeId = "method:upload-file",
                    toNodeId = "call:fallback",
                    label = "calls",
                ),
            ),
        )
        val staleGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:stale",
                    type = NodeType.METHOD,
                    title = "StaleController.oldPath",
                ),
            ),
        )
        var capturedAuditContext: GraphAuditContext? = null
        var capturedSession: AuditConversationSession? = null
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            auditExecutor = { input, _, _ ->
                capturedAuditContext = input.auditContext
                capturedSession = input.session
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "runtime qa",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "解释这里为什么会 fallback",
                auditContext = GraphAuditContext(
                    factGraph = staleGraph,
                    editableGraph = staleGraph,
                    selectedNodeIds = listOf("method:upload-file"),
                    sourceContext = listOf(
                        SourceSnippetContext(
                            nodeId = "method:stale",
                            filePath = "src/main/java/com/example/StaleController.java",
                            startLine = 1,
                            endLine = 3,
                            snippet = "STALE_SNIPPET",
                        ),
                    ),
                ),
                session = AuditConversationSession(
                    sessionId = "session-1",
                    scopeKey = "scope-1",
                    messages = listOf(
                        AuditConversationMessage(
                            messageId = "message-1",
                            role = AuditMessageRole.USER,
                            content = "历史问题",
                        ),
                    ),
                    candidateChanges = listOf(
                        CandidateDraftChange(
                            changeId = "change-1",
                            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                            title = "existing candidate",
                        ),
                    ),
                    investigationThreads = listOf(
                        InvestigationThread(
                            threadId = "thread-1",
                            status = InvestigationThreadStatus.OPEN,
                            title = "existing thread",
                        ),
                    ),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        selectedNodeId = "method:upload-file",
                        workingGraph = runtimeGraph,
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("runtime qa", result.output?.answer)
        assertEquals(listOf("method:upload-file"), capturedAuditContext?.selectedNodeIds)
        assertEquals(staleGraph.nodes.map { it.id }.toSet(), capturedAuditContext?.factGraph?.nodes?.map { it.id }?.toSet())
        assertEquals(staleGraph.nodes.map { it.id }.toSet(), capturedAuditContext?.editableGraph?.nodes?.map { it.id }?.toSet())
        assertEquals(sourceFile.toString(), capturedAuditContext?.sourceContext?.singleOrNull()?.filePath)
        assertTrue(capturedAuditContext?.sourceContext?.singleOrNull()?.snippet?.contains("fallback") == true)
        assertEquals(1, capturedSession?.messages?.size)
        assertEquals("历史问题", capturedSession?.messages?.singleOrNull()?.content)
        assertEquals(1, capturedSession?.candidateChanges?.size)
        assertEquals(1, capturedSession?.investigationThreads?.size)
    }

    fun testSkipsProjectExternalCodeEvidenceForQaRuntime() {
        val sourceFile = Files.createTempFile("qa-external-evidence", ".java")
        Files.writeString(
            sourceFile,
            """
            class QaExternalEvidence {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            auditExecutor = { input, _, _ ->
                capturedSourceContext = input.auditContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.MOCK,
                    question = input.question,
                    answer = "未读取项目外代码证据。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请结合代码解释这里为什么会走 fallback",
                auditContext = GraphAuditContext(
                    selectedNodeIds = listOf("method:upload-file"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:upload-file",
                                    type = NodeType.METHOD,
                                    title = "QaExternalEvidence.submit",
                                    signature = "com.example.QaExternalEvidence.submit(java.lang.String):java.lang.String",
                                    metadata = mapOf(
                                        "source.filePath" to sourceFile.toString(),
                                        "source.startLine" to "1",
                                        "source.endLine" to "5",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("未读取项目外代码证据。", result.output?.answer)
        assertTrue(capturedSourceContext.isEmpty())
        assertTrue(result.finalState.artifactRefs.none { it.type == ArtifactType.CODE_EVIDENCE })
    }
}
