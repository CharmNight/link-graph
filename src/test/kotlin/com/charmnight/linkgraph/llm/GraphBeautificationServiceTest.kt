package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LlmProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphBeautificationServiceTest {
    @Test
    fun requestsRemoteBeautificationWhenOpenAiCompatibleProviderIsReady() {
        val service = DefaultGraphBeautificationService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """
                            {
                              "summaryTitle": "远程链路讲解",
                              "summary": "远程 LLM 已根据当前图和源码片段整理讲解。",
                              "sections": [
                                {
                                  "id": "current-method",
                                  "title": "当前方法内部",
                                  "content": "当前方法先读取 subject，再切换新的 principal。"
                                }
                              ],
                              "findings": [
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertEquals("远程链路讲解", result.summaryTitle)
        assertTrue(result.summary.contains("远程 LLM"))
        assertTrue(result.sections.any { it.title == "当前方法内部" && it.content.contains("principal") })
        assertTrue(result.findings.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE &&
                finding.references.any { reference -> reference.nodeId == "flow-action:run-as" }
        })
        assertTrue(result.warnings.any { it.contains("不等于完整源码真值") })
    }

    @Test
    fun fallsBackToLocalBeautificationWhenRemoteBeautificationFails() {
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.summary.contains("ShiroUtils.setSysUser"))
        assertTrue(result.findings.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE &&
                finding.references.any { reference -> reference.nodeId == "flow-action:run-as" }
        })
        assertTrue(result.warnings.any { it.contains("远程 LLM 链路讲解失败") })
    }

    @Test
    fun explainsHowToFixRemoteBeautificationConfigurationBeforeUse() {
        val result = DefaultGraphBeautificationService().beautify(
            context = beautificationContext(),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "",
                apiKey = "",
                model = "",
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.warnings.any { it.contains("远程 LLM 配置未就绪") })
        assertTrue(result.warnings.any { it.contains("本地规则讲解") })
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
        val actionNode = GraphNode(
            id = "flow-action:run-as",
            type = NodeType.FLOW_ACTION,
            title = "subject.runAs(newPrincipalCollection)",
            signature = "subject.runAs(newPrincipalCollection)",
            sourceTag = GraphSourceTag.FACT,
        )
        return GraphBeautificationContext(
            presentationContext = GraphPresentationContext(
                graph = GraphDocument(nodes = listOf(methodNode, actionNode)),
                fullGraph = GraphDocument(nodes = listOf(methodNode, actionNode)),
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
