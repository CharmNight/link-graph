package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LlmProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphDiffPatchServiceTest {
    @Test
    fun buildsMockDiffExplanationAndRevisionPatch() {
        val result = GraphDiffPatchService().review(
            context = GraphDiffContext(
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
                designBaseline = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "design:default-fallback",
                            type = NodeType.CLASS,
                            title = "DefaultChannelFallback",
                            doc = "设计里要求补默认兜底处理。",
                            sourceTag = GraphSourceTag.DESIGN_BASELINE,
                        ),
                    ),
                ),
                diff = GraphDiff(
                    entries = listOf(
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "design:default-fallback",
                            status = DiffStatus.ONLY_IN_MERMAID,
                            message = "Mermaid 中存在，但代码中缺失。",
                        ),
                    ),
                ),
            ),
            question = "这些差异意味着什么？请给出修订草稿。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.MOCK.name,
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.answer.contains("结论："))
        assertTrue(result.answer.contains("关键影响："))
        assertTrue(result.answer.contains("建议动作："))
        assertTrue(result.answer.contains("仅设计图有"))
        assertTrue(result.answer.contains("修订"))
        assertNotNull(result.patch)
        assertTrue(result.patch!!.operations.any { it.action == GraphPatchAction.ADD_NODE })
        val addedNode = result.patch.operations.firstNotNullOfOrNull { it.node }
        assertNotNull(addedNode)
        assertEquals("DefaultChannelFallback", addedNode.title)
        assertEquals(GraphSourceTag.DRAFT_AI, addedNode.sourceTag)
    }

    @Test
    fun requestsRemoteDiffReviewWhenOpenAiCompatibleProviderIsReady() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "远程分析认为设计基线中的 DefaultChannelFallback 尚未在代码事实层落地。",
                          "warnings": [],
                          "patch": {
                            "summary": "远程差异修订草稿",
                            "operations": [
                              {
                                "id": "remote-diff-add-node",
                                "action": "ADD_NODE",
                                "elementKind": "NODE",
                                "elementId": "design:default-fallback",
                                "title": "补设计基线节点",
                                "summary": "把设计节点写入草稿层",
                                "node": {
                                  "id": "design:default-fallback",
                                  "type": "CLASS",
                                  "title": "DefaultChannelFallback",
                                  "doc": "远程建议先转成待实现草稿节点。",
                                  "sourceTag": "DRAFT_AI"
                                }
                              }
                            ],
                            "addedNodeIds": ["design:default-fallback"],
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

        val result = GraphDiffPatchService(gateway = gateway).review(
            context = GraphDiffContext(
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
                designBaseline = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "design:default-fallback",
                            type = NodeType.CLASS,
                            title = "DefaultChannelFallback",
                            sourceTag = GraphSourceTag.DESIGN_BASELINE,
                        ),
                    ),
                ),
                diff = GraphDiff(
                    entries = listOf(
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "design:default-fallback",
                            status = DiffStatus.ONLY_IN_MERMAID,
                        ),
                    ),
                ),
            ),
            question = "请解释这些差异并给出修订草稿。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.answer.contains("DefaultChannelFallback"))
        assertEquals("DefaultChannelFallback", result.patch?.operations?.firstNotNullOfOrNull { it.node }?.title)
    }

    @Test
    fun parsesRemoteDiffReviewWrappedByProseAndJsonFence() {
        val result = GraphDiffPatchService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """
                            下面是差异修订结果，请直接消费 JSON。
                            ```json
                            {
                              "answer": "远程分析认为 DefaultChannelFallback 仍未落地。",
                              "warnings": ["当前代码事实图可能存在采集范围不足。"],
                              "patch": {
                                "summary": "远程差异修订草稿",
                                "operations": [
                                  {
                                    "id": "remote-diff-add-node",
                                    "action": "ADD_NODE",
                                    "elementKind": "NODE",
                                    "elementId": "design:default-fallback",
                                    "title": "补设计基线节点",
                                    "summary": "把设计节点写入草稿层",
                                    "node": {
                                      "id": "design:default-fallback",
                                      "type": "CLASS",
                                      "title": "DefaultChannelFallback",
                                      "doc": "远程建议先转成待实现草稿节点。",
                                      "sourceTag": "DRAFT_AI"
                                    }
                                  }
                                ],
                                "addedNodeIds": ["design:default-fallback"],
                                "removedNodeIds": [],
                                "addedEdgeIds": [],
                                "removedEdgeIds": []
                              }
                            }
                            ```
                            以上为本次结果。
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        ).review(
            context = GraphDiffContext(
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
                designBaseline = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "design:default-fallback",
                            type = NodeType.CLASS,
                            title = "DefaultChannelFallback",
                            sourceTag = GraphSourceTag.DESIGN_BASELINE,
                        ),
                    ),
                ),
                diff = GraphDiff(
                    entries = listOf(
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "design:default-fallback",
                            status = DiffStatus.ONLY_IN_MERMAID,
                        ),
                    ),
                ),
            ),
            question = "请解释这些差异并给出修订草稿。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.answer.contains("DefaultChannelFallback"))
        assertTrue(result.warnings.any { it.contains("采集范围不足") })
        assertEquals("DefaultChannelFallback", result.patch?.operations?.firstNotNullOfOrNull { it.node }?.title)
    }

    @Test
    fun explainsHowToFixRemoteDiffConfigurationBeforeUse() {
        val result = GraphDiffPatchService().review(
            context = GraphDiffContext(
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
                designBaseline = GraphDocument(),
                diff = GraphDiff(),
            ),
            question = "请解释差异。",
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
    fun prioritizesSelectedDiffItemsWhenBuildingRevisionPatch() {
        val result = GraphDiffPatchService().review(
            context = GraphDiffContext(
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
                designBaseline = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "design:default-fallback",
                            type = NodeType.CLASS,
                            title = "DefaultChannelFallback",
                            doc = "设计里要求补默认兜底处理。",
                            sourceTag = GraphSourceTag.DESIGN_BASELINE,
                        ),
                        GraphNode(
                            id = "design:audit-note",
                            type = NodeType.DOC_PAGE,
                            title = "AuditNote",
                            doc = "另一个设计节点。",
                            sourceTag = GraphSourceTag.DESIGN_BASELINE,
                        ),
                    ),
                ),
                diff = GraphDiff(
                    entries = listOf(
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "design:audit-note",
                            status = DiffStatus.ONLY_IN_MERMAID,
                            message = "另一个设计节点未落地。",
                        ),
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "design:default-fallback",
                            status = DiffStatus.ONLY_IN_MERMAID,
                            message = "Mermaid 中存在，但代码中缺失。",
                        ),
                    ),
                ),
                selectedDiffItemIds = listOf("design:default-fallback"),
            ),
            question = "请只围绕当前差异焦点给出修订草稿。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.MOCK.name,
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.answer.contains("DefaultChannelFallback"))
        assertEquals("DefaultChannelFallback", result.patch?.operations?.firstNotNullOfOrNull { it.node }?.title)
    }
}
