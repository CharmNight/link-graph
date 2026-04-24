package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkGraphProjectServiceBeautificationTest : BasePlatformTestCase() {
    fun testRequestGraphBeautificationBuildsPromptFromVisibleGraphFullGraphAndSourceSnippet() {
        val sourceFile = Files.createTempFile("linkgraph-beautification", ".java")
        val sourceCode = """
            class ShiroUtils {
                void getSysUser() {
                    BeanUtils.copyBeanProp(user, obj);
                }
            }
        """.trimIndent()
        Files.writeString(sourceFile, sourceCode)
        val methodSignature = "com.ruoyi.common.utils.ShiroUtils.getSysUser():com.ruoyi.common.core.domain.entity.SysUser"
        val snippetText = "BeanUtils.copyBeanProp(user, obj);"
        val snippetStart = sourceCode.indexOf(snippetText)
        val snippetEnd = snippetStart + snippetText.length

        val visibleActionNode = GraphNode(
            id = "flow-action:copy-bean",
            type = NodeType.FLOW_ACTION,
            title = "BeanUtils.copyBeanProp(user, obj)",
            signature = "BeanUtils.copyBeanProp(user, obj)",
            metadata = mapOf(
                "flow.anchorMethod" to methodSignature,
                "flow.ownerMethod" to methodSignature,
                "source.filePath" to sourceFile.toString(),
                "source.startOffset" to snippetStart.toString(),
                "source.endOffset" to snippetEnd.toString(),
            ),
            sourceTag = GraphSourceTag.FACT,
        )
        val hiddenActionNode = GraphNode(
            id = "flow-action:return-user",
            type = NodeType.FLOW_ACTION,
            title = "return user",
            signature = "return user",
            metadata = mapOf(
                "flow.anchorMethod" to methodSignature,
                "flow.ownerMethod" to methodSignature,
            ),
            sourceTag = GraphSourceTag.FACT,
        )
        val methodNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, methodSignature),
            type = NodeType.METHOD,
            title = "ShiroUtils.getSysUser",
            signature = methodSignature,
            sourceTag = GraphSourceTag.FACT,
        )
        val crossMethodNode = GraphNode(
            id = "method:downstream-helper",
            type = NodeType.METHOD,
            title = "UserMapper.selectUserById",
            signature = "com.ruoyi.system.mapper.UserMapper.selectUserById(java.lang.Long):SysUser",
            sourceTag = GraphSourceTag.FACT,
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(methodNode, visibleActionNode),
        )
        val fullGraph = GraphDocument(
            nodes = listOf(methodNode, visibleActionNode, hiddenActionNode, crossMethodNode),
        )

        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            source = "currentMethod",
            selectedMethodSignature = methodSignature,
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        val result = service.requestGraphBeautification(
            goal = "把当前方法链路讲清楚",
            preferredStyle = "汇报版",
            explanationFocus = "先讲当前方法内部",
        )

        val snapshot = stateService.snapshot()
        assertEquals(LlmResultSource.MOCK, result.source)
        assertEquals(result, snapshot.graphBeautificationResult)
        assertEquals("graphBeautificationResult", snapshot.lastMessageType)
        assertTrue(result.steps.isNotEmpty())
        assertTrue(result.steps.any { it.description.contains("BeanUtils.copyBeanProp(user, obj)") })
        assertTrue(result.steps.any { it.downstreamTargets.contains(crossMethodNode.id) })
        assertTrue(result.warnings.any { it.contains("当前方法内部仍有 1 个节点未展开") })
        assertTrue(result.warnings.any { it.contains("跨方法扩展仍有 1 个节点未展开") })
        assertTrue(result.promptPreview.contains("ShiroUtils.getSysUser"))
        assertTrue(result.promptPreview.contains("BeanUtils.copyBeanProp(user, obj)"))
        assertTrue(result.promptPreview.contains("id=flow-action:copy-bean"))
        assertTrue(result.promptPreview.contains(snippetText))
        assertTrue(result.promptPreview.contains("当前方法内部折叠节点：1"))
        assertTrue(result.promptPreview.contains("跨方法扩展折叠节点：1"))
        assertTrue(result.promptPreview.contains("汇报版"))
    }

    fun testRequestGraphBeautificationPreservesWholeMethodSignatureLineInPromptSnippet() {
        val sourceFile = Files.createTempFile("linkgraph-beautification-signature", ".java")
        val padding = (1..5).joinToString(", ") { index -> "String padding${index}${"x".repeat(32)}" }
        val methodLine =
            "public void fileDownload(String fileName, Boolean delete, $padding, HttpServletResponse response, HttpServletRequest request) {}"
        val sourceCode = """
            class DownloadController {
                $methodLine
            }
        """.trimIndent()
        Files.writeString(sourceFile, sourceCode)
        val methodSignature = "com.example.DownloadController.fileDownload(java.lang.String,java.lang.Boolean):void"
        val lineStart = sourceCode.indexOf("public void fileDownload")
        val lineEnd = lineStart + methodLine.length
        val methodNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, methodSignature),
            type = NodeType.METHOD,
            title = "DownloadController.fileDownload",
            signature = methodSignature,
            sourceTag = GraphSourceTag.FACT,
        )
        val actionNode = GraphNode(
            id = "flow-action:method-signature",
            type = NodeType.FLOW_ACTION,
            title = "方法签名",
            metadata = mapOf(
                "flow.anchorMethod" to methodSignature,
                "flow.ownerMethod" to methodSignature,
                "source.filePath" to sourceFile.toString(),
                "source.startOffset" to lineStart.toString(),
                "source.endOffset" to lineEnd.toString(),
            ),
            sourceTag = GraphSourceTag.FACT,
        )

        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = GraphDocument(nodes = listOf(methodNode, actionNode)),
            fullGraph = GraphDocument(nodes = listOf(methodNode, actionNode)),
            source = "currentMethod",
            selectedMethodSignature = methodSignature,
        )

        val result = project.getService(LinkGraphProjectService::class.java).requestGraphBeautification(
            goal = "确认源码片段完整性",
            preferredStyle = "审阅版",
            explanationFocus = "只看当前方法签名片段",
        )

        assertTrue(result.promptPreview.contains("HttpServletResponse response"))
        assertTrue(result.promptPreview.contains("HttpServletRequest request"))
    }

    fun testRequestGraphBeautificationPrefersCurrentWorkingGraphAfterCanvasEdits() {
        val methodSignature = "com.example.ShiroUtils.setSysUser(com.example.SysUser):void"
        val methodNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, methodSignature),
            type = NodeType.METHOD,
            title = "ShiroUtils.setSysUser",
            signature = methodSignature,
            sourceTag = GraphSourceTag.FACT,
        )
        val visibleDraftNode = GraphNode(
            id = "doc:manual-note",
            type = NodeType.DOC_PAGE,
            title = "人工补充说明",
            doc = "当前画布里新增的说明节点。",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
        )
        val staleFactOnlyCrossNode = GraphNode(
            id = "method:fallback-guard",
            type = NodeType.METHOD,
            title = "FallbackGuard.handle",
            signature = "com.example.FallbackGuard.handle():void",
            sourceTag = GraphSourceTag.FACT,
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = GraphDocument(nodes = listOf(methodNode)),
            fullGraph = GraphDocument(
                nodes = listOf(methodNode, staleFactOnlyCrossNode),
                edges = listOf(
                    GraphEdge(
                        id = "edge:stale-fact",
                        type = EdgeType.CALL,
                        fromNodeId = methodNode.id,
                        toNodeId = staleFactOnlyCrossNode.id,
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            source = "currentMethod",
            selectedMethodSignature = methodSignature,
        )
        stateService.markGraphChanged(
            graph = GraphDocument(nodes = listOf(methodNode, visibleDraftNode)),
            selectedMethodSignature = methodSignature,
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        val result = service.requestGraphBeautification(
            goal = "解释当前工作图",
            preferredStyle = "审阅版",
            explanationFocus = "只解释当前画布内容",
        )

        assertTrue(result.promptPreview.contains("人工补充说明"))
        assertTrue(!result.promptPreview.contains("FallbackGuard.handle"))
    }
}
