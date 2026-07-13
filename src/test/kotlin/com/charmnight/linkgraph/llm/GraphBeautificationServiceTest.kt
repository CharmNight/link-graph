package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphProvenance
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.workbench.StepGranularity
import com.charmnight.linkgraph.workbench.StepKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphBeautificationServiceTest {
    @Test
    fun requestsRemoteStepExplanationWhenOpenAiCompatibleProviderIsReady() {
        val service = DefaultGraphBeautificationService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """
                            {
                              "steps": [
                                {
                                  "stepId": "step-load-subject",
                                  "title": "读取当前 subject",
                                  "description": "先从上下文拿到当前 subject，后续要在它上面切换 principal。",
                                  "followUpQuestions": ["这个 subject 从哪里来的？"],
                                  "evidence": [
                                    {
                                      "id": "direct-subject",
                                      "claim": "当前步骤直接读取了 subject。",
                                      "evidenceLevel": "DIRECT_GRAPH",
                                      "references": [
                                        {
                                          "nodeId": "flow-action:get-subject"
                                        }
                                      ]
                                    }
                                  ],
                                  "downstreamTargets": []
                                },
                                {
                                  "stepId": "step-run-as",
                                  "title": "切换 principal",
                                  "description": "调用 runAs 切换新的 principal collection。",
                                  "followUpQuestions": ["这个 principalCollection 是怎么构造的？"],
                                  "evidence": [
                                    {
                                      "id": "direct-run-as",
                                      "claim": "当前方法直接调用了 subject.runAs(newPrincipalCollection)。",
                                      "evidenceLevel": "DIRECT_SOURCE",
                                      "references": [
                                        {
                                          "nodeId": "flow-action:run-as",
                                          "filePath": "/tmp/ShiroUtils.java",
                                          "startLine": 12,
                                          "endLine": 12
                                        }
                                      ]
                                    }
                                  ],
                                  "downstreamTargets": ["method:build-principal-collection"]
                                }
                              ],
                              "warnings": ["远程讲解仅基于当前图输入，不等于完整源码真值。"]
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        )

        val result = service.beautify(
            context = beautificationContext(),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertEquals(StepGranularity.BUSINESS, result.granularity)
        assertEquals(2, result.steps.size)
        assertEquals("step-run-as", result.steps[1].stepId)
        assertEquals("flow-action:run-as", result.steps[1].primaryNodeId)
        assertEquals("subject.runAs(newPrincipalCollection);", result.steps[1].codeSnippet)
        assertTrue(result.steps[1].description.contains("runAs"))
        assertTrue(result.steps[1].followUpQuestions.any { it.contains("principalCollection") })
        assertTrue(result.steps[1].evidence.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE &&
                finding.references.any { reference -> reference.nodeId == "flow-action:run-as" }
        })
        assertEquals(listOf("method:build-principal-collection"), result.steps[1].downstreamTargets)
        assertTrue(result.warnings.any { it.contains("不等于完整源码真值") })
    }

    @Test
    fun remoteBeautificationStripsSourceSnippetsByDefaultAndWarns() {
        val requests = mutableListOf<LlmRequest>()
        val service = DefaultGraphBeautificationService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    requests += request
                    return LlmResponse(
                        content = """
                            {
                              "steps": [
                                {
                                  "stepId": "step-run-as",
                                  "title": "切换 principal",
                                  "description": "基于图节点解释 runAs 调用。",
                                  "evidence": [
                                    {
                                      "id": "remote-graph",
                                      "claim": "远程只收到图节点证据。",
                                      "evidenceLevel": "DIRECT_GRAPH",
                                      "references": [
                                        { "nodeId": "flow-action:run-as" }
                                      ]
                                    }
                                  ],
                                  "followUpQuestions": [],
                                  "downstreamTargets": []
                                }
                              ],
                              "warnings": []
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        )

        val result = service.beautify(
            context = beautificationContext().copy(
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "flow-action:run-as",
                        filePath = "/tmp/ShiroUtils.java",
                        startLine = 12,
                        endLine = 12,
                        snippet = "String beautificationRemoteSecret = \"beautification-secret\";",
                    ),
                ),
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(1, requests.size)
        assertFalse(requests.single().userPrompt.contains("beautificationRemoteSecret"))
        assertFalse(requests.single().userPrompt.contains("beautification-secret"))
        assertTrue(result.warnings.any { it.contains("源码片段外发未授权") })
    }

    @Test
    fun remoteBeautificationStripsStepSourceContextFromPromptEvidenceByDefaultAndWarns() {
        val requests = mutableListOf<LlmRequest>()
        val service = DefaultGraphBeautificationService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    requests += request
                    return LlmResponse(
                        content = """
                            {
                              "steps": [
                                {
                                  "stepId": "step-run-as",
                                  "title": "切换 principal",
                                  "description": "基于图节点解释 runAs 调用。",
                                  "evidence": [
                                    {
                                      "id": "remote-graph",
                                      "claim": "远程只收到图节点证据。",
                                      "evidenceLevel": "DIRECT_GRAPH",
                                      "references": [
                                        { "nodeId": "flow-action:run-as" }
                                      ]
                                    }
                                  ],
                                  "followUpQuestions": [],
                                  "downstreamTargets": []
                                }
                              ],
                              "warnings": []
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        )
        val baseContext = beautificationContext()

        val result = service.beautify(
            context = baseContext.copy(
                presentationContext = baseContext.presentationContext.copy(
                    anchorNodeId = "flow-action:run-as",
                    selectedNodeIds = listOf("flow-action:run-as"),
                ),
                sourceContext = emptyList(),
                stepSourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "flow-action:run-as",
                        filePath = "/tmp/ShiroUtils.java",
                        startLine = 12,
                        endLine = 12,
                        snippet = "String stepRemoteSecret = \"step-secret\";",
                    ),
                ),
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        val prompt = requests.single().userPrompt
        assertFalse(prompt.contains("stepRemoteSecret"))
        assertFalse(prompt.contains("step-secret"))
        assertTrue(prompt.contains("具备源码证据：false"))
        assertTrue(result.warnings.any { it.contains("源码片段外发未授权") })
        assertEquals("String stepRemoteSecret = \"step-secret\";", result.steps.single().codeSnippet)
    }

    @Test
    fun fallsBackToLocalStepExplanationWhenRemoteBeautificationFails() {
        val service = DefaultGraphBeautificationService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    error("HTTP 503 service unavailable")
                }
            },
        )

        val result = service.beautify(
            context = beautificationContext(),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertEquals(listOf("step-load-subject", "step-run-as", "step-return"), result.steps.map { it.stepId })
        assertTrue(result.steps[1].description.contains("runAs"))
        assertTrue(result.steps[1].evidence.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE &&
                finding.references.any { reference -> reference.nodeId == "flow-action:run-as" }
        })
        assertTrue(result.steps[1].followUpQuestions.isNotEmpty())
        assertTrue(result.warnings.any { it.contains("远程 LLM 链路讲解失败") })
    }

    @Test
    fun hydratesRemoteStepsFromStepSourceContextWhenPromptSourceIsCapped() {
        val service = DefaultGraphBeautificationService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """
                            {
                              "steps": [
                                {
                                  "stepId": "step-run-as",
                                  "title": "切换 principal",
                                  "description": "调用 runAs 切换 principal。",
                                  "evidence": [
                                    {
                                      "id": "remote-graph",
                                      "claim": "远程只返回了图节点证据。",
                                      "evidenceLevel": "DIRECT_GRAPH",
                                      "references": [
                                        { "nodeId": "flow-action:run-as" }
                                      ]
                                    }
                                  ],
                                  "followUpQuestions": [],
                                  "downstreamTargets": []
                                }
                              ],
                              "warnings": []
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        )

        val context = beautificationContext().copy(
            sourceContext = emptyList(),
            stepSourceContext = listOf(
                SourceSnippetContext(
                    nodeId = "flow-action:run-as",
                    filePath = "/tmp/ShiroUtils.java",
                    startLine = 12,
                    endLine = 12,
                    snippet = "subject.runAs(newPrincipalCollection);",
                ),
            ),
        )

        val result = service.beautify(
            context = context,
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        val step = result.steps.single()
        assertEquals("subject.runAs(newPrincipalCollection);", step.codeSnippet)
        assertTrue(step.evidence.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE &&
                finding.references.any { reference -> reference.filePath == "/tmp/ShiroUtils.java" }
        })
        assertTrue(step.evidence.any { finding -> finding.id == "remote-graph" })
    }

    @Test
    fun remoteBeautificationPreservesStructuredStepKind() {
        val service = DefaultGraphBeautificationService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """
                            {
                              "steps": [
                                {
                                  "stepId": "class-overview-quota-manager",
                                  "title": "结构概览：ClientRequestQuotaManager",
                                  "kind": "STRUCTURE_OVERVIEW",
                                  "description": "说明类职责、字段依赖和协作者。",
                                  "evidence": [
                                    {
                                      "id": "class-node",
                                      "claim": "当前节点是类图中的类节点。",
                                      "evidenceLevel": "DIRECT_GRAPH",
                                      "references": [
                                        { "nodeId": "class:quota-manager" }
                                      ]
                                    }
                                  ],
                                  "followUpQuestions": ["继续下钻构造参数？"],
                                  "downstreamTargets": []
                                }
                              ],
                              "warnings": []
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        )
        val classNode = GraphNode(
            id = "class:quota-manager",
            type = NodeType.CLASS,
            title = "ClientRequestQuotaManager",
            provenance = GraphProvenance.CODE_ANALYSIS,
        )

        val result = service.beautify(
            context = GraphBeautificationContext(
                presentationContext = GraphPresentationContext(
                    graph = GraphDocument(nodes = listOf(classNode)),
                    fullGraph = GraphDocument(nodes = listOf(classNode)),
                    anchorNodeId = classNode.id,
                ),
                userGoal = "请介绍类图节点“ClientRequestQuotaManager”",
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(StepKind.STRUCTURE_OVERVIEW, result.steps.single().kind)
    }

    @Test
    fun followUpQuestionChangesLocalBeautificationResultInsteadOfBeingIgnored() {
        val result = DefaultGraphBeautificationService().beautify(
            context = beautificationContext().copy(
                followUp = GraphBeautificationFollowUpContext(
                    stepId = "step-run-as",
                    stepTitle = "切换 principal",
                    question = "这里失败时会如何处理？",
                ),
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "",
                apiKey = "",
                model = "",
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertEquals(listOf("step-run-as"), result.steps.map { it.stepId })
        assertTrue(result.steps[0].description.contains("这里失败时会如何处理"))
        assertTrue(result.promptPreview.contains("用户追问：<user_input>这里失败时会如何处理？</user_input>"))
    }

    @Test
    fun explainsHowToFixRemoteStepExplanationConfigurationBeforeUse() {
        val result = DefaultGraphBeautificationService().beautify(
            context = beautificationContext(),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "",
                apiKey = "",
                model = "",
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertTrue(result.warnings.any { it.contains("远程 LLM 配置未就绪") })
        assertTrue(result.warnings.any { it.contains("本地规则讲解") })
        assertTrue(result.steps.isNotEmpty())
    }

    @Test
    fun localBeautificationForPackageAnchorFallsBackToStructureOverviewWithoutCalleeQuestions() {
        val service = PlaceholderGraphBeautificationService()
        val packageNode = GraphNode(
            id = "jvm:package:kafka-cluster",
            type = NodeType.PACKAGE,
            title = "kafka.cluster",
            metadata = mapOf(
                "architecture.node.kind" to "PACKAGE",
                "indexed.memberClassCount" to "19",
            ),
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val componentNode = GraphNode(
            id = "arch:component:kafka-server",
            type = NodeType.COMPONENT,
            title = "kafka.server",
            metadata = mapOf("architecture.node.kind" to "COMPONENT"),
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val context = GraphBeautificationContext(
            presentationContext = GraphPresentationContext(
                graph = GraphDocument(nodes = listOf(packageNode, componentNode)),
                fullGraph = GraphDocument(nodes = listOf(packageNode, componentNode)),
                anchorNodeId = packageNode.id,
            ),
            userGoal = "讲解当前项目结构视图",
        )

        val result = service.beautify(context, LinkGraphSettingsState())

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertTrue(result.steps.isNotEmpty())
        assertTrue(result.steps.all { step -> step.primaryNodeId in setOf(packageNode.id, componentNode.id) })
        val renderedText = (result.steps.flatMap { step ->
            listOf(step.title, step.description) + step.followUpQuestions + step.evidence.map { it.claim }
        } + result.warnings).joinToString("\n")
        assertTrue(renderedText.contains("结构概览") || renderedText.contains("结构"))
        assertFalse(renderedText.contains("被调方法"))
        assertFalse(renderedText.contains("当前方法"))
    }

    @Test
    fun remoteBeautificationHandlesPackageAnchorEvidenceGate() {
        var remoteCalled = false
        val service = DefaultGraphBeautificationService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    remoteCalled = true
                    return LlmResponse(
                        content = """
                            {
                              "steps": [
                                {
                                  "stepId": "structure-jvm-package-kafka-cluster",
                                  "title": "结构概览：kafka.cluster",
                                  "description": "远程基于类图结构说明 kafka.cluster 的包职责和相邻关系，不生成方法调用链。",
                                  "evidence": [
                                    {
                                      "id": "package-node",
                                      "claim": "当前锚点是包结构节点。",
                                      "evidenceLevel": "DIRECT_GRAPH",
                                      "references": [
                                        { "nodeId": "jvm:package:kafka-cluster" }
                                      ]
                                    }
                                  ],
                                  "followUpQuestions": ["下一步应该下钻到哪些类？"],
                                  "downstreamTargets": []
                                }
                              ],
                              "warnings": []
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        )
        val packageNode = GraphNode(
            id = "jvm:package:kafka-cluster",
            type = NodeType.PACKAGE,
            title = "kafka.cluster",
            metadata = mapOf("indexed.memberClassCount" to "19"),
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val result = service.beautify(
            context = GraphBeautificationContext(
                presentationContext = GraphPresentationContext(
                    graph = GraphDocument(nodes = listOf(packageNode)),
                    fullGraph = GraphDocument(nodes = listOf(packageNode)),
                    anchorNodeId = packageNode.id,
                ),
                userGoal = "讲解当前项目结构视图",
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertTrue(remoteCalled)
        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.steps.any { step -> step.description.contains("远程基于类图结构") })
    }

    private fun beautificationContext(): GraphBeautificationContext {
        val methodSignature = "com.ruoyi.common.utils.ShiroUtils.setSysUser(com.example.SysUser):void"
        val methodNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, methodSignature),
            type = NodeType.METHOD,
            title = "ShiroUtils.setSysUser",
            signature = methodSignature,
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val getSubjectNode = GraphNode(
            id = "flow-action:get-subject",
            type = NodeType.FLOW_ACTION,
            title = "SecurityUtils.getSubject()",
            signature = "SecurityUtils.getSubject()",
            metadata = mapOf(
                "workbench.businessStepId" to "step-load-subject",
                "workbench.businessStepTitle" to "读取当前 subject",
                "source.startLine" to "11",
            ),
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val actionNode = GraphNode(
            id = "flow-action:run-as",
            type = NodeType.FLOW_ACTION,
            title = "subject.runAs(newPrincipalCollection)",
            signature = "subject.runAs(newPrincipalCollection)",
            metadata = mapOf(
                "workbench.businessStepId" to "step-run-as",
                "workbench.businessStepTitle" to "切换 principal",
                "source.startLine" to "12",
            ),
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val returnNode = GraphNode(
            id = "flow-terminal:return",
            type = NodeType.TERMINAL,
            title = "return",
            metadata = mapOf(
                "workbench.businessStepId" to "step-return",
                "workbench.businessStepTitle" to "返回调用结果",
                "source.startLine" to "13",
            ),
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        return GraphBeautificationContext(
            presentationContext = GraphPresentationContext(
                graph = GraphDocument(nodes = listOf(methodNode, getSubjectNode, actionNode, returnNode)),
                fullGraph = GraphDocument(nodes = listOf(methodNode, getSubjectNode, actionNode, returnNode)),
                anchorNodeId = methodNode.id,
            ),
            sourceContext = listOf(
                SourceSnippetContext(
                    nodeId = actionNode.id,
                    filePath = "/tmp/ShiroUtils.java",
                    startLine = 12,
                    endLine = 12,
                    snippet = "subject.runAs(newPrincipalCollection);",
                ),
            ),
            userGoal = "解释当前方法链路",
            preferredStyle = "审阅版",
            explanationFocus = "先讲当前方法内部",
        )
    }
}
