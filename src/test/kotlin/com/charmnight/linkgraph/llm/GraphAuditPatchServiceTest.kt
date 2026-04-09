package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.GraphUncertainty
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LlmProviderType
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphAuditPatchServiceTest {
    @Test
    fun buildsMockAuditAnswerAndPatchPreview() {
        val result = GraphAuditPatchService().audit(
            context = GraphAuditContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                        GraphNode(
                            id = "uncertain:channel-router",
                            type = NodeType.UNCERTAIN_LINK,
                            title = "ChannelStrategyRouter.resolve",
                            uncertainty = GraphUncertainty(reason = "运行时字符串路由"),
                            sourceTag = GraphSourceTag.UNCERTAIN_FACT,
                        ),
                    ),
                ),
                selectedNodeIds = listOf("uncertain:channel-router"),
            ),
            question = "请审计当前范围：这段链路是否遗漏了默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.MOCK.name,
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.answer.contains("结论："))
        assertTrue(result.answer.contains("关键影响："))
        assertTrue(result.answer.contains("建议动作："))
        assertTrue(result.answer.contains("默认兜底"))
        assertTrue(result.promptPreview.contains("当前范围"))
        assertNotNull(result.patch)
        assertTrue(result.patch!!.operations.any { it.action == GraphPatchAction.ADD_NODE })
        assertTrue(result.patch.operations.any { it.action == GraphPatchAction.ADD_EDGE })
        assertTrue(result.findings.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED
        })
        val addedNode = result.patch.operations.firstNotNullOfOrNull { it.node }
        assertNotNull(addedNode)
        assertEquals(GraphSourceTag.DRAFT_AI, addedNode.sourceTag)
        assertEquals("RISK_HINT", result.patch.operations.first().metadata["draft.claimType"])
        assertEquals("RISK_HINT", addedNode.metadata["draft.claimType"])
    }

    @Test
    fun requestsRemoteAuditWhenOpenAiCompatibleProviderIsReady() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "远程审计建议补一个默认兜底说明节点。",
                          "findings": [
                            {
                              "id": "fallback-missing",
                              "claim": "当前上下文没有直接观察到默认兜底分支。",
                              "evidenceLevel": "NOT_OBSERVED",
                              "references": [
                                {
                                  "nodeId": "method:order-service-place"
                                }
                              ]
                            }
                          ],
                          "warnings": ["远程回答只作为候选建议。"],
                          "patch": {
                            "summary": "远程审计草稿",
                            "operations": [
                              {
                                "id": "remote-audit-add-node",
                                "action": "ADD_NODE",
                                "elementKind": "NODE",
                                "elementId": "doc:remote-fallback",
                                "title": "新增远程建议节点",
                                "summary": "补充默认兜底说明",
                                "metadata": {
                                  "draft.claimType": "RISK_HINT"
                                },
                                "node": {
                                  "id": "doc:remote-fallback",
                                  "type": "DOC_PAGE",
                                  "title": "默认兜底说明",
                                  "doc": "远程 LLM 建议补齐默认兜底逻辑。",
                                  "sourceTag": "DRAFT_AI"
                                }
                              }
                            ],
                            "addedNodeIds": ["doc:remote-fallback"],
                            "removedNodeIds": [],
                            "addedEdgeIds": [],
                            "removedEdgeIds": []
                          }
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请审计整图是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.answer.contains("默认兜底"))
        assertTrue(result.findings.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED &&
                finding.references.any { reference -> reference.nodeId == "method:order-service-place" }
        })
        assertTrue(result.warnings.any { it.contains("候选建议") })
        assertNotNull(result.patch)
        assertEquals("默认兜底说明", result.patch.operations.firstNotNullOfOrNull { it.node }?.title)
        assertEquals("RISK_HINT", result.patch.operations.first().metadata["draft.claimType"])
    }

    @Test
    fun retriesOnceWhenRemoteAuditResponseIsNotStructuredJson() {
        val requests = mutableListOf<LlmRequest>()
        val gateway = object : LlmGateway {
            private var callCount = 0

            override fun generate(request: LlmRequest): LlmResponse {
                requests += request
                callCount += 1
                return if (callCount == 1) {
                    LlmResponse(
                        content = "我建议先补默认兜底节点，再人工确认真实实现。",
                        model = request.model,
                    )
                } else {
                    LlmResponse(
                        content = """
                            {
                              "answer": "远程审计建议补一个默认兜底说明节点。",
                              "warnings": [],
                              "patch": {
                                "summary": "远程审计草稿",
                                "operations": [
                                  {
                                    "id": "remote-audit-add-node",
                                    "action": "ADD_NODE",
                                    "elementKind": "NODE",
                                    "elementId": "doc:remote-fallback",
                                    "title": "新增远程建议节点",
                                    "summary": "补充默认兜底说明",
                                    "node": {
                                      "id": "doc:remote-fallback",
                                      "type": "DOC_PAGE",
                                      "title": "默认兜底说明",
                                      "doc": "远程 LLM 建议补齐默认兜底逻辑。",
                                      "sourceTag": "DRAFT_AI"
                                    }
                                  }
                                ],
                                "addedNodeIds": ["doc:remote-fallback"],
                                "removedNodeIds": [],
                                "addedEdgeIds": [],
                                "removedEdgeIds": []
                              }
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            }
        }

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请审计整图是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(2, requests.size)
        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.answer.contains("默认兜底"))
        assertTrue(result.warnings.any { it.contains("自动修复") || it.contains("重试") })
        assertTrue(requests.last().systemPrompt.contains("JSON 修复"))
    }

    @Test
    fun retriesOnceWhenRemoteAuditRequestTimesOut() {
        var callCount = 0
        val result = GraphAuditPatchService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    callCount += 1
                    if (callCount == 1) {
                        throw HttpTimeoutException("request timed out")
                    }
                    return LlmResponse(
                        content = """
                            {
                              "answer": "远程审计建议补一个默认兜底说明节点。",
                              "warnings": [],
                              "patch": {
                                "summary": "远程审计草稿",
                                "operations": [
                                  {
                                    "id": "remote-audit-add-node",
                                    "action": "ADD_NODE",
                                    "elementKind": "NODE",
                                    "elementId": "doc:remote-fallback",
                                    "title": "新增远程建议节点",
                                    "summary": "补充默认兜底说明",
                                    "node": {
                                      "id": "doc:remote-fallback",
                                      "type": "DOC_PAGE",
                                      "title": "默认兜底说明",
                                      "doc": "远程 LLM 建议补齐默认兜底逻辑。",
                                      "sourceTag": "DRAFT_AI"
                                    }
                                  }
                                ],
                                "addedNodeIds": ["doc:remote-fallback"],
                                "removedNodeIds": [],
                                "addedEdgeIds": [],
                                "removedEdgeIds": []
                              }
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        ).audit(
            context = GraphAuditContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请审计整图是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(2, callCount)
        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.warnings.any { it.contains("超时") && it.contains("重试") })
        assertTrue(result.answer.contains("默认兜底"))
    }

    @Test
    fun reportsRetryAttemptWhenRemoteAuditStillTimesOutAfterRetry() {
        var callCount = 0
        val result = GraphAuditPatchService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    callCount += 1
                    throw HttpTimeoutException("request timed out")
                }
            },
        ).audit(
            context = GraphAuditContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请审计整图是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(2, callCount)
        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.warnings.any { it.contains("重试 1 次后仍失败") })
        assertTrue(result.warnings.none { it.contains("请检查请求地址、鉴权和模型配置") })
    }

    @Test
    fun fallsBackToMockAuditWhenRemoteAuditFails() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                error("Remote LLM request failed with HTTP 503 (model_not_found): No available channel for model gpt-5.4")
            }
        }

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请审计整图是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.warnings.any { it.contains("远程 LLM 审计失败") })
        assertTrue(result.warnings.any { it.contains("model_not_found") })
    }

    @Test
    fun explainsHowToFixRemoteAuditConfigurationBeforeUse() {
        val result = GraphAuditPatchService().audit(
            context = GraphAuditContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请审计整图。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "",
                apiKey = "",
                model = "",
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.warnings.any { it.contains("请求地址") })
        assertTrue(result.warnings.any { it.contains("API 密钥") })
        assertTrue(result.warnings.any { it.contains("链路图设置") })
        assertTrue(result.warnings.any { it.contains("先验证") })
    }

    @Test
    fun usesDraftOnlySelectedNodeAsAuditScopeWhenManualNodeIsNotInFactGraph() {
        val manualNode = GraphNode(
            id = "doc:manual-audit-note",
            type = NodeType.DOC_PAGE,
            title = "人工测试节点",
            doc = "这是只存在于草稿层的手工说明节点。",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
        )
        val factNode = GraphNode(
            id = "method:order-service-place",
            type = NodeType.METHOD,
            title = "OrderService.place",
            sourceTag = GraphSourceTag.FACT,
        )
        val result = GraphAuditPatchService().audit(
            context = GraphAuditContext(
                factGraph = GraphDocument(nodes = listOf(factNode)),
                draftGraph = GraphDocument(
                    nodes = listOf(factNode, manualNode),
                    edges = listOf(
                        GraphEdge(
                            id = "edge:place->manual-note",
                            type = EdgeType.LINKS_DOC,
                            fromNodeId = factNode.id,
                            toNodeId = manualNode.id,
                            sourceTag = GraphSourceTag.DRAFT_MANUAL,
                        ),
                    ),
                ),
                selectedNodeIds = listOf(manualNode.id),
            ),
            question = "请围绕这个手工补充节点继续审计并补全链路。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.MOCK.name,
            ),
        )

        assertNotNull(result.patch)
        val linkedEdge = result.patch!!.operations.firstNotNullOfOrNull { it.edge }
        assertNotNull(linkedEdge)
        assertEquals(manualNode.id, linkedEdge.fromNodeId)
        assertTrue(result.promptPreview.contains(manualNode.title))
        assertTrue(result.answer.contains("当前节点") || result.answer.contains("当前框选范围"))
    }
}
