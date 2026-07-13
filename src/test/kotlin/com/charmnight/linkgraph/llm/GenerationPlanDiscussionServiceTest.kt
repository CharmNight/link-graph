package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.GenerationContext
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GenerationPlanItem
import com.charmnight.linkgraph.agent.model.GenerationPlanSource
import com.charmnight.linkgraph.agent.model.LlmGateway
import com.charmnight.linkgraph.agent.model.LlmRequest
import com.charmnight.linkgraph.agent.model.LlmResponse
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LlmProviderPresets
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerationPlanDiscussionServiceTest {
    @Test
    fun remoteDiscussionStripsSourceSnippetsByDefaultAndWarns() {
        val requests = mutableListOf<LlmRequest>()
        val service = GenerationPlanDiscussionService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    requests += request
                    return LlmResponse(
                        content = """
                            {
                              "answer": "这是围绕当前实现建议的远程说明。",
                              "focusItemId": "plan-item",
                              "warnings": []
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        )

        val result = service.discuss(
            context = GenerationContext(
                graph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:discussion-target",
                            type = NodeType.METHOD,
                            title = "DiscussionTarget.handle",
                        ),
                    ),
                ),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:discussion-target",
                        filePath = "src/main/java/com/example/DiscussionTarget.java",
                        startLine = 7,
                        endLine = 8,
                        snippet = "String discussionRemoteSecret = \"discussion-secret\";",
                    ),
                ),
            ),
            plan = GenerationPlan(
                source = GenerationPlanSource.REMOTE,
                summary = "调整 DiscussionTarget。",
                items = listOf(
                    GenerationPlanItem(
                        id = "plan-item",
                        title = "调整 handle",
                        description = "解释这个实现建议。",
                        risk = SyncPreviewRisk.MEDIUM,
                        targetPath = "src/main/java/com/example/DiscussionTarget.java",
                    ),
                ),
            ),
            question = "为什么这样改？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "gpt-test",
            ),
            session = null,
            focusItemId = "plan-item",
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertEquals(1, requests.size)
        assertFalse(requests.single().userPrompt.contains("discussionRemoteSecret"))
        assertFalse(requests.single().userPrompt.contains("discussion-secret"))
        assertTrue(result.warnings.any { it.contains("源码片段外发未授权") })
    }
}
