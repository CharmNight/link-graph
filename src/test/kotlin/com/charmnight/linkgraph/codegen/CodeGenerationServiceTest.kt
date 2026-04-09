package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.LlmGateway
import com.charmnight.linkgraph.llm.LlmRequest
import com.charmnight.linkgraph.llm.LlmResponse
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.LlmPromptFactory
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LlmProviderType
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeGenerationServiceTest {
    @Test
    fun generatesJavaAndSqlDraftsFromGenerationPlan() {
        val result = CodeGenerationService().generateDrafts(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            signature = "com.example.OrderService.place(java.lang.String):void",
                            inputs = listOf("java.lang.String"),
                            outputs = listOf("void"),
                        ),
                        GraphNode(
                            id = "class:order-draft-dto",
                            type = NodeType.CLASS,
                            title = "OrderDraftDto",
                            doc = "订单草稿 DTO。",
                        ),
                        GraphNode(
                            id = "sql:insert-order-draft",
                            type = NodeType.SQL,
                            title = "insert_order_draft",
                            doc = "插入订单草稿记录。",
                        ),
                    ),
                    edges = listOf(
                        GraphEdge(
                            id = "call:method-order-service-place->class-order-draft-dto",
                            type = EdgeType.CALL,
                            fromNodeId = "method:order-service-place",
                            toNodeId = "class:order-draft-dto",
                        ),
                    ),
                ),
                diff = GraphDiff(
                    entries = listOf(
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "class:order-draft-dto",
                            status = DiffStatus.ONLY_IN_MERMAID,
                        ),
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "sql:insert-order-draft",
                            status = DiffStatus.ONLY_IN_MERMAID,
                        ),
                    ),
                ),
            ),
            plan = GenerationPlan(
                source = GenerationPlanSource.MOCK,
                summary = "Create DTO and SQL draft.",
                items = listOf(
                    GenerationPlanItem(
                        id = "gen-class",
                        title = "Create OrderDraftDto",
                        description = "Generate DTO class skeleton.",
                        risk = SyncPreviewRisk.LOW,
                        targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    ),
                    GenerationPlanItem(
                        id = "gen-sql",
                        title = "Create draft SQL",
                        description = "Generate SQL file skeleton.",
                        risk = SyncPreviewRisk.LOW,
                        targetPath = "src/main/resources/sql/order-draft.sql",
                    ),
                ),
            ),
        )

        assertEquals(2, result.drafts.size)
        val javaDraft = result.drafts.single { it.targetPath.endsWith("OrderDraftDto.java") }
        assertEquals("class:order-draft-dto", javaDraft.sourceNodeId)
        assertTrue(javaDraft.content.contains("package com.example;"))
        assertTrue(javaDraft.content.contains("class OrderDraftDto"))
        assertTrue(javaDraft.content.contains("订单草稿 DTO"))

        val sqlDraft = result.drafts.single { it.targetPath.endsWith("order-draft.sql") }
        assertEquals("sql:insert-order-draft", sqlDraft.sourceNodeId)
        assertTrue(sqlDraft.content.contains("TODO"))
        assertTrue(sqlDraft.content.contains("insert_order_draft"))
    }

    @Test
    fun reportsWarningsForUnsupportedNodesInsteadOfThrowing() {
        val result = CodeGenerationService().generateDrafts(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "uncertain:runtime-proxy",
                            type = NodeType.UNCERTAIN_LINK,
                            title = "RuntimeProxy",
                        ),
                    ),
                    edges = emptyList(),
                ),
                diff = GraphDiff(
                    entries = listOf(
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "uncertain:runtime-proxy",
                            status = DiffStatus.ONLY_IN_MERMAID,
                        ),
                    ),
                ),
            ),
            plan = GenerationPlan(
                source = GenerationPlanSource.MOCK,
                summary = "No direct code draft.",
            ),
        )

        assertTrue(result.drafts.isEmpty())
        assertTrue(result.warnings.any { it.contains("UNCERTAIN_LINK") })
    }

    @Test
    fun requestsRemoteCodeDraftsWhenOpenAiCompatibleProviderIsReady() {
        val requests = mutableListOf<LlmRequest>()
        val result = CodeGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    requests += request
                    return LlmResponse(
                        content = """
                            {
                              "summary": "远程代码草稿",
                              "warnings": ["请复核 import 与包路径。"],
                              "drafts": [
                                {
                                  "id": "draft-remote-order-draft",
                                  "sourceNodeId": "class:order-draft-dto",
                                  "title": "OrderDraftDto.java",
                                  "targetPath": "src/main/java/com/example/OrderDraftDto.java",
                                  "content": "package com.example;\n\npublic class OrderDraftDto {\n}\n",
                                  "warnings": ["保留手工补充字段。"]
                                }
                              ]
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        ).generateDrafts(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "class:order-draft-dto",
                            type = NodeType.CLASS,
                            title = "OrderDraftDto",
                            doc = "订单草稿 DTO。",
                        ),
                    ),
                ),
                diff = GraphDiff(
                    entries = listOf(
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "class:order-draft-dto",
                            status = DiffStatus.ONLY_IN_MERMAID,
                        ),
                    ),
                ),
            ),
            plan = GenerationPlan(
                source = GenerationPlanSource.REMOTE,
                summary = "Generate DTO remotely.",
                items = listOf(
                    GenerationPlanItem(
                        id = "gen-class",
                        title = "Create OrderDraftDto",
                        description = "Generate DTO class skeleton.",
                        risk = SyncPreviewRisk.LOW,
                        targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    ),
                ),
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-5.4",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertEquals(1, requests.size)
        assertTrue(requests.single().systemPrompt.contains("代码生成"))
        assertTrue(requests.single().userPrompt.contains("目标文件"))
        assertEquals(1, result.drafts.size)
        assertEquals("src/main/java/com/example/OrderDraftDto.java", result.drafts.single().targetPath)
        assertTrue(result.drafts.single().content.contains("class OrderDraftDto"))
        assertNotNull(result.promptPreview)
        assertTrue(result.warnings.any { it.contains("复核 import") })
    }

    @Test
    fun parsesRemoteCodeDraftsWrappedByProseAndJsonFence() {
        val result = CodeGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """
                            代码草稿如下，请只提取 JSON：
                            ```json
                            {
                              "summary": "远程代码草稿",
                              "warnings": ["请保留手工补充字段。"],
                              "drafts": [
                                {
                                  "id": "draft-remote-order-draft",
                                  "sourceNodeId": "class:order-draft-dto",
                                  "title": "OrderDraftDto.java",
                                  "targetPath": "src/main/java/com/example/OrderDraftDto.java",
                                  "content": "package com.example;\n\npublic class OrderDraftDto {\n}\n",
                                  "warnings": []
                                }
                              ]
                            }
                            ```
                            JSON 结束。
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        ).generateDrafts(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "class:order-draft-dto",
                            type = NodeType.CLASS,
                            title = "OrderDraftDto",
                            doc = "订单草稿 DTO。",
                        ),
                    ),
                ),
                diff = GraphDiff(
                    entries = listOf(
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "class:order-draft-dto",
                            status = DiffStatus.ONLY_IN_MERMAID,
                        ),
                    ),
                ),
            ),
            plan = GenerationPlan(
                source = GenerationPlanSource.REMOTE,
                summary = "Generate DTO remotely.",
                items = listOf(
                    GenerationPlanItem(
                        id = "gen-class",
                        title = "Create OrderDraftDto",
                        description = "Generate DTO class skeleton.",
                        risk = SyncPreviewRisk.LOW,
                        targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    ),
                ),
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-5.4",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertEquals(1, result.drafts.size)
        assertEquals("src/main/java/com/example/OrderDraftDto.java", result.drafts.single().targetPath)
        assertTrue(result.warnings.any { it.contains("手工补充字段") })
    }
}
