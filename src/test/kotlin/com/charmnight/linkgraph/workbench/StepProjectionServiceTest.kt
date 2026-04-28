package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepProjectionServiceTest {
    @Test
    fun `builds business steps with stable ids from fact and draft graphs`() {
        val result = StepProjectionService().buildSteps(
            factGraph = uploadGraphFixture(),
            draftEntries = emptyList(),
            granularity = StepGranularity.BUSINESS,
        )

        assertEquals(
            listOf("step-upload-dir", "step-save-file", "step-build-url", "step-return-result"),
            result.steps.map { it.stepId },
        )
    }

    @Test
    fun `uses action-oriented fallback titles when business metadata is missing`() {
        val result = StepProjectionService().buildSteps(
            factGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "flow:config-path",
                        type = NodeType.FLOW_ACTION,
                        title = "RuoYiConfig.getUploadPath()",
                        metadata = mapOf("source.startLine" to "82"),
                    ),
                    GraphNode(
                        id = "flow:transfer-file",
                        type = NodeType.FLOW_ACTION,
                        title = "file.transferTo(uploadFile)",
                        metadata = mapOf("source.startLine" to "84"),
                    ),
                    GraphNode(
                        id = "terminal:return",
                        type = NodeType.TERMINAL,
                        title = "AjaxResult.success()",
                        metadata = mapOf("source.startLine" to "88"),
                    ),
                ),
            ),
            draftEntries = emptyList(),
            granularity = StepGranularity.BUSINESS,
        )

        assertTrue(result.steps[0].title.contains("读取") || result.steps[0].title.contains("获取"))
        assertTrue(result.steps[1].title.contains("执行") || result.steps[1].title.contains("调用"))
        assertTrue(result.steps[2].title.contains("返回"))
    }

    @Test
    fun `builds method-call steps from invocation actions and called methods`() {
        val result = StepProjectionService().buildSteps(
            factGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:upload-file",
                        type = NodeType.METHOD,
                        title = "CommonController.uploadFile",
                        signature = "CommonController.uploadFile()",
                        metadata = mapOf("source.startLine" to "76"),
                    ),
                    GraphNode(
                        id = "flow:get-upload-path",
                        type = NodeType.FLOW_ACTION,
                        title = "RuoYiConfig.getUploadPath()",
                        metadata = mapOf(
                            "source.startLine" to "82",
                            "flow.kind" to "INVOCATION",
                        ),
                    ),
                    GraphNode(
                        id = "method:config-upload-path",
                        type = NodeType.METHOD,
                        title = "RuoYiConfig.getUploadPath",
                        signature = "RuoYiConfig.getUploadPath():String",
                        metadata = mapOf("source.startLine" to "82"),
                    ),
                ),
            ),
            draftEntries = emptyList(),
            granularity = StepGranularity.METHOD_CALL,
        )

        assertEquals(2, result.steps.size)
        assertTrue(result.steps.all { it.granularity == StepGranularity.METHOD_CALL })
    }

    @Test
    fun `builds code-semantic steps with scopes actions and terminals`() {
        val result = StepProjectionService().buildSteps(
            factGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "scope:if",
                        type = NodeType.FLOW_SCOPE,
                        title = "if (size > 0)",
                        metadata = mapOf("source.startLine" to "81"),
                    ),
                    GraphNode(
                        id = "flow:get-upload-path",
                        type = NodeType.FLOW_ACTION,
                        title = "RuoYiConfig.getUploadPath()",
                        metadata = mapOf("source.startLine" to "82"),
                    ),
                    GraphNode(
                        id = "terminal:return",
                        type = NodeType.TERMINAL,
                        title = "return AjaxResult.success()",
                        metadata = mapOf("source.startLine" to "90"),
                    ),
                ),
            ),
            draftEntries = emptyList(),
            granularity = StepGranularity.CODE_SEMANTIC,
        )

        assertEquals(3, result.steps.size)
        assertEquals(StepKind.CONDITION, result.steps.first().kind)
        assertTrue(result.steps.all { it.granularity == StepGranularity.CODE_SEMANTIC })
    }

    private fun uploadGraphFixture(): GraphDocument {
        val methodNode = GraphNode(
            id = "method:upload-file",
            type = NodeType.METHOD,
            title = "uploadFile",
            signature = "CommonController.uploadFile()",
            metadata = mapOf("source.startLine" to "76"),
        )
        val step1 = GraphNode(
            id = "flow:upload-dir",
            type = NodeType.FLOW_ACTION,
            title = "Upload Dir",
            metadata = mapOf(
                "source.startLine" to "82",
                "workbench.businessStepId" to "step-upload-dir",
                "workbench.businessStepTitle" to "读取上传目录",
            ),
        )
        val step2 = GraphNode(
            id = "flow:save-file",
            type = NodeType.FLOW_ACTION,
            title = "Save File",
            metadata = mapOf(
                "source.startLine" to "84",
                "workbench.businessStepId" to "step-save-file",
                "workbench.businessStepTitle" to "调用上传工具保存文件",
            ),
        )
        val step3 = GraphNode(
            id = "flow:build-url",
            type = NodeType.FLOW_ACTION,
            title = "Build Url",
            metadata = mapOf(
                "source.startLine" to "86",
                "workbench.businessStepId" to "step-build-url",
                "workbench.businessStepTitle" to "拼接回显 URL",
            ),
        )
        val step4 = GraphNode(
            id = "terminal:return",
            type = NodeType.TERMINAL,
            title = "Return Result",
            metadata = mapOf(
                "source.startLine" to "91",
                "workbench.businessStepId" to "step-return-result",
                "workbench.businessStepTitle" to "返回结果",
            ),
        )
        return GraphDocument(
            nodes = listOf(methodNode, step1, step2, step3, step4),
            edges = listOf(
                GraphEdge("contains:1", EdgeType.CONTAINS_FLOW, methodNode.id, step1.id),
                GraphEdge("flow:1", EdgeType.CONTROL_FLOW, step1.id, step2.id),
                GraphEdge("flow:2", EdgeType.CONTROL_FLOW, step2.id, step3.id),
                GraphEdge("flow:3", EdgeType.CONTROL_FLOW, step3.id, step4.id),
            ),
        )
    }
}
