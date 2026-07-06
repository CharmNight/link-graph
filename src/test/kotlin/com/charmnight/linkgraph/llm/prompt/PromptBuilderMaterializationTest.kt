package com.charmnight.linkgraph.llm.prompt

import com.charmnight.linkgraph.agent.model.GenerationContext
import com.charmnight.linkgraph.agent.model.GraphEvidenceProfile
import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.llm.context.ContextBudgetController
import com.charmnight.linkgraph.llm.context.PromptComposer
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptBuilderMaterializationTest {
    @Test
    fun generationPromptRendersOnlyBudgetReachableGraphAndSourceEntries() {
        val nodes = CountingList((1..300).map(::node))
        val edges = CountingList((1..500).map { index -> edge(index, nodes.size) })
        val snippets = CountingList((1..120).map(::snippet))
        val composer = PromptComposer(ContextBudgetController(maxCharacters = 600, maxTokens = 120))

        val prompt = buildGenerationPromptPackage(
            promptComposer = composer,
            snapshot = GenerationContext(
                graph = GraphDocument(nodes = nodes, edges = edges),
                sourceContext = snippets,
                userGoal = "只保留很小的 prompt 预算。",
            ),
            settings = LinkGraphSettingsState(model = "diagnostic"),
        )

        assertTrue(nodes.iterated < nodes.size, "graph nodes should stop rendering when prompt budget is exhausted")
        assertTrue(edges.iterated < edges.size, "graph edges should stop rendering when prompt budget is exhausted")
        assertTrue(snippets.iterated < snippets.size, "source snippets should stop rendering when prompt budget is exhausted")
        assertTrue(prompt.userPrompt.length <= 600, "final user prompt is still budget-trimmed")
    }

    @Test
    fun qaPromptWithPresetEvidenceProfileRendersOnlyBudgetReachableGraphAndSourceEntries() {
        val nodes = CountingList((1..300).map(::node))
        val edges = CountingList((1..500).map { index -> edge(index, nodes.size) })
        val snippets = CountingList((1..120).map(::snippet))
        val composer = PromptComposer(ContextBudgetController(maxCharacters = 600, maxTokens = 120))

        val prompt = buildQaPromptPackage(
            promptComposer = composer,
            context = GraphQaContext(
                factGraph = GraphDocument(),
                editableGraph = GraphDocument(nodes = nodes, edges = edges),
                selectedNodeIds = emptyList(),
                sourceContext = snippets,
                evidenceProfile = GraphEvidenceProfile(anchorNodeType = NodeType.CLASS),
            ),
            question = "为什么展开计算很重？",
            settings = LinkGraphSettingsState(model = "diagnostic"),
        )

        assertTrue(nodes.iterated < nodes.size, "QA graph nodes should stop rendering when prompt budget is exhausted")
        assertTrue(edges.iterated < edges.size, "QA graph edges should stop rendering when prompt budget is exhausted")
        assertTrue(snippets.iterated < snippets.size, "QA source snippets should stop rendering when prompt budget is exhausted")
        assertTrue(prompt.userPrompt.length <= 600, "final user prompt is still budget-trimmed")
    }

    @Test
    fun generationPromptDoesNotMaterializeEveryGraphEntryBeforeBudgetTrimming() {
        val nodes = CountingList((1..300).map(::node))
        val edges = CountingList((1..500).map { index -> edge(index, nodes.size) })
        val snippets = CountingList((1..120).map(::snippet))
        val composer = PromptComposer(ContextBudgetController(maxCharacters = 600, maxTokens = 120))

        val prompt = buildGenerationPromptPackage(
            promptComposer = composer,
            snapshot = GenerationContext(
                graph = GraphDocument(nodes = nodes, edges = edges),
                sourceContext = snippets,
                userGoal = "只保留很小的 prompt 预算。",
            ),
            settings = LinkGraphSettingsState(model = "diagnostic"),
        )

        assertTrue(nodes.iterated < nodes.size, "nodes should not be fully rendered before prompt budget trimming")
        assertTrue(edges.iterated < edges.size, "edges should not be fully rendered before prompt budget trimming")
        assertTrue(snippets.iterated < snippets.size, "snippets should not be fully rendered before prompt budget trimming")
        assertTrue(prompt.userPrompt.length <= 600, "final user prompt is still budget-trimmed")
    }

    @Test
    fun qaPromptWithoutPresetEvidenceProfileDoesNotMaterializeEverySourceSnippetBeforeBudgetTrimming() {
        val nodes = CountingList((1..300).map(::node))
        val edges = CountingList((1..500).map { index -> edge(index, nodes.size) })
        val snippets = CountingList((1..120).map(::snippet))
        val composer = PromptComposer(ContextBudgetController(maxCharacters = 600, maxTokens = 120))

        val prompt = buildQaPromptPackage(
            promptComposer = composer,
            context = GraphQaContext(
                factGraph = GraphDocument(),
                editableGraph = GraphDocument(nodes = nodes, edges = edges),
                selectedNodeIds = emptyList(),
                sourceContext = snippets,
            ),
            question = "为什么展开计算很重？",
            settings = LinkGraphSettingsState(model = "diagnostic"),
        )

        assertEquals(edges.size, edges.iterated, "derived evidence profile should scan graph edges once for exact relation counts")
        assertTrue(snippets.iterated < snippets.size, "prompt source snippets should stop rendering when budget is exhausted")
        assertTrue(prompt.userPrompt.length <= 600, "final user prompt is still budget-trimmed")
    }

    @Test
    fun confirmedChangeSummaryStopsScanningGraphNodesAfterAllTargetFilesAreFound() {
        val nodes = CountingList((1..300).map(::node))

        val summary = confirmedChangeSummary(
            DraftWorkbenchEntry(
                entryId = "change-1",
                kind = DraftEntryKind.CHANGE,
                title = "确认变更",
                targetNodeIds = listOf("node-1"),
            ),
            GraphDocument(nodes = nodes),
        )

        assertTrue(summary.contains("src/main/kotlin/DiagnosticNode1.kt"))
        assertEquals(1, nodes.iterated, "target file lookup should stop after all target nodes are found")
    }

    private fun node(index: Int): GraphNode =
        GraphNode(
            id = "node-$index",
            type = NodeType.CLASS,
            title = "DiagnosticNode$index",
            location = "src/main/kotlin/DiagnosticNode$index.kt",
            signature = "com.example.DiagnosticNode$index",
            doc = "diagnostic node $index",
        )

    private fun edge(
        index: Int,
        nodeCount: Int,
    ): GraphEdge =
        GraphEdge(
            id = "edge-$index",
            type = EdgeType.USES_TYPE,
            fromNodeId = "node-${((index - 1) % nodeCount) + 1}",
            toNodeId = "node-${(index % nodeCount) + 1}",
            label = "diagnostic edge $index",
        )

    private fun snippet(index: Int): SourceSnippetContext =
        SourceSnippetContext(
            nodeId = "node-$index",
            filePath = "src/main/kotlin/DiagnosticNode$index.kt",
            startLine = 1,
            endLine = 20,
            snippet = "fun diagnostic$index() = $index\n".repeat(20),
        )

    private class CountingList<T>(
        private val values: List<T>,
    ) : AbstractList<T>() {
        var iterated: Int = 0
            private set

        override val size: Int
            get() = values.size

        override fun get(index: Int): T = values[index]

        override fun iterator(): Iterator<T> {
            val delegate = values.iterator()
            return object : Iterator<T> {
                override fun hasNext(): Boolean = delegate.hasNext()

                override fun next(): T {
                    iterated += 1
                    return delegate.next()
                }
            }
        }
    }
}
