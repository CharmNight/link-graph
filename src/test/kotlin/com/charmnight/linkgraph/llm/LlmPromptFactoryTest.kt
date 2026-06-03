package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.QaMode
import kotlin.test.Test
import kotlin.test.assertTrue

class LlmPromptFactoryTest {
    @Test
    fun qaPromptPackageTrimsLargeUserPromptToBudget() {
        val hugeSnippet = buildString {
            repeat(600) {
                append("if (value != null) { value = value.trim(); }\n")
            }
        }

        val promptPackage = LlmPromptFactory().buildQaPromptPackage(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:scheduled-task",
                            type = NodeType.METHOD,
                            title = "Task.run",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:scheduled-task"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:scheduled-task",
                        filePath = "src/main/java/com/example/Task.java",
                        startLine = 10,
                        endLine = 14,
                        snippet = hugeSnippet,
                    ),
                ),
            ),
            question = "这个方法是如何触发的？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                model = "gpt-test",
            ),
            requestedMode = QaMode.AUTO,
            effectiveMode = QaMode.ANSWER,
        )

        assertTrue(promptPackage.userPrompt.length <= 12_000)
        assertTrue(promptPackage.userPrompt.contains("目标模型：gpt-test"))
        assertTrue(promptPackage.userPrompt.contains("用户问题：这个方法是如何触发的？"))
        assertTrue(promptPackage.userPrompt.contains("当前范围："))
    }

    @Test
    fun promptPackageAppliesBudgetAcrossSystemAndUserMessages() {
        val hugeSnippet = buildString {
            repeat(600) {
                append("return callRemoteServiceAndNormalizeResult(request);\n")
            }
        }

        val promptPackage = LlmPromptFactory().buildCodeGenerationPromptPackage(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:upload",
                            type = NodeType.METHOD,
                            title = "UploadService.upload",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:upload",
                        filePath = "src/main/java/com/example/UploadService.java",
                        snippet = hugeSnippet,
                    ),
                ),
            ),
            plan = null,
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                model = "gpt-test",
            ),
        )

        assertTrue(promptPackage.systemPrompt.length + promptPackage.userPrompt.length <= 12_000)
        assertTrue(promptPackage.userPrompt.contains("目标模型：gpt-test"))
        assertTrue(promptPackage.userPrompt.contains("相关源码片段"))
    }

    @Test
    fun promptPackageKeepsSchemaInstructionWhenSourceContextIsHuge() {
        val hugeSnippet = buildString {
            repeat(1_200) {
                append("return callRemoteServiceAndNormalizeResult(request);\n")
            }
        }

        val promptPackage = LlmPromptFactory().buildCodeGenerationPromptPackage(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:upload",
                            type = NodeType.METHOD,
                            title = "UploadService.upload",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:upload",
                        filePath = "src/main/java/com/example/UploadService.java",
                        snippet = hugeSnippet,
                    ),
                ),
            ),
            plan = null,
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                model = "gpt-test",
            ),
        )

        assertTrue(promptPackage.systemPrompt.length + promptPackage.userPrompt.length <= 12_000)
        assertTrue(promptPackage.userPrompt.contains("仅返回 JSON，结构如下："))
        assertTrue(promptPackage.userPrompt.contains("\"drafts\""))
        assertTrue(promptPackage.userPrompt.contains("\"editOperations\""))
    }

    @Test
    fun qaPromptPackageKeepsSchemaInstructionWhenSourceContextIsHuge() {
        val hugeSnippet = buildString {
            repeat(1_200) {
                append("return callRemoteServiceAndNormalizeResult(request);\n")
            }
        }

        val promptPackage = LlmPromptFactory().buildQaPromptPackage(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:upload",
                            type = NodeType.METHOD,
                            title = "UploadService.upload",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:upload"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:upload",
                        filePath = "src/main/java/com/example/UploadService.java",
                        snippet = hugeSnippet,
                    ),
                ),
            ),
            question = "解释上传链路",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                model = "gpt-test",
            ),
            requestedMode = QaMode.AUTO,
            effectiveMode = QaMode.ANSWER,
        )

        assertTrue(promptPackage.systemPrompt.length + promptPackage.userPrompt.length <= 12_000)
        assertTrue(promptPackage.userPrompt.contains("仅返回 JSON，结构如下："))
        assertTrue(promptPackage.userPrompt.contains("\"candidateChanges\""))
        assertTrue(promptPackage.userPrompt.contains("\"investigationThreads\""))
    }

    @Test
    fun qaPromptPackageMakesQaModeBoundariesExplicit() {
        val promptPackage = LlmPromptFactory().buildQaPromptPackage(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:scheduled-task",
                            type = NodeType.METHOD,
                            title = "Task.run",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:scheduled-task"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:scheduled-task",
                        filePath = "src/main/java/com/example/Task.java",
                        startLine = 10,
                        endLine = 14,
                        snippet = "@Scheduled(cron = \"0 * * * * ?\")\nvoid run() {}",
                    ),
                ),
            ),
            question = "这个方法是如何触发的？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                model = "gpt-test",
            ),
            requestedMode = QaMode.AUTO,
            effectiveMode = QaMode.ANSWER,
        )

        assertTrue(promptPackage.userPrompt.contains("请求模式：AUTO"))
        assertTrue(promptPackage.userPrompt.contains("实际模式：ANSWER"))
        assertTrue(promptPackage.systemPrompt.contains("ANSWER 模式"))
        assertTrue(promptPackage.systemPrompt.contains("不要生成 candidateChanges"))
        assertTrue(promptPackage.systemPrompt.contains("不要生成 investigationThreads"))
        assertTrue(promptPackage.systemPrompt.contains("图中没有调用边，不等于方法无法触发"))
        assertTrue(promptPackage.userPrompt.contains("@Scheduled"))
    }

    @Test
    fun buildsPromptFromGraphIssuesDiffAndSyncPreview() {
        val prompt = LlmPromptFactory().buildGenerationPrompt(
            snapshot = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:submit-order",
                            type = NodeType.METHOD,
                            title = "OrderService.submit",
                            signature = "com.example.OrderService.submit(java.lang.String):com.example.OrderResult",
                            inputs = listOf("java.lang.String"),
                            outputs = listOf("com.example.OrderResult"),
                            doc = "提交订单。",
                        ),
                    ),
                    edges = listOf(
                        GraphEdge(
                            id = "call:submit-order->draft-order",
                            type = com.charmnight.linkgraph.model.EdgeType.CALL,
                            fromNodeId = "method:submit-order",
                            toNodeId = "class:order-draft",
                        ),
                    ),
                ),
                mermaidIssues = listOf(
                    MermaidIssue(
                        category = MermaidIssue.Category.SEMANTIC,
                        code = "missing-method-signature",
                        message = "METHOD node 'draft:create-order' is missing signature metadata.",
                        line = 3,
                        nodeId = "draft:create-order",
                    ),
                ),
                diff = GraphDiff(
                    entries = listOf(
                        GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "class:order-draft",
                            status = DiffStatus.ONLY_IN_MERMAID,
                            fields = listOf("title", "inputs"),
                            message = "Need to add draft DTO node.",
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
            ),
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                model = "gpt-4.1-mini",
            ),
        )

        assertTrue(prompt.contains("gpt-4.1-mini"))
        assertTrue(prompt.contains("OrderService.submit"))
        assertTrue(prompt.contains("missing-method-signature"))
        assertTrue(prompt.contains("ONLY_IN_MERMAID"))
        assertTrue(prompt.contains("Create OrderDraft"))
        assertTrue(prompt.contains("JSON"))
    }

    @Test
    fun buildsQaAndDiffPromptsFromDualLayerContext() {
        val factory = LlmPromptFactory()
        val qaPrompt = factory.buildQaPrompt(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            signature = "com.example.OrderService.place(java.lang.String):void",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                        GraphNode(
                            id = "scope:order-service-if",
                            type = NodeType.FLOW_SCOPE,
                            title = "if (channel == null)",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "flowchart.kind" to "DECISION",
                                "flow.ownerMethod" to "com.example.OrderService.place(java.lang.String):void",
                            ),
                        ),
                    ),
                ),
                editableGraph = GraphDocument(),
                selectedNodeIds = listOf("method:order-service-place"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:order-service-place",
                        filePath = "src/main/java/com/example/OrderService.java",
                        startLine = 41,
                        endLine = 55,
                        snippet = "if (channel == null) { return defaultChannel(); }",
                    ),
                ),
            ),
            question = "这段链路是否遗漏了默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
                model = "gpt-4.1-mini",
            ),
        )
        val diffPrompt = factory.buildDiffReviewPrompt(
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
                            message = "Mermaid 中存在，但代码中缺失。",
                        ),
                    ),
                ),
            ),
            question = "这些差异意味着什么？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
                model = "gpt-4.1-mini",
            ),
        )

        assertTrue(qaPrompt.contains("这段链路是否遗漏了默认兜底逻辑"))
        assertTrue(qaPrompt.contains("当前范围"))
        assertTrue(qaPrompt.contains("OrderService.place"))
        assertTrue(qaPrompt.contains("相关源码片段"))
        assertTrue(qaPrompt.contains("defaultChannel"))
        assertTrue(qaPrompt.contains("你正在做链路图问答"))
        assertTrue(qaPrompt.contains("本轮问答回答"))
        assertTrue(qaPrompt.contains("\"answer\": \"问答回答\""))
        assertTrue(!qaPrompt.contains("事实图节点"))
        assertTrue(!qaPrompt.contains("当前可编辑图节点"))
        assertTrue(qaPrompt.contains("图上下文边界"))
        assertTrue(qaPrompt.contains("必须按用户问题调用工具查询最小必要上下文"))
        assertTrue("本轮复核回答" !in qaPrompt)
        assertTrue(qaPrompt.contains("你的第一优先级是直接回答“用户问题”"))
        assertTrue(qaPrompt.contains("禁止输出与用户问题无关的通用安全、性能、规范性建议"))
        assertTrue(diffPrompt.contains("这些差异意味着什么"))
        assertTrue(diffPrompt.contains("DefaultChannelFallback"))
        assertTrue(diffPrompt.contains("ONLY_IN_MERMAID"))
    }

    @Test
    fun buildsSceneSpecificPromptPackagesForQaDiffAndCodeGeneration() {
        val factory = LlmPromptFactory()
        val settings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            model = "gpt-4.1-mini",
        )

        val qaPackage = factory.buildQaPromptPackage(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                    edges = listOf(
                        GraphEdge(
                            id = "edge:place->fallback",
                            type = com.charmnight.linkgraph.model.EdgeType.CALL,
                            fromNodeId = "method:order-service-place",
                            toNodeId = "method:order-service-fallback",
                        ),
                    ),
                ),
                editableGraph = GraphDocument(),
                selectedNodeIds = listOf("method:order-service-place"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:order-service-place",
                        filePath = "src/main/java/com/example/OrderService.java",
                        startLine = 41,
                        endLine = 55,
                        snippet = "if (channel == null) { return defaultChannel(); }",
                    ),
                ),
            ),
            question = "请围绕当前范围进行问答，判断是否遗漏默认兜底逻辑？",
            settings = settings,
        )
        val diffPackage = factory.buildDiffReviewPromptPackage(
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
                            message = "Mermaid 中存在，但代码中缺失。",
                        ),
                    ),
                ),
                selectedDiffItemIds = listOf("design:default-fallback"),
            ),
            question = "这些差异意味着什么？",
            settings = settings,
        )
        val codePackage = factory.buildCodeGenerationPromptPackage(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "class:order-draft",
                            type = NodeType.CLASS,
                            title = "OrderDraftDto",
                            sourceTag = GraphSourceTag.DESIGN_BASELINE,
                        ),
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
            ),
            plan = GenerationPlan(
                source = GenerationPlanSource.REMOTE,
                summary = "Generate DTO",
                items = listOf(
                    GenerationPlanItem(
                        id = "gen-order-draft",
                        title = "Generate OrderDraftDto",
                        description = "生成 DTO 类草稿",
                        risk = SyncPreviewRisk.LOW,
                        targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    ),
                ),
            ),
            settings = settings,
        )

        assertTrue(qaPackage.systemPrompt.contains("链路问答"))
        assertTrue(qaPackage.systemPrompt.contains("不要绕开问题泛化输出通用问答结论"))
        assertTrue(qaPackage.userPrompt.contains("链路图问答"))
        assertTrue(qaPackage.userPrompt.contains("\"answer\": \"问答回答\""))
        assertTrue("链路复核" !in qaPackage.systemPrompt)
        assertTrue(qaPackage.userPrompt.contains("当前范围边"))
        assertTrue(qaPackage.userPrompt.contains("相关源码片段"))
        assertTrue(qaPackage.userPrompt.contains("defaultChannel"))
        assertTrue(qaPackage.userPrompt.contains("candidateChanges"))
        assertTrue(qaPackage.userPrompt.contains("\"patchIntent\""))
        assertTrue(qaPackage.userPrompt.contains("\"graphPatch\""))
        assertTrue(qaPackage.systemPrompt.contains("必须提供 patchIntent"))
        assertTrue(qaPackage.systemPrompt.contains("INSERT_NEW_DECISION"))
        assertTrue(qaPackage.userPrompt.contains("investigationThreads"))
        assertTrue(qaPackage.userPrompt.contains("\"threadId\""))
        assertTrue(diffPackage.systemPrompt.contains("差异"))
        assertTrue(diffPackage.userPrompt.contains("当前关注差异"))
        assertTrue(diffPackage.userPrompt.contains("draft.claimType"))
        assertTrue(codePackage.systemPrompt.contains("代码生成"))
        assertTrue(codePackage.userPrompt.contains("目标文件"))
    }

    @Test
    fun qaPromptPackageUsesMinimalQuestionScopedGraphContext() {
        val factory = LlmPromptFactory()
        val settings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            model = "gpt-4.1-mini",
        )
        val factMethod = GraphNode(
            id = "method:file-download",
            type = NodeType.METHOD,
            title = "CommonController.fileDownload",
            sourceTag = GraphSourceTag.FACT,
        )
        val editableDecision = GraphNode(
            id = "scope:file-download-if",
            type = NodeType.FLOW_SCOPE,
            title = "if (delete)",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
            metadata = mapOf("flowchart.kind" to "DECISION"),
        )

        val qaPackage = factory.buildQaPromptPackage(
            context = GraphQaContext(
                factGraph = GraphDocument(nodes = listOf(factMethod)),
                editableGraph = GraphDocument(
                    nodes = listOf(factMethod, editableDecision),
                    edges = listOf(
                        GraphEdge(
                            id = "edge:file-download->if-delete",
                            type = com.charmnight.linkgraph.model.EdgeType.CONTROL_FLOW,
                            fromNodeId = factMethod.id,
                            toNodeId = editableDecision.id,
                            sourceTag = GraphSourceTag.DRAFT_MANUAL,
                        ),
                    ),
                ),
                selectedNodeIds = listOf(factMethod.id),
            ),
            question = "请确认当前删除分支应该落在哪个真实节点上？",
            settings = settings,
        )

        assertTrue(!qaPackage.userPrompt.contains("事实图节点"))
        assertTrue(!qaPackage.userPrompt.contains("当前可编辑图节点"))
        assertTrue(!qaPackage.userPrompt.contains("当前可编辑图连线"))
        assertTrue(qaPackage.userPrompt.contains("当前范围节点"))
        assertTrue(qaPackage.userPrompt.contains("当前范围边"))
        assertTrue(qaPackage.userPrompt.contains("if (delete)"))
        assertTrue(qaPackage.userPrompt.contains("如需整图、邻接节点、架构索引、Review Graph 或源码细节，必须按用户问题调用工具查询最小必要上下文"))
        assertTrue(qaPackage.systemPrompt.contains("不要把当前可编辑图误称为事实图"))
        assertTrue(qaPackage.systemPrompt.contains("必须明确是来自“事实图”还是“当前可编辑图”"))
    }

    @Test
    fun generationAndCodePromptPackagesIncludeConfirmedDraftChanges() {
        val factory = LlmPromptFactory()
        val settings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            model = "gpt-5.4",
        )
        val context = GenerationContext(
            graph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:file-download",
                        type = NodeType.METHOD,
                        title = "CommonController.fileDownload",
                        location = "src/main/java/com/example/CommonController.java:42",
                        signature = "com.example.CommonController.fileDownload(java.lang.String):void",
                        metadata = mapOf(
                            "source.filePath" to "src/main/java/com/example/CommonController.java",
                            "source.startLine" to "42",
                            "source.endLine" to "88",
                        ),
                    ),
                ),
            ),
            sourceContext = listOf(
                SourceSnippetContext(
                    nodeId = "method:file-download",
                    filePath = "src/main/java/com/example/CommonController.java",
                    startLine = 42,
                    endLine = 88,
                    snippet = """
                        public String fileDownload(String baseUrl) {
                            if (baseUrl.startsWith("/usr")) {
                                return baseUrl.replaceFirst("/usr", "/tmp");
                            }
                            return baseUrl;
                        }
                    """.trimIndent(),
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
                    impactSummary = "影响下载文件路径解析。",
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
                            allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK", "REPLACE_METHOD_BODY"),
                            supportingFindingIds = listOf("finding-file-download"),
                        ),
                    ),
                ),
            ),
        )

        val generationPackage = factory.buildGenerationPromptPackage(context, settings)
        val codePackage = factory.buildCodeGenerationPromptPackage(context, plan = null, settings = settings)

        assertTrue(generationPackage.userPrompt.contains("已确认草稿变更"))
        assertTrue(generationPackage.userPrompt.contains("修改 fileDownload 的路径判定"))
        assertTrue(generationPackage.userPrompt.contains("src/main/java/com/example/CommonController.java"))
        assertTrue(generationPackage.userPrompt.contains("claimType"))
        assertTrue(generationPackage.userPrompt.contains("相关源码片段"))
        assertTrue(generationPackage.userPrompt.contains("baseUrl.replaceFirst(\"/usr\", \"/tmp\")"))
        assertTrue(codePackage.userPrompt.contains("已确认草稿变更"))
        assertTrue(codePackage.userPrompt.contains("C:/ 开头时直接报错"))
        assertTrue(codePackage.userPrompt.contains("evidence"))
        assertTrue(codePackage.systemPrompt.contains("保留目标文件中与本次变更无关的现有代码"))
        assertTrue(codePackage.userPrompt.contains("如果目标文件已经明确指向现有源码"))
        assertTrue(codePackage.userPrompt.contains("不要删除未提及的成员"))
        assertTrue(codePackage.userPrompt.contains("scope-file-download"))
        assertTrue(codePackage.userPrompt.contains("symbolSignature=com.example.CommonController.fileDownload(java.lang.String):void"))
        assertTrue(codePackage.userPrompt.contains("allowedChangeKinds=REPLACE_METHOD_BLOCK, REPLACE_METHOD_BODY"))
        assertTrue(codePackage.userPrompt.contains("已确认草稿变更附带的 edit scopes"))
        assertTrue(!codePackage.userPrompt.contains("已授权 edit scopes："))
        assertTrue(!codePackage.userPrompt.contains("planItem="))
        assertTrue(codePackage.userPrompt.contains("相关源码片段"))
        assertTrue(codePackage.userPrompt.contains("baseUrl.replaceFirst(\"/usr\", \"/tmp\")"))
        assertTrue(codePackage.systemPrompt.contains("editOperations[].payload 必须是纯源码片段字符串"))
        assertTrue(codePackage.systemPrompt.contains("禁止把 methodSignature、changeType、existingCodeSnippet、newImplementation 等包装字段或元数据序列化进 payload"))
        assertTrue(codePackage.userPrompt.contains("纯源码片段，不要放 methodSignature/changeType/existingCodeSnippet 等元数据包装"))
        assertTrue(codePackage.systemPrompt.contains("禁止声称未提供源码上下文"))
    }

    @Test
    fun buildsBeautificationPromptPackageFromPresentationAndSourceContext() {
        val factory = LlmPromptFactory()
        val settings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            model = "gpt-4.1-mini",
        )

        val promptPackage = factory.buildBeautificationPromptPackage(
            context = GraphBeautificationContext(
                presentationContext = GraphPresentationContext(
                    graph = GraphDocument(
                        nodes = listOf(
                            GraphNode(
                                id = "method:get-sys-user",
                                type = NodeType.METHOD,
                                title = "ShiroUtils.getSysUser",
                                signature = "com.ruoyi.common.utils.ShiroUtils.getSysUser():com.ruoyi.common.core.domain.entity.SysUser",
                            ),
                            GraphNode(
                                id = "flow-action:copy-bean",
                                type = NodeType.FLOW_ACTION,
                                title = "BeanUtils.copyBeanProp(user, obj)",
                                signature = "BeanUtils.copyBeanProp(user, obj)",
                                metadata = mapOf(
                                    "flow.anchorMethod" to "com.ruoyi.common.utils.ShiroUtils.getSysUser():com.ruoyi.common.core.domain.entity.SysUser",
                                ),
                            ),
                        ),
                    ),
                    anchorNodeId = "method:get-sys-user",
                    hiddenCurrentMethodNodeCount = 1,
                    hiddenCrossMethodNodeCount = 4,
                ),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "flow-action:copy-bean",
                        filePath = "/tmp/ShiroUtils.java",
                        startOffset = 100,
                        endOffset = 132,
                        snippet = "BeanUtils.copyBeanProp(user, obj);",
                    ),
                ),
                userGoal = "把当前方法链路讲清楚",
                preferredStyle = "汇报版",
                explanationFocus = "先解释当前方法内部，再补跨方法扩展",
                followUp = GraphBeautificationFollowUpContext(
                    stepId = "step-copy-bean",
                    stepTitle = "Step 2 复制用户信息",
                    question = "这里复制失败时会怎么处理？",
                ),
            ),
            settings = settings,
        )

        assertTrue(promptPackage.systemPrompt.contains("步骤化链路讲解助手"))
        assertTrue(promptPackage.systemPrompt.contains("如果提供了追问上下文"))
        assertTrue(promptPackage.userPrompt.contains("ShiroUtils.getSysUser"))
        assertTrue(promptPackage.userPrompt.contains("BeanUtils.copyBeanProp(user, obj)"))
        assertTrue(promptPackage.userPrompt.contains("id=flow-action:copy-bean"))
        assertTrue(promptPackage.userPrompt.contains("当前粒度"))
        assertTrue(promptPackage.userPrompt.contains("当前方法内部折叠节点"))
        assertTrue(promptPackage.userPrompt.contains("跨方法扩展折叠节点"))
        assertTrue(promptPackage.userPrompt.contains("汇报版"))
        assertTrue(promptPackage.userPrompt.contains("讲解模式：追问讲解"))
        assertTrue(promptPackage.userPrompt.contains("用户追问：这里复制失败时会怎么处理？"))
        assertTrue(promptPackage.userPrompt.contains("本轮回答必须先直接回答用户追问"))
        assertTrue(promptPackage.userPrompt.contains("如果当前证据不足，必须明确写出“不足以确认”"))
    }

    @Test
    fun beautificationPromptForPackageAnchorCarriesEvidenceGateInsteadOfMethodChainAssumptions() {
        val factory = LlmPromptFactory()
        val settings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            model = "gpt-4.1-mini",
        )

        val promptPackage = factory.buildBeautificationPromptPackage(
            context = GraphBeautificationContext(
                presentationContext = GraphPresentationContext(
                    graph = GraphDocument(
                        nodes = listOf(
                            GraphNode(
                                id = "jvm:package:kafka-cluster",
                                type = NodeType.PACKAGE,
                                title = "kafka.cluster",
                                metadata = mapOf(
                                    "architecture.node.kind" to "PACKAGE",
                                    "indexed.memberClassCount" to "19",
                                ),
                            ),
                        ),
                    ),
                    anchorNodeId = "jvm:package:kafka-cluster",
                ),
                userGoal = "讲解当前视图",
            ),
            settings = settings,
        )

        assertTrue(promptPackage.userPrompt.contains("锚点类型：PACKAGE"))
        assertTrue(promptPackage.userPrompt.contains("允许讲解模式：PACKAGE_OVERVIEW, DRILLDOWN_SUGGESTION"))
        assertTrue(promptPackage.userPrompt.contains("禁止声明：不要把当前锚点称为方法或当前方法"))
        assertTrue(promptPackage.userPrompt.contains("证据缺口：缺少方法级调用边"))
        assertTrue(promptPackage.userPrompt.contains("讲解模式：证据受限讲解"))
        assertTrue(promptPackage.userPrompt.contains("不能输出“定位被调方法”"))
    }

    @Test
    fun qaPromptForPackageSelectionCarriesEvidenceGateBeforeAnsweringMethodQuestions() {
        val factory = LlmPromptFactory()
        val promptPackage = factory.buildQaPromptPackage(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "jvm:package:kafka-cluster",
                            type = NodeType.PACKAGE,
                            title = "kafka.cluster",
                            metadata = mapOf("indexed.memberClassCount" to "19"),
                        ),
                    ),
                ),
                selectedNodeIds = listOf("jvm:package:kafka-cluster"),
            ),
            question = "这个方法的被调方法是什么？",
            settings = LinkGraphSettingsState(),
        )

        assertTrue(promptPackage.userPrompt.contains("图证据边界"))
        assertTrue(promptPackage.userPrompt.contains("锚点类型：PACKAGE"))
        assertTrue(promptPackage.userPrompt.contains("允许讲解模式：PACKAGE_OVERVIEW, DRILLDOWN_SUGGESTION"))
        assertTrue(promptPackage.userPrompt.contains("禁止声明：不要把当前锚点称为方法或当前方法"))
        assertTrue(promptPackage.userPrompt.contains("证据缺口：缺少方法级调用边"))
        assertTrue(promptPackage.systemPrompt.contains("必须遵守图证据边界"))
    }
}
