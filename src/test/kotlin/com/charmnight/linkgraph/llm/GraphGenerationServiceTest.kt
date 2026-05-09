package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import kotlin.test.assertNotNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphGenerationServiceTest {
    @Test
    fun returnsDisabledPlanWhenLlmIsOff() {
        val service = GraphGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = FakeLlmGateway(),
        )

        val plan = service.generatePlan(
            context = sampleContext(),
            settings = LinkGraphSettingsState(llmEnabled = false),
        )

        assertEquals(GenerationPlanSource.DISABLED, plan.source)
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.warnings.any { it.contains("LLM", ignoreCase = true) })
    }

    @Test
    fun buildsDeterministicPlanForMockProvider() {
        val service = GraphGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = FakeLlmGateway(),
        )

        val plan = service.generatePlan(
            context = sampleContext(),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
            ),
        )

        assertEquals(GenerationPlanSource.LOCAL_RULE, plan.source)
        assertTrue(plan.summary.contains("Create OrderDraft"))
        assertTrue(plan.items.any { it.title.contains("Create OrderDraft") })
        assertTrue(plan.promptPreview.contains("OrderService.submit"))
    }

    @Test
    fun parsesRemoteGatewayPlan() {
        val service = GraphGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = FakeLlmGateway(
                content = """
                    {
                      "summary": "Remote plan",
                      "items": [
                        {
                          "id": "remote-1",
                          "title": "Generate DTO",
                          "description": "Create OrderDraft.java",
                          "risk": "LOW",
                          "targetPath": "src/main/java/com/example/OrderDraft.java"
                        }
                      ],
                      "warnings": ["Check mapper binding."]
                    }
                """.trimIndent(),
            ),
        )

        val plan = service.generatePlan(
            context = sampleContext(),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-4.1-mini",
            ),
        )

        assertEquals(GenerationPlanSource.REMOTE, plan.source)
        assertEquals("Remote plan", plan.summary)
        assertEquals("Generate DTO", plan.items.single().title)
        assertEquals("src/main/java/com/example/OrderDraft.java", plan.items.single().targetPath)
        assertTrue(plan.warnings.contains("Check mapper binding."))
    }

    @Test
    fun retriesOnceWhenRemotePlanResponseIsNotStructuredJson() {
        val requests = mutableListOf<LlmRequest>()
        val service = GraphGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = object : LlmGateway {
                private var callCount = 0

                override fun generate(request: LlmRequest): LlmResponse {
                    requests += request
                    callCount += 1
                    return if (callCount == 1) {
                        LlmResponse(
                            content = "我建议先生成 DTO，再补 mapper。",
                            model = request.model,
                        )
                    } else {
                        LlmResponse(
                            content = """
                                {
                                  "summary": "Remote plan",
                                  "items": [
                                    {
                                      "id": "remote-1",
                                      "title": "Generate DTO",
                                      "description": "Create OrderDraft.java",
                                      "risk": "LOW",
                                      "targetPath": "src/main/java/com/example/OrderDraft.java"
                                    }
                                  ],
                                  "warnings": []
                                }
                            """.trimIndent(),
                            model = request.model,
                        )
                    }
                }
            },
        )

        val plan = service.generatePlan(
            context = sampleContext(),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-4.1-mini",
            ),
        )

        assertEquals(2, requests.size)
        assertEquals(GenerationPlanSource.REMOTE, plan.source)
        assertEquals("Generate DTO", plan.items.single().title)
        assertTrue(plan.warnings.any { it.contains("自动修复") || it.contains("重试") })
        assertTrue(requests.last().systemPrompt.contains("JSON 修复"))
    }

    @Test
    fun explainsHowToFixRemoteGenerationConfigurationBeforeUse() {
        val service = GraphGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = FakeLlmGateway(),
        )

        val plan = service.generatePlan(
            context = sampleContext(),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "",
                apiKey = "",
                model = "",
            ),
        )

        assertEquals(GenerationPlanSource.LOCAL_RULE, plan.source)
        assertTrue(plan.warnings.any { it.contains("请求地址") })
        assertTrue(plan.warnings.any { it.contains("API 密钥") })
        assertTrue(plan.warnings.any { it.contains("链路图设置") })
        assertTrue(plan.warnings.any { it.contains("先验证") })
    }

    @Test
    fun parsesRemotePlanForMiniMaxAnthropicPreset() {
        val requests = mutableListOf<LlmRequest>()
        val service = GraphGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    requests += request
                    return LlmResponse(
                        content = """
                            {
                              "summary": "Remote plan",
                              "items": [
                                {
                                  "id": "remote-1",
                                  "title": "Generate DTO",
                                  "description": "Create OrderDraft.java",
                                  "risk": "LOW",
                                  "targetPath": "src/main/java/com/example/OrderDraft.java"
                                }
                              ],
                              "warnings": ["Check mapper binding."]
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        )

        val plan = service.generatePlan(
            context = sampleContext(),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MINIMAX_ANTHROPIC.id,
                endpoint = "",
                apiKey = "secret-key",
                model = "",
            ),
        )

        assertEquals(1, requests.size)
        assertEquals(LlmWireProtocol.ANTHROPIC_MESSAGES, requests.single().protocol)
        assertEquals("https://api.minimax.io/anthropic", requests.single().endpoint)
        assertEquals("MiniMax-M2.7", requests.single().model)
        assertEquals(GenerationPlanSource.REMOTE, plan.source)
        assertEquals("Remote plan", plan.summary)
    }

    @Test
    fun buildsDeterministicPlanFromConfirmedDraftChangesWhenSyncPreviewIsEmpty() {
        val service = GraphGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = FakeLlmGateway(),
        )

        val plan = service.generatePlan(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "com.example.CommonController.fileDownload(java.lang.String):void",
                            metadata = mapOf(
                                "source.filePath" to "src/main/java/com/example/CommonController.java",
                                "source.startLine" to "42",
                                "source.endLine" to "88",
                            ),
                        ),
                    ),
                ),
                confirmedChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-file-download",
                        kind = DraftEntryKind.CHANGE,
                        sourceChangeId = "change-file-download",
                        title = "修改 fileDownload 的路径判定",
                        targetNodeIds = listOf("method:file-download"),
                        beforeState = "直接使用 baseUrl 拼接下载路径。",
                        afterState = "当 /usr 开头时改写到 /tmp；当 C:/ 开头时直接报错；其他路径保持原逻辑。",
                        reason = "统一处理 Linux 临时目录并阻止 Windows 路径。",
                        impactSummary = "影响下载路径解析。",
                        editScopes = listOf(
                            EditScope(
                                scopeId = "scope-file-download",
                                targetNodeId = "method:file-download",
                                filePath = "src/main/java/com/example/CommonController.java",
                                language = "JAVA",
                                symbolKind = "METHOD",
                                symbolSignature = "com.example.CommonController.fileDownload(java.lang.String):void",
                                startLine = 42,
                                endLine = 88,
                                allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                                supportingFindingIds = listOf("finding-file-download"),
                            ),
                        ),
                    ),
                ),
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
            ),
        )

        assertEquals(GenerationPlanSource.LOCAL_RULE, plan.source)
        assertTrue(plan.summary.contains("fileDownload"))
        assertTrue(plan.items.any { it.title.contains("fileDownload") })
        assertEquals(
            "src/main/java/com/example/CommonController.java",
            plan.items.single().targetPath,
        )
    }

    private fun sampleContext(): GenerationContext {
        return GenerationContext(
            graph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderService.submit",
                        signature = "com.example.OrderService.submit(java.lang.String):com.example.OrderResult",
                        inputs = listOf("java.lang.String"),
                        outputs = listOf("com.example.OrderResult"),
                    ),
                ),
            ),
            mermaidIssues = listOf(
                MermaidIssue(
                    category = MermaidIssue.Category.SEMANTIC,
                    code = "missing-method-signature",
                    message = "METHOD node 'draft:create-order' is missing signature metadata.",
                ),
            ),
            diff = GraphDiff(
                entries = listOf(
                    GraphDiffEntry(
                        elementKind = GraphDiffElementKind.NODE,
                        elementId = "class:order-draft",
                        status = DiffStatus.ONLY_IN_MERMAID,
                    ),
                ),
            ),
            syncPreviewItems = listOf(
                SyncPreviewItem(
                    id = "create-order-draft",
                    title = "Create OrderDraft",
                    description = "Generate DTO class from Mermaid design.",
                    risk = SyncPreviewRisk.LOW,
                ),
            ),
        )
    }
}

private class FakeLlmGateway(
    private val content: String = "",
) : LlmGateway {
    override fun generate(request: LlmRequest): LlmResponse {
        return LlmResponse(
            content = content,
            model = request.model,
        )
    }
}
