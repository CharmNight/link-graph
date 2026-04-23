package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.LlmProviderPresets
import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.LlmGateway
import com.charmnight.linkgraph.llm.LlmRequest
import com.charmnight.linkgraph.llm.LlmResponse
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.LlmPromptFactory
import com.charmnight.linkgraph.llm.SourceSnippetContext
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
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertNotNull(javaDraft.content)
        assertTrue(javaDraft.content!!.contains("package com.example;"))
        assertTrue(javaDraft.content!!.contains("class OrderDraftDto"))
        assertTrue(javaDraft.content!!.contains("订单草稿 DTO"))

        val sqlDraft = result.drafts.single { it.targetPath.endsWith("order-draft.sql") }
        assertEquals("sql:insert-order-draft", sqlDraft.sourceNodeId)
        assertNotNull(sqlDraft.content)
        assertTrue(sqlDraft.content!!.contains("TODO"))
        assertTrue(sqlDraft.content!!.contains("insert_order_draft"))
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
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "class:order-draft-dto",
                        filePath = "src/main/java/com/example/OrderDraftDto.java",
                        startLine = 1,
                        endLine = 3,
                        snippet = "package com.example;\n\npublic class OrderDraftDto {\n}",
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
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-5.4",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertEquals(1, requests.size)
        assertTrue(requests.single().systemPrompt.contains("代码生成"))
        assertTrue(requests.single().userPrompt.contains("目标文件"))
        assertTrue(requests.single().userPrompt.contains("相关源码片段"))
        assertTrue(requests.single().userPrompt.contains("public class OrderDraftDto"))
        assertEquals(1, result.drafts.size)
        assertEquals("src/main/java/com/example/OrderDraftDto.java", result.drafts.single().targetPath)
        assertNotNull(result.drafts.single().content)
        assertTrue(result.drafts.single().content!!.contains("class OrderDraftDto"))
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
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
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

    @Test
    fun fallsBackToLocalDraftsWhenRemoteReturnsEmptyDraftList() {
        val result = CodeGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """
                            {
                              "summary": "远程代码草稿",
                              "warnings": ["远端提示需要人工确认。"],
                              "drafts": []
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
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-5.4",
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertEquals(1, result.drafts.size)
        assertEquals("src/main/java/com/example/OrderDraftDto.java", result.drafts.single().targetPath)
        assertTrue(result.warnings.any { it.contains("未返回任何可用代码草稿") })
        assertTrue(result.warnings.any { it.contains("远端提示需要人工确认") })
    }

    @Test
    fun fallsBackToLocalDraftsWhenRemoteDraftPayloadIsMalformed() {
        val result = CodeGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """
                            {
                              "summary": "远程代码草稿",
                              "warnings": [],
                              "drafts": [
                                {
                                  "id": "draft-remote-order-draft",
                                  "sourceNodeId": "class:order-draft-dto",
                                  "title": "OrderDraftDto.java",
                                  "targetPath": "src/main/java/com/example/OrderDraftDto.java"
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
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-5.4",
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertEquals(1, result.drafts.size)
        assertEquals("src/main/java/com/example/OrderDraftDto.java", result.drafts.single().targetPath)
        assertTrue(result.warnings.any { it.contains("远程 LLM 代码生成失败") })
        assertTrue(result.warnings.any { it.contains("结构化校验") || it.contains("模型输出格式") })
        assertNotNull(result.diagnosticDetail)
        assertTrue(result.diagnosticDetail!!.contains("首次解析错误"))
        assertTrue(result.diagnosticDetail!!.contains("重试解析错误"))
        assertTrue(result.diagnosticDetail!!.contains("must provide content or editOperations"))
        assertTrue(result.emptyResultDetailMessage()!!.contains("首次返回片段"))
    }

    @Test
    fun summarizesStructuredJsonRepairFailureWithoutDumpingRawModelPayloadIntoWarnings() {
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
                              "warnings": [],
                              "drafts": [
                                {
                                  "id": "draft-remote-common-controller",
                                  "sourceNodeId": "method:file-download",
                                  "title": "CommonController.java",
                                  "targetPath": "src/main/java/com/example/CommonController.java",
                                  "editOperations": [
                                    {
                                      "operationId": "op-replace-file-download",
                                      "filePath": "src/main/java/com/example/CommonController.java",
                                      "scopeId": "scope-file-download",
                                      "kind": "REPLACE_METHOD_BLOCK"
                                    }
                                  ],
                                  "warnings": []
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
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                        ),
                    ),
                ),
                confirmedChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-file-download",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改 fileDownload 的路径判定",
                        targetNodeIds = listOf("method:file-download"),
                        editScopes = listOf(
                            EditScope(
                                scopeId = "scope-file-download",
                                targetNodeId = "method:file-download",
                                filePath = "src/main/java/com/example/CommonController.java",
                                language = "JAVA",
                                symbolKind = "METHOD",
                                symbolSignature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                                startLine = 12,
                                endLine = 24,
                                allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                            ),
                        ),
                    ),
                ),
            ),
            plan = GenerationPlan(
                source = GenerationPlanSource.REMOTE,
                summary = "Modify CommonController.java",
                items = listOf(
                    GenerationPlanItem(
                        id = "plan-file-download",
                        title = "修改 fileDownload",
                        description = "只允许改 fileDownload。",
                        risk = SyncPreviewRisk.MEDIUM,
                        targetPath = "src/main/java/com/example/CommonController.java",
                    ),
                ),
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-5.4",
            ),
        )

        assertEquals(2, requests.size)
        assertTrue(requests[1].userPrompt.contains("\"editOperations\""))
        assertTrue(requests[1].userPrompt.contains("\"payload\""))
        assertTrue(requests[1].userPrompt.contains("\"scopeId\""))
        assertTrue(requests[1].userPrompt.contains("payload is required"))
        assertTrue(requests[1].userPrompt.contains("上一次结构化校验失败的具体原因"))
        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.warnings.any { it.contains("返回内容未通过结构化校验") })
        assertTrue(result.warnings.none { it.contains("首次返回片段") })
        assertTrue(result.warnings.none { it.contains("\"summary\"") })
        assertNotNull(result.diagnosticDetail)
        assertTrue(result.diagnosticDetail!!.contains("首次返回片段"))
        assertTrue(result.diagnosticDetail!!.contains("重试返回片段"))
        assertTrue(result.diagnosticDetail!!.contains("payload is required"))
        assertTrue(result.emptyResultDetailMessage()!!.contains("重试返回片段"))
    }

    @Test
    fun warnsWhenLocalRuleModeCannotSafelyRewriteExistingMethodLogic() {
        val result = CodeGenerationService().generateDrafts(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                            metadata = mapOf(
                                "source.filePath" to "src/main/java/com/example/CommonController.java",
                                "source.startLine" to "42",
                                "source.endLine" to "88",
                            ),
                        ),
                    ),
                    edges = emptyList(),
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
                    ),
                ),
            ),
            plan = GenerationPlan(
                source = GenerationPlanSource.MOCK,
                summary = "修改 CommonController.fileDownload 的路径判定。",
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
            ),
        )

        assertTrue(result.drafts.isEmpty())
        assertTrue(result.warnings.any { it.contains("无法安全改写现有方法") })
        assertTrue(result.warnings.any { it.contains("远程 LLM") })
    }

    @Test
    fun parsesRemoteExistingFileDraftAsStructuredEditOperations() {
        val result = CodeGenerationService(
            promptFactory = LlmPromptFactory(),
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """
                            {
                              "summary": "远程代码草稿",
                              "warnings": [],
                              "drafts": [
                                {
                                  "id": "draft-remote-common-controller",
                                  "sourceNodeId": "method:file-download",
                                  "title": "CommonController.java",
                                  "targetPath": "src/main/java/com/example/CommonController.java",
                                  "editOperations": [
                                    {
                                      "operationId": "op-replace-file-download",
                                      "filePath": "src/main/java/com/example/CommonController.java",
                                      "scopeId": "scope-file-download",
                                      "kind": "REPLACE_METHOD_BLOCK",
                                      "payload": "public String fileDownload(String baseUrl) { if (baseUrl.startsWith(\"/usr\")) { return baseUrl.replaceFirst(\"/usr\", \"/tmp\"); } return baseUrl; }",
                                      "warnings": []
                                    }
                                  ],
                                  "warnings": ["请复核 Windows 路径分支。"]
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
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                        ),
                    ),
                ),
                confirmedChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-file-download",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改 fileDownload 的路径判定",
                        targetNodeIds = listOf("method:file-download"),
                        editScopes = listOf(
                            EditScope(
                                scopeId = "scope-file-download",
                                targetNodeId = "method:file-download",
                                filePath = "src/main/java/com/example/CommonController.java",
                                language = "JAVA",
                                symbolKind = "METHOD",
                                symbolSignature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                                startLine = 12,
                                endLine = 24,
                                allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                                supportingFindingIds = listOf("finding-file-download"),
                            ),
                        ),
                    ),
                ),
            ),
            plan = GenerationPlan(
                source = GenerationPlanSource.REMOTE,
                summary = "Modify CommonController.java",
                items = listOf(
                    GenerationPlanItem(
                        id = "plan-file-download",
                        title = "修改 fileDownload",
                        description = "只允许改 fileDownload。",
                        risk = SyncPreviewRisk.MEDIUM,
                        targetPath = "src/main/java/com/example/CommonController.java",
                    ),
                ),
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-5.4",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        val draft = result.drafts.single()
        assertEquals("src/main/java/com/example/CommonController.java", draft.targetPath)
        assertNull(draft.content)
        assertEquals(1, draft.editOperations.size)
        assertEquals("REPLACE_METHOD_BLOCK", draft.editOperations.single().kind.name)
        assertEquals("scope-file-download", draft.editOperations.single().scopeId)
        assertEquals(1, draft.editScopes.size)
        assertEquals("scope-file-download", draft.editScopes.single().scopeId)
        assertTrue(draft.warnings.any { it.contains("Windows 路径分支") })
    }
}
