package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.StepGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
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
                endpoint = "https://localhost:8080/v1",
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
                endpoint = "https://localhost:8080/v1",
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
                endpoint = "https://localhost:8080/v1",
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
        assertTrue(result.promptPreview.contains("用户追问：这里失败时会如何处理？"))
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

    private fun beautificationContext(): GraphBeautificationContext {
        val methodSignature = "com.ruoyi.common.utils.ShiroUtils.setSysUser(com.example.SysUser):void"
        val methodNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, methodSignature),
            type = NodeType.METHOD,
            title = "ShiroUtils.setSysUser",
            signature = methodSignature,
            sourceTag = GraphSourceTag.FACT,
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
            sourceTag = GraphSourceTag.FACT,
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
            sourceTag = GraphSourceTag.FACT,
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
            sourceTag = GraphSourceTag.FACT,
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
