package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 覆盖 GraphEditRequestPayloadParser 的结构化错误返回：畸形字段不再抛异常，
 * 而是返回 (request=null, issues=[...])，便于上游构造结构化拒绝响应。
 */
class GraphEditRequestPayloadParserTest {
    @Test
    fun parsesWellFormedRequest() {
        val result = GraphEditRequestPayloadParser.parse(
            mapOf(
                "sceneId" to "WORKSPACE_FACT",
                "baseWorkspaceRevision" to 5,
                "source" to "FRONTEND",
                "operations" to listOf(
                    mapOf(
                        "type" to "UPSERT_NODE",
                        "node" to mapOf(
                            "id" to "node-1",
                            "type" to "METHOD",
                            "title" to "Method 1",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.issues.isEmpty())
        val request = assertNotNull(result.request)
        assertEquals(GraphSceneId.WORKSPACE_FACT, request.sceneId)
        assertEquals(5L, request.baseWorkspaceRevision)
        assertEquals(GraphEditRequestSource.FRONTEND, request.source)
        val op = request.operations.single() as GraphEditOperation.UpsertNode
        assertEquals("node-1", op.node.id)
        assertEquals(NodeType.METHOD, op.node.type)
    }

    @Test
    fun missingSceneIdReturnsIssueInsteadOfThrowing() {
        val result = GraphEditRequestPayloadParser.parse(
            mapOf(
                "baseWorkspaceRevision" to 5,
                "source" to "FRONTEND",
                "operations" to emptyList<String>(),
            ),
        )

        assertNull(result.request)
        val issue = result.issues.single()
        assertEquals(GraphEditIssueCode.INVALID_PAYLOAD_FIELD, issue.code)
        assertTrue(issue.message.contains("sceneId"))
    }

    @Test
    fun missingBaseWorkspaceRevisionReturnsIssue() {
        val result = GraphEditRequestPayloadParser.parse(
            mapOf(
                "sceneId" to "WORKSPACE_FACT",
                "source" to "FRONTEND",
                "operations" to emptyList<String>(),
            ),
        )

        assertNull(result.request)
        val issue = result.issues.single()
        assertEquals(GraphEditIssueCode.INVALID_PAYLOAD_FIELD, issue.code)
        assertTrue(issue.message.contains("baseWorkspaceRevision"))
    }

    @Test
    fun unsupportedEnumValueReturnsIssue() {
        val result = GraphEditRequestPayloadParser.parse(
            mapOf(
                "sceneId" to "WORKSPACE_FACT",
                "baseWorkspaceRevision" to 5,
                "source" to "FRONTEND",
                "operations" to listOf(
                    mapOf(
                        "type" to "UPSERT_NODE",
                        "node" to mapOf(
                            "id" to "node-1",
                            "type" to "NOT_A_NODE_TYPE",
                        ),
                    ),
                ),
            ),
        )

        assertNull(result.request)
        val issue = result.issues.single()
        assertEquals(GraphEditIssueCode.INVALID_PAYLOAD_FIELD, issue.code)
        assertTrue(issue.message.contains("type"))
    }

    @Test
    fun unsupportedOperationTypeReturnsIssueWithOperationIndex() {
        val result = GraphEditRequestPayloadParser.parse(
            mapOf(
                "sceneId" to "WORKSPACE_FACT",
                "baseWorkspaceRevision" to 5,
                "source" to "FRONTEND",
                "operations" to listOf(
                    mapOf(
                        "type" to "UPSERTNODE", // 旧别名，不再支持
                        "node" to mapOf("id" to "node-1", "type" to "METHOD"),
                    ),
                ),
            ),
        )

        assertNull(result.request)
        val issue = result.issues.single()
        assertEquals(GraphEditIssueCode.INVALID_PAYLOAD_FIELD, issue.code)
        assertEquals(0, issue.operationIndex)
        assertTrue(issue.message.contains("unsupported graph edit request operation"))
    }

    @Test
    fun enumOrDefaultAcceptsValidValueForOptionalField() {
        // 验证 enumOrDefault 在传入合法枚举值时也能正常解析（覆盖默认路径）
        val result = GraphEditRequestPayloadParser.parse(
            mapOf(
                "sceneId" to "WORKSPACE_FACT",
                "baseWorkspaceRevision" to 5,
                "source" to "AI_TOOL",
                "operations" to listOf(
                    mapOf(
                        "type" to "UPSERT_NODE",
                        "node" to mapOf(
                            "id" to "node-1",
                            "type" to "METHOD",
                            "certainty" to "RULE_INFERRED",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.issues.isEmpty())
        val op = result.request?.operations?.single() as GraphEditOperation.UpsertNode
        assertEquals(com.charmnight.linkgraph.model.Certainty.RULE_INFERRED, op.node.certainty)
    }

    @Test
    fun emptyOperationsListParsesSuccessfully() {
        // 空操作列表是合法的解析结果；是否拒绝由上游 GraphEditScriptValidator 决定
        val result = GraphEditRequestPayloadParser.parse(
            mapOf(
                "sceneId" to "WORKSPACE_FACT",
                "baseWorkspaceRevision" to 5,
                "source" to "FRONTEND",
                "operations" to emptyList<String>(),
            ),
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(0, result.request?.operations?.size)
    }

    @Test
    fun numericFieldPassedAsStringReturnsIssue() {
        // baseWorkspaceRevision 必须是 Number；传字符串应被识别为畸形
        val result = GraphEditRequestPayloadParser.parse(
            mapOf(
                "sceneId" to "WORKSPACE_FACT",
                "baseWorkspaceRevision" to "not-a-number",
                "source" to "FRONTEND",
                "operations" to emptyList<String>(),
            ),
        )

        assertNull(result.request)
        val issue = result.issues.single()
        assertEquals(GraphEditIssueCode.INVALID_PAYLOAD_FIELD, issue.code)
        assertTrue(issue.message.contains("baseWorkspaceRevision"))
    }
}
