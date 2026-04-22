package com.charmnight.linkgraph.llm

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
import com.charmnight.linkgraph.settings.LlmProviderType
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import kotlin.test.Test
import kotlin.test.assertTrue

class LlmPromptFactoryTest {
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
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
    fun buildsAuditAndDiffPromptsFromDualLayerContext() {
        val factory = LlmPromptFactory()
        val auditPrompt = factory.buildAuditPrompt(
            context = GraphAuditContext(
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
                provider = LlmProviderType.MOCK.name,
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
                provider = LlmProviderType.MOCK.name,
                model = "gpt-4.1-mini",
            ),
        )

        assertTrue(auditPrompt.contains("这段链路是否遗漏了默认兜底逻辑"))
        assertTrue(auditPrompt.contains("当前范围"))
        assertTrue(auditPrompt.contains("OrderService.place"))
        assertTrue(auditPrompt.contains("相关源码片段"))
        assertTrue(auditPrompt.contains("defaultChannel"))
        assertTrue(auditPrompt.contains("你正在做链路图问答"))
        assertTrue(auditPrompt.contains("本轮问答回答"))
        assertTrue(auditPrompt.contains("\"answer\": \"问答回答\""))
        assertTrue(auditPrompt.contains("flowchart.kind=DECISION"))
        assertTrue(auditPrompt.contains("flow.ownerMethod=com.example.OrderService.place(java.lang.String):void"))
        assertTrue("本轮审计回答" !in auditPrompt)
        assertTrue(auditPrompt.contains("你的第一优先级是直接回答“用户问题”"))
        assertTrue(auditPrompt.contains("禁止输出与用户问题无关的通用安全、性能、规范性建议"))
        assertTrue(diffPrompt.contains("这些差异意味着什么"))
        assertTrue(diffPrompt.contains("DefaultChannelFallback"))
        assertTrue(diffPrompt.contains("ONLY_IN_MERMAID"))
    }

    @Test
    fun buildsSceneSpecificPromptPackagesForAuditDiffAndCodeGeneration() {
        val factory = LlmPromptFactory()
        val settings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderType.OPENAI_COMPATIBLE.name,
            model = "gpt-4.1-mini",
        )

        val auditPackage = factory.buildAuditPromptPackage(
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

        assertTrue(auditPackage.systemPrompt.contains("链路问答"))
        assertTrue(auditPackage.systemPrompt.contains("不要绕开问题泛化输出通用问答结论"))
        assertTrue(auditPackage.userPrompt.contains("链路图问答"))
        assertTrue(auditPackage.userPrompt.contains("\"answer\": \"问答回答\""))
        assertTrue("链路审计" !in auditPackage.systemPrompt)
        assertTrue(auditPackage.userPrompt.contains("当前范围边"))
        assertTrue(auditPackage.userPrompt.contains("相关源码片段"))
        assertTrue(auditPackage.userPrompt.contains("defaultChannel"))
        assertTrue(auditPackage.userPrompt.contains("candidateChanges"))
        assertTrue(auditPackage.userPrompt.contains("\"patchIntent\""))
        assertTrue(auditPackage.userPrompt.contains("\"graphPatch\""))
        assertTrue(auditPackage.systemPrompt.contains("必须提供 patchIntent"))
        assertTrue(auditPackage.systemPrompt.contains("INSERT_NEW_DECISION"))
        assertTrue(auditPackage.userPrompt.contains("investigationThreads"))
        assertTrue(auditPackage.userPrompt.contains("\"threadId\""))
        assertTrue(diffPackage.systemPrompt.contains("差异"))
        assertTrue(diffPackage.userPrompt.contains("当前关注差异"))
        assertTrue(diffPackage.userPrompt.contains("draft.claimType"))
        assertTrue(codePackage.systemPrompt.contains("代码生成"))
        assertTrue(codePackage.userPrompt.contains("目标文件"))
    }

    @Test
    fun auditPromptPackageSeparatesFactGraphFromEditableGraphSemantics() {
        val factory = LlmPromptFactory()
        val settings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderType.OPENAI_COMPATIBLE.name,
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

        val auditPackage = factory.buildAuditPromptPackage(
            context = GraphAuditContext(
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

        assertTrue(auditPackage.userPrompt.contains("事实图节点"))
        assertTrue(auditPackage.userPrompt.contains("当前可编辑图节点"))
        assertTrue(auditPackage.userPrompt.contains("当前可编辑图连线"))
        assertTrue(auditPackage.userPrompt.contains("if (delete)"))
        assertTrue(auditPackage.systemPrompt.contains("不要把当前可编辑图误称为事实图"))
        assertTrue(auditPackage.systemPrompt.contains("必须明确是来自“事实图”还是“当前可编辑图”"))
    }

    @Test
    fun generationAndCodePromptPackagesIncludeConfirmedDraftChanges() {
        val factory = LlmPromptFactory()
        val settings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderType.OPENAI_COMPATIBLE.name,
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
        assertTrue(codePackage.systemPrompt.contains("禁止声称未提供源码上下文"))
    }

    @Test
    fun buildsBeautificationPromptPackageFromPresentationAndSourceContext() {
        val factory = LlmPromptFactory()
        val settings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderType.OPENAI_COMPATIBLE.name,
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
}
