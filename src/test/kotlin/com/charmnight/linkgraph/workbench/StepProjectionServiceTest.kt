package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals

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
