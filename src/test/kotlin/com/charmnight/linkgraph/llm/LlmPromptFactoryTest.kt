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
                    ),
                ),
                draftGraph = GraphDocument(),
                selectedNodeIds = listOf("method:order-service-place"),
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
                draftGraph = GraphDocument(),
                selectedNodeIds = listOf("method:order-service-place"),
            ),
            question = "请审计当前范围是否遗漏默认兜底逻辑？",
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

        assertTrue(auditPackage.systemPrompt.contains("链路审计"))
        assertTrue(auditPackage.userPrompt.contains("当前范围边"))
        assertTrue(auditPackage.userPrompt.contains("draft.claimType"))
        assertTrue(diffPackage.systemPrompt.contains("差异"))
        assertTrue(diffPackage.userPrompt.contains("当前关注差异"))
        assertTrue(diffPackage.userPrompt.contains("draft.claimType"))
        assertTrue(codePackage.systemPrompt.contains("代码生成"))
        assertTrue(codePackage.userPrompt.contains("目标文件"))
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
            ),
            settings = settings,
        )

        assertTrue(promptPackage.systemPrompt.contains("链路可读性美化助手"))
        assertTrue(promptPackage.userPrompt.contains("ShiroUtils.getSysUser"))
        assertTrue(promptPackage.userPrompt.contains("BeanUtils.copyBeanProp(user, obj)"))
        assertTrue(promptPackage.userPrompt.contains("id=flow-action:copy-bean"))
        assertTrue(promptPackage.userPrompt.contains("当前方法内部折叠节点"))
        assertTrue(promptPackage.userPrompt.contains("跨方法扩展折叠节点"))
        assertTrue(promptPackage.userPrompt.contains("汇报版"))
    }
}
