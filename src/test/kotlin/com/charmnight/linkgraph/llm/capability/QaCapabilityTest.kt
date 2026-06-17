package com.charmnight.linkgraph.llm.capability

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.GraphQaContext
import com.charmnight.linkgraph.llm.EvidenceTraceEntry
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmProviderPresets
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
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
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphSceneId
import com.charmnight.linkgraph.ui.view.FlowchartSummary
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.GraphProjectionIndex
import com.charmnight.linkgraph.ui.view.GraphProjectionMappingKind
import com.charmnight.linkgraph.ui.view.GraphProjectionNodeMapping
import com.charmnight.linkgraph.workbench.QaConversationMessage
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.QaMode
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
    fun testReviewModesAllowReviewGraphTools() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "ok",
                    promptPreview = "prompt",
                )
            },
        )
        val baseInput = QaCapabilityInput(
            question = "解释链路",
            qaContext = GraphQaContext(),
        )

        assertFalse("get_blast_radius" in capability.allowedTools(baseInput))

        val reviewTools = capability.allowedTools(baseInput.copy(effectiveMode = QaMode.REVIEW))
        assertTrue("get_changed_symbols" in reviewTools)
        assertTrue("get_blast_radius" in reviewTools)
        assertTrue("find_related_tests" in reviewTools)
        assertTrue("build_review_evidence_bundle" in reviewTools)
    }

    fun testQaModeAllowsProjectExploreAndQueryTools() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "ok",
                    promptPreview = "prompt",
                )
            },
        )
        val tools = capability.allowedTools(
            QaCapabilityInput(
                question = "解释项目结构",
                qaContext = GraphQaContext(),
            ),
        )

        assertTrue("explore_project_context" in tools)
        assertTrue("query_project_graph" in tools)
        assertTrue("find_project_path" in tools)
        assertTrue("explain_project_node" in tools)
        assertTrue("affected_project_nodes" in tools)
        assertTrue("get_project_index_digest" in tools)
    }

    fun testQaAnswerModeDoesNotAllowGraphMutationTool() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "ok",
                    promptPreview = "prompt",
                )
            },
        )

        val tools = capability.allowedTools(
            QaCapabilityInput(
                question = "解释当前图，不要修改",
                qaContext = GraphQaContext(),
                effectiveMode = QaMode.ANSWER,
            ),
        )

        assertFalse("edit_graph" in tools)
    }

    fun testAllowedToolsReflectInjectedRegistryAndReviewModeGate() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "ok",
                    promptPreview = "prompt",
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    fakeTool("custom_context"),
                    fakeTool("get_blast_radius"),
                ),
            ),
        )
        val baseInput = QaCapabilityInput(
            question = "解释项目结构",
            qaContext = GraphQaContext(),
        )

        assertEquals(setOf("custom_context"), capability.allowedTools(baseInput))
        assertEquals(
            setOf("custom_context", "get_blast_radius"),
            capability.allowedTools(baseInput.copy(effectiveMode = QaMode.REVIEW)),
        )
    }

    fun testBuildsQaInitialStateFromQuestionAndUsesQaCapabilityId() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
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
                qaContext = GraphQaContext(),
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

    fun testRuntimeBudgetUsesConfiguredQaTimeout() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "ok",
                    promptPreview = "prompt",
                )
            },
        )

        val state = capability.buildInitialState(
            input = QaCapabilityInput(
                question = "解释链路",
                qaContext = GraphQaContext(),
                settings = LinkGraphSettingsState(timeoutSeconds = 3_600),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { testSnapshot().toToolGraphSnapshot() },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(3_600, state.budget.maxRuntimeSeconds)
        assertEquals(10, state.budget.maxSteps)
    }

    fun testPreservesFallbackWarningsWhenQaExecutorFallsBack() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "已回退到本地规则。",
                    promptPreview = "prompt",
                    warnings = listOf("远程 LLM 问答失败，已回退到本地规则。"),
                )
            },
        )

        val result = capability.executeQa(
            input = QaCapabilityInput(
                question = "请围绕当前链路进行问答",
                qaContext = GraphQaContext(),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { null },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertTrue(result.warnings.single().contains("已回退"))
    }

    fun testConfiguredRuntimeTimeoutFlowsToQaExecutorSettings() {
        var capturedTimeoutSeconds: Int? = null
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload",
                    type = NodeType.METHOD,
                    title = "UploadService.upload",
                ),
            ),
        )
        val coordinator = AgentRunCoordinator()
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                capturedTimeoutSeconds = input.settings.sanitized().timeoutSeconds
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "ok",
                    promptPreview = "prompt",
                )
            },
        )

        coordinator.run(
            capability = capability,
            input = QaCapabilityInput(
                question = "解释上传链路",
                qaContext = GraphQaContext(
                    factGraph = graph,
                    editableGraph = graph,
                    selectedNodeIds = listOf("method:upload"),
                ),
                settings = LinkGraphSettingsState(
                    llmEnabled = true,
                    provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                    endpoint = "https://example.com",
                    apiKey = "test-key",
                    model = "gpt-test",
                    timeoutSeconds = 300,
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { testSnapshot(workingGraph = graph, selectedNodeId = "method:upload").toToolGraphSnapshot() },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertNotNull(capturedTimeoutSeconds)
        assertEquals(300, capturedTimeoutSeconds)
    }

    fun testUsesGraphToolBeforeRunningQaExecutor() {
        var toolInvoked = false
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
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
                qaContext = GraphQaContext(),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:upload-file",
                                    type = NodeType.METHOD,
                                    title = "CommonController.uploadFile",
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
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

    fun testReadsCodeEvidenceBeforeRunningQaExecutorWhenSourceContextIsMissing() {
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
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
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
                qaContext = GraphQaContext(
                    selectedNodeIds = listOf("method:upload-file"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
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
                    ).toToolGraphSnapshot()
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

    fun testReadsCalleeMethodEvidenceWhenSelectedNodeIsInvocationCallsite() {
        val basePath = Path.of(requireNotNull(project.basePath))
        val controllerFile = basePath.resolve("src/main/java/com/example/QaUploadController.java")
        val serviceFile = basePath.resolve("src/main/java/com/example/QaUploadServiceImpl.java")
        Files.createDirectories(controllerFile.parent)
        Files.writeString(
            controllerFile,
            """
            class QaUploadController {
                boolean upload() {
                    return fileUploadService.uploadFile();
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            serviceFile,
            """
            class QaUploadServiceImpl {
                boolean uploadFile() {
                    return sshFileUploadUtil.uploadFileViaSCP();
                }
            }
            """.trimIndent(),
        )
        val targetSignature = "com.example.QaUploadServiceImpl.uploadFile():boolean"
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "invoke:controller-to-upload-service",
                    type = NodeType.FLOW_ACTION,
                    title = "调用 QaUploadServiceImpl.uploadFile",
                    signature = targetSignature,
                    metadata = mapOf(
                        "flow.kind" to "INVOCATION",
                        "flow.ownerMethod" to "com.example.QaUploadController.upload():boolean",
                        "source.filePath" to controllerFile.toString(),
                        "source.startLine" to "2",
                        "source.endLine" to "4",
                    ),
                ),
                GraphNode(
                    id = "method:qa-upload-service-impl-upload-file",
                    type = NodeType.METHOD,
                    title = "QaUploadServiceImpl.uploadFile",
                    signature = targetSignature,
                    metadata = mapOf(
                        "source.filePath" to serviceFile.toString(),
                        "source.startLine" to "1",
                        "source.endLine" to "5",
                    ),
                ),
            ),
        )
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "已读取被调方法证据。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "确认上传调用是否继续进入 SCP sink",
                qaContext = GraphQaContext(
                    editableGraph = graph,
                    selectedNodeIds = listOf("invoke:controller-to-upload-service"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        workingGraph = graph,
                        selectedNodeId = "invoke:controller-to-upload-service",
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("已读取被调方法证据。", result.output?.answer)
        assertEquals("read_symbol", result.finalState.stepRecords[2].toolName)
        val snippet = requireNotNull(capturedSourceContext.singleOrNull())
        assertEquals(serviceFile.toString(), snippet.filePath)
        assertTrue(snippet.snippet?.contains("sshFileUploadUtil.uploadFileViaSCP()") == true)
        assertFalse(snippet.snippet?.contains("fileUploadService.uploadFile()") == true)
    }

    fun testReadsSinkMethodEvidenceWhenSelectedNodeIsSecondHopInvocationCallsite() {
        val basePath = Path.of(requireNotNull(project.basePath))
        val serviceFile = basePath.resolve("src/main/java/com/example/QaUploadServiceImpl.java")
        val utilFile = basePath.resolve("src/main/java/com/example/SshFileUploadUtil.java")
        Files.createDirectories(serviceFile.parent)
        Files.writeString(
            serviceFile,
            """
            class QaUploadServiceImpl {
                boolean uploadFile() {
                    return sshFileUploadUtil.uploadFileViaSCP();
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            utilFile,
            """
            class SshFileUploadUtil {
                boolean uploadFileViaSCP() {
                    session = jsch.getSession(username, host, port);
                    channel.put(inputStream, finalDestPath);
                    return true;
                }
            }
            """.trimIndent(),
        )
        val sinkSignature = "com.example.SshFileUploadUtil.uploadFileViaSCP():boolean"
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "invoke:service-to-scp-util",
                    type = NodeType.FLOW_ACTION,
                    title = "调用 SshFileUploadUtil.uploadFileViaSCP",
                    signature = sinkSignature,
                    metadata = mapOf(
                        "flow.kind" to "INVOCATION",
                        "flow.ownerMethod" to "com.example.QaUploadServiceImpl.uploadFile():boolean",
                        "source.filePath" to serviceFile.toString(),
                        "source.startLine" to "2",
                        "source.endLine" to "4",
                    ),
                ),
                GraphNode(
                    id = "method:ssh-file-upload-util-upload-file-via-scp",
                    type = NodeType.METHOD,
                    title = "SshFileUploadUtil.uploadFileViaSCP",
                    signature = sinkSignature,
                    metadata = mapOf(
                        "source.filePath" to utilFile.toString(),
                        "source.startLine" to "1",
                        "source.endLine" to "8",
                    ),
                ),
            ),
        )
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "已读取 sink 证据。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "确认上传调用是否进入 SCP sink",
                qaContext = GraphQaContext(
                    editableGraph = graph,
                    selectedNodeIds = listOf("invoke:service-to-scp-util"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        workingGraph = graph,
                        selectedNodeId = "invoke:service-to-scp-util",
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("已读取 sink 证据。", result.output?.answer)
        assertEquals("read_symbol", result.finalState.stepRecords[2].toolName)
        val snippet = requireNotNull(capturedSourceContext.singleOrNull())
        assertEquals(utilFile.toString(), snippet.filePath)
        assertTrue(snippet.snippet?.contains("jsch.getSession(username, host, port)") == true)
        assertTrue(snippet.snippet?.contains("channel.put(inputStream, finalDestPath)") == true)
        assertFalse(snippet.snippet?.contains("sshFileUploadUtil.uploadFileViaSCP()") == true)
    }

    fun testReadsSecondHopSinkEvidenceFromSelectedControllerToServiceInvocation() {
        val basePath = Path.of(requireNotNull(project.basePath))
        val controllerFile = basePath.resolve("src/main/java/com/example/QaControllerEntry.java")
        val serviceFile = basePath.resolve("src/main/java/com/example/QaServiceImpl.java")
        val utilFile = basePath.resolve("src/main/java/com/example/QaSshUploadUtil.java")
        Files.createDirectories(controllerFile.parent)
        Files.writeString(
            controllerFile,
            """
            class QaControllerEntry {
                boolean upload() {
                    return fileUploadService.uploadFile();
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            serviceFile,
            """
            class QaServiceImpl {
                boolean uploadFile() {
                    return sshFileUploadUtil.uploadFileViaSCP();
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            utilFile,
            """
            class QaSshUploadUtil {
                boolean uploadFileViaSCP() {
                    session = jsch.getSession(username, host, port);
                    channel.put(inputStream, finalDestPath);
                    return true;
                }
            }
            """.trimIndent(),
        )
        val serviceSignature = "com.example.QaServiceImpl.uploadFile():boolean"
        val sinkSignature = "com.example.QaSshUploadUtil.uploadFileViaSCP():boolean"
        val controllerInvocation = GraphNode(
            id = "invoke:controller-to-service",
            type = NodeType.FLOW_ACTION,
            title = "调用 QaServiceImpl.uploadFile",
            signature = serviceSignature,
            metadata = mapOf(
                "flow.kind" to "INVOCATION",
                "flow.ownerMethod" to "com.example.QaControllerEntry.upload():boolean",
                "source.filePath" to controllerFile.toString(),
                "source.startLine" to "2",
                "source.endLine" to "4",
            ),
        )
        val serviceMethod = GraphNode(
            id = "method:qa-service-impl-upload-file",
            type = NodeType.METHOD,
            title = "QaServiceImpl.uploadFile",
            signature = serviceSignature,
            metadata = mapOf(
                "source.filePath" to serviceFile.toString(),
                "source.startLine" to "1",
                "source.endLine" to "5",
            ),
        )
        val serviceInvocation = GraphNode(
            id = "invoke:service-to-sink",
            type = NodeType.FLOW_ACTION,
            title = "调用 QaSshUploadUtil.uploadFileViaSCP",
            signature = sinkSignature,
            metadata = mapOf(
                "flow.kind" to "INVOCATION",
                "flow.ownerMethod" to serviceSignature,
                "source.filePath" to serviceFile.toString(),
                "source.startLine" to "2",
                "source.endLine" to "4",
            ),
        )
        val sinkMethod = GraphNode(
            id = "method:qa-ssh-upload-util-upload-file-via-scp",
            type = NodeType.METHOD,
            title = "QaSshUploadUtil.uploadFileViaSCP",
            signature = sinkSignature,
            metadata = mapOf(
                "source.filePath" to utilFile.toString(),
                "source.startLine" to "1",
                "source.endLine" to "8",
            ),
        )
        val graph = GraphDocument(
            nodes = listOf(controllerInvocation, serviceMethod, serviceInvocation, sinkMethod),
            edges = listOf(
                GraphEdge(
                    id = "call:controller-to-service",
                    type = EdgeType.CALL,
                    fromNodeId = controllerInvocation.id,
                    toNodeId = serviceMethod.id,
                ),
                GraphEdge(
                    id = "contains:service-to-invocation",
                    type = EdgeType.CONTAINS_FLOW,
                    fromNodeId = serviceMethod.id,
                    toNodeId = serviceInvocation.id,
                ),
                GraphEdge(
                    id = "call:service-to-sink",
                    type = EdgeType.CALL,
                    fromNodeId = serviceInvocation.id,
                    toNodeId = sinkMethod.id,
                ),
            ),
        )
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "已读取二跳 sink 证据。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "确认上传入口是否通向 SCP sink",
                qaContext = GraphQaContext(
                    editableGraph = graph,
                    selectedNodeIds = listOf(controllerInvocation.id),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        workingGraph = graph,
                        selectedNodeId = controllerInvocation.id,
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("已读取二跳 sink 证据。", result.output?.answer)
        assertTrue(capturedSourceContext.any { context ->
            context.filePath == serviceFile.toString() &&
                context.snippet?.contains("sshFileUploadUtil.uploadFileViaSCP()") == true
        })
        assertTrue(capturedSourceContext.any { context ->
            context.filePath == utilFile.toString() &&
                context.snippet?.contains("jsch.getSession(username, host, port)") == true &&
                context.snippet?.contains("channel.put(inputStream, finalDestPath)") == true
        })
        assertFalse(capturedSourceContext.any { context ->
            context.filePath == controllerFile.toString() &&
                context.snippet?.contains("fileUploadService.uploadFile()") == true
        })
    }

    fun testRecordsFailedCodeEvidenceTraceWhenSelectedNodeCannotReadSource() {
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        var capturedEvidenceTrace: List<EvidenceTraceEntry> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                capturedEvidenceTrace = input.qaContext.evidenceTrace
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "已按当前上下文回答。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "这个方法是基于哪个组件实现的？如果替换组件的改动大概是多少？",
                qaContext = GraphQaContext(
                    selectedNodeIds = listOf("invoke:gender-prompt"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "invoke:gender-prompt",
                                    type = NodeType.FLOW_ACTION,
                                    title = "调用 AIServiceImpl.genderPrompt",
                                    signature = "com.example.AIServiceImpl.genderPrompt():java.lang.String",
                                    metadata = mapOf("flow.kind" to "INVOCATION"),
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertTrue(capturedSourceContext.isEmpty())
        val trace = requireNotNull(capturedEvidenceTrace.singleOrNull())
        assertEquals("invoke:gender-prompt", trace.nodeId)
        assertFalse(trace.includedInPrompt)
        assertTrue(trace.reason.contains("未读取到源码"))
        assertTrue(result.output?.evidenceTrace?.any { entry ->
            entry.nodeId == "invoke:gender-prompt" && !entry.includedInPrompt
        } == true)
        assertTrue(result.output?.warnings?.any { warning ->
            warning.contains("没有读取到可送入 prompt")
        } == true)
    }

    fun testDoesNotUsePreloadedSourceWhenRuntimeReadAttemptFails() {
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        var capturedEvidenceTrace: List<EvidenceTraceEntry> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                capturedEvidenceTrace = input.qaContext.evidenceTrace
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "已按当前上下文回答。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "这个方法替换组件的影响范围是什么？",
                qaContext = GraphQaContext(
                    selectedNodeIds = listOf("invoke:gender-prompt"),
                    sourceContext = listOf(
                        SourceSnippetContext(
                            nodeId = "method:stale",
                            filePath = "src/main/java/com/example/Stale.java",
                            startLine = 1,
                            endLine = 3,
                            snippet = "class Stale {}",
                        ),
                    ),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "invoke:gender-prompt",
                                    type = NodeType.FLOW_ACTION,
                                    title = "调用 AIServiceImpl.genderPrompt",
                                    signature = "com.example.AIServiceImpl.genderPrompt():java.lang.String",
                                    metadata = mapOf("flow.kind" to "INVOCATION"),
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertTrue(capturedSourceContext.isEmpty())
        assertTrue(capturedEvidenceTrace.singleOrNull()?.includedInPrompt == false)
        assertTrue(result.output?.sourceContext?.isEmpty() == true)
        assertTrue(result.output?.warnings?.any { warning ->
            warning.contains("没有读取到可送入 prompt")
        } == true)
    }

    fun testContinuesQaWhenCodeReadBudgetIsUnavailable() {
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
            qaExecutor = { input, _, _ ->
                executorInvoked = true
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "按图证据继续问答。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请解释这里的字符串处理逻辑",
                qaContext = GraphQaContext(
                    selectedNodeIds = listOf("method:submit"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
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
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertTrue(executorInvoked)
        assertEquals("按图证据继续问答。", result.output?.answer)
        assertTrue(result.output?.sourceContext?.isEmpty() == true)
        assertNull(result.finalState.failureReason)
    }

    fun testContinuesQaWithPartialEvidenceAfterFileBudgetIsExhausted() {
        var readCount = 0
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(maxFilesRead = 1),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "已使用部分源码证据继续问答。",
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
                            val node = context.snapshot.workspaceGraph.nodes.firstOrNull { it.id == nodeId }
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
                qaContext = GraphQaContext(
                    selectedNodeIds = listOf("method:first", "method:second"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
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
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(1, readCount)
        assertEquals("已使用部分源码证据继续问答。", result.output?.answer)
        assertEquals(1, capturedSourceContext.size)
        assertNull(result.finalState.failureReason)
    }

    fun testContinuesQaWithPartialEvidenceAfterSnippetBudgetIsExhausted() {
        var readCount = 0
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(maxFilesRead = 5, maxSnippets = 1),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "已使用部分片段继续问答。",
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
                                    "node" to context.snapshot.workspaceGraph.nodes.firstOrNull { it.id == nodeId },
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
                qaContext = GraphQaContext(selectedNodeIds = listOf("method:first", "method:second")),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
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
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(1, readCount)
        assertEquals("已使用部分片段继续问答。", result.output?.answer)
        assertEquals(1, capturedSourceContext.size)
        assertNull(result.finalState.failureReason)
    }

    fun testContinuesQaWhenSingleSnippetExceedsLineBudget() {
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(maxSnippetLines = 1),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "源码片段过大，按图证据继续问答。",
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
                                    "node" to context.snapshot.workspaceGraph.nodes.firstOrNull { it.id == "method:only" },
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
                qaContext = GraphQaContext(selectedNodeIds = listOf("method:only")),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
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
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("源码片段过大，按图证据继续问答。", result.output?.answer)
        assertTrue(capturedSourceContext.isEmpty())
        assertTrue(result.output?.warnings?.any { warning ->
            warning.contains("没有读取到可送入 prompt")
        } == true)
        assertNull(result.finalState.failureReason)
    }

    fun testWholeGraphQaSkipsAutomaticResourceEvidenceTargets() {
        var capturedSourceContext: List<SourceSnippetContext>? = null
        var readInvoked = false
        val resourceGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "resource:linkgraph-xml",
                    type = NodeType.RESOURCE,
                    title = "linkgraph.xml",
                    metadata = mapOf(
                        "source.filePath" to "build/idea-sandbox/config/workspace/linkgraph.xml",
                        "source.startLine" to "1",
                        "source.endLine" to "120",
                    ),
                ),
            ),
        )
        val capability = QaCapability(
            defaultBudget = RunBudget(maxSnippetLines = 200),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "按图摘要解释项目结构。",
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
                            return ToolResult(
                                toolName = name,
                                payload = mapOf("selectedNodeIds" to emptyList<String>()),
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
                                    "graph" to resourceGraph,
                                    "selectedNodeIds" to emptyList<String>(),
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
                                    "node" to resourceGraph.nodes.firstOrNull { node -> node.id == nodeId },
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source snippet reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            readInvoked = true
                            return ToolResult(toolName = name, payload = mapOf("snippet" to "<component name=\"LinkGraph\" />"))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_symbol"
                        override val description: String = "unused fake symbol reader"

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
                question = "介绍下这个项目结构",
                qaContext = GraphQaContext(),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(workingGraph = resourceGraph).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("按图摘要解释项目结构。", result.output?.answer)
        assertFalse(readInvoked)
        assertEquals(emptyList<SourceSnippetContext>(), capturedSourceContext)
        assertEquals("skip-code-evidence-read", result.finalState.stepRecords[2].summary)
    }

    fun testPersistsCandidateDraftArtifactsFromQaResult() {
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
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
                qaContext = GraphQaContext(),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:upload-file",
                                    type = NodeType.METHOD,
                                    title = "CommonController.uploadFile",
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("建议形成候选草稿。", result.output?.answer)
        assertTrue(result.finalState.artifactRefs.any { it.type == ArtifactType.CANDIDATE_DRAFT })
        assertTrue(result.artifactSummaries.any { it.artifactType == ArtifactType.CANDIDATE_DRAFT.name })
    }

    fun testPrunesStaleCandidateDraftArtifactsWhenNewQaResultCreatesCandidates() {
        val artifactStore = InMemoryArtifactStore()
        artifactStore.save(
            com.charmnight.linkgraph.llm.artifact.CandidateDraftArtifact(
                artifactId = "candidate-stale-change",
                candidate = CandidateDraftChange(
                    changeId = "stale-change",
                    status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                    title = "旧候选",
                ),
            ),
        )
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "建议形成新候选草稿。",
                    promptPreview = "prompt",
                    candidateChanges = listOf(
                        CandidateDraftChange(
                            changeId = "current-change",
                            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                            title = "新候选",
                        ),
                    ),
                    newCandidateChanges = listOf(
                        CandidateDraftChange(
                            changeId = "current-change",
                            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                            title = "新候选",
                        ),
                    ),
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请给出新的候选修改建议",
                qaContext = GraphQaContext(),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:upload-file",
                                    type = NodeType.METHOD,
                                    title = "CommonController.uploadFile",
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = artifactStore,
            ),
        )

        assertEquals("建议形成新候选草稿。", result.output?.answer)
        assertNull(artifactStore.get("candidate-stale-change"))
        assertNotNull(artifactStore.get("candidate-current-change"))
    }

    fun testPreloadedSourceContextBudgetDoesNotFailQa() {
        var executorInvoked = false
        val capability = QaCapability(
            defaultBudget = RunBudget(maxFilesRead = 0),
            qaExecutor = { input, _, _ ->
                executorInvoked = true
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "预读证据预算不足，仍继续问答。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "请解释预读源码的行为",
                qaContext = GraphQaContext(
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
                    testSnapshot(
                        workingGraph = GraphDocument(
                            nodes = listOf(
                                GraphNode(
                                    id = "method:upload-file",
                                    type = NodeType.METHOD,
                                    title = "CommonController.uploadFile",
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertTrue(executorInvoked)
        assertEquals("预读证据预算不足，仍继续问答。", result.output?.answer)
        assertNull(result.finalState.failureReason)
    }

    fun testRebuildsQaInputFromRuntimeArtifactsAndUsesRuntimeEditableGraph() {
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
        var capturedQaContext: GraphQaContext? = null
        var capturedSession: QaConversationSession? = null
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                capturedQaContext = input.qaContext
                capturedSession = input.session
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
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
                qaContext = GraphQaContext(
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
                session = QaConversationSession(
                    sessionId = "session-1",
                    scopeKey = "scope-1",
                    messages = listOf(
                        QaConversationMessage(
                            messageId = "message-1",
                            role = QaMessageRole.USER,
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
                    testSnapshot(
                        selectedNodeId = "method:upload-file",
                        workingGraph = runtimeGraph,
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("runtime qa", result.output?.answer)
        assertEquals(listOf("method:upload-file"), capturedQaContext?.selectedNodeIds)
        assertEquals(staleGraph.nodes.map { it.id }.toSet(), capturedQaContext?.factGraph?.nodes?.map { it.id }?.toSet())
        assertEquals(runtimeGraph.nodes.map { it.id }.toSet(), capturedQaContext?.editableGraph?.nodes?.map { it.id }?.toSet())
        assertEquals(sourceFile.toString(), capturedQaContext?.sourceContext?.singleOrNull()?.filePath)
        assertTrue(capturedQaContext?.sourceContext?.singleOrNull()?.snippet?.contains("fallback") == true)
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
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
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
                qaContext = GraphQaContext(
                    selectedNodeIds = listOf("method:upload-file"),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
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
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("未读取项目外代码证据。", result.output?.answer)
        assertTrue(capturedSourceContext.isEmpty())
        assertTrue(result.finalState.artifactRefs.none { it.type == ArtifactType.CODE_EVIDENCE })
    }

    fun testReadsRealSourceWhenSelectedNodeIsAFlowchartProjection() {
        val sourceFile = Path.of(requireNotNull(project.basePath))
            .resolve("src/main/java/com/example/QaProjectedScheduledJob.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            package com.example;

            import org.springframework.scheduling.annotation.Scheduled;

            @Component
            class QaProjectedScheduledJob {
                @Scheduled(cron = "0 0 * * * ?")
                void run() {
                    cleanupExpiredOrders();
                }
            }
            """.trimIndent(),
        )
        val projectedNode = GraphNode(
            id = "flow-action:cleanup-projection",
            type = NodeType.FLOW_ACTION,
            title = "cleanupExpiredOrders()",
            metadata = mapOf("flowchart.kind" to "PROCESS"),
        )
        val realNode = GraphNode(
            id = "method:scheduled-cleanup",
            type = NodeType.METHOD,
            title = "QaProjectedScheduledJob.run",
            signature = "com.example.QaProjectedScheduledJob.run():void",
            metadata = mapOf(
                "source.filePath" to sourceFile.toString(),
                "source.startLine" to "7",
                "source.endLine" to "10",
            ),
        )
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        var capturedEvidenceTrace: List<EvidenceTraceEntry> = emptyList()
        val capability = QaCapability(
            defaultBudget = RunBudget(),
            qaExecutor = { input, _, _ ->
                capturedSourceContext = input.qaContext.sourceContext
                capturedEvidenceTrace = input.qaContext.evidenceTrace
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = input.question,
                    answer = "已读取投影节点对应源码。",
                    promptPreview = "prompt",
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = QaCapabilityInput(
                question = "这个方法是如何触发的？",
                qaContext = GraphQaContext(
                    selectedNodeIds = listOf(projectedNode.id),
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        currentSceneId = GraphSceneId.WORKSPACE_FLOWCHART,
                        workspaceGraph = GraphDocument(nodes = listOf(projectedNode)),
                        semanticFactGraph = GraphDocument(nodes = listOf(realNode)),
                        flowchartView = FlowchartViewDocument(
                            visibleGraph = GraphDocument(nodes = listOf(projectedNode)),
                            fullGraph = GraphDocument(nodes = listOf(projectedNode)),
                            anchorNodeId = projectedNode.id,
                            projectionIndex = GraphProjectionIndex(
                                nodeMappings = mapOf(
                                    projectedNode.id to GraphProjectionNodeMapping(
                                        projectedNodeId = projectedNode.id,
                                        mappingKind = GraphProjectionMappingKind.PATH_ALIAS,
                                        canonicalNodeIds = listOf(realNode.id),
                                    ),
                                ),
                            ),
                            summary = FlowchartSummary(nodeCount = 1, branchCount = 0, exceptionPathCount = 0),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals("已读取投影节点对应源码。", result.output?.answer)
        val snippet = requireNotNull(capturedSourceContext.singleOrNull())
        assertEquals(realNode.id, snippet.nodeId)
        assertTrue(snippet.snippet?.contains("@Scheduled") == true)
        assertTrue(snippet.snippet?.contains("import org.springframework.scheduling.annotation.Scheduled;") == true)
        val trace = requireNotNull(capturedEvidenceTrace.singleOrNull())
        assertEquals(projectedNode.id, trace.nodeId)
        assertEquals(realNode.id, trace.resolvedNodeId)
        assertTrue(trace.mappingTrace.any { step -> step.contains("projectionIndex:${projectedNode.id}->${realNode.id}") })
        assertTrue(trace.includedInPrompt)
    }

    private fun fakeTool(name: String): AgentTool =
        object : AgentTool {
            override val name: String = name
            override val description: String = "fake tool"

            override fun invoke(
                input: Map<String, Any?>,
                context: ToolExecutionContext,
            ): ToolResult = ToolResult(toolName = name)
        }
}
