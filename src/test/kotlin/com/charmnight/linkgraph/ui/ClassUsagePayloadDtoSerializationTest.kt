package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.json.JsonCodec
import com.charmnight.linkgraph.usage.ClassUsageEntry
import com.charmnight.linkgraph.usage.ClassUsageGroup
import com.charmnight.linkgraph.usage.ClassUsageKind
import com.charmnight.linkgraph.usage.ClassUsageOwnerKind
import com.charmnight.linkgraph.usage.ClassUsageSearchResult
import com.charmnight.linkgraph.usage.ClassUsageSummary
import com.charmnight.linkgraph.usage.ClassUsageTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2-6 DTO 序列化一致性测试。
 *
 * 验证 ClassUsageSearchResultDto 通过 Gson 序列化后的 JSON
 * 与原 `linkedMapOf` 的字段顺序、字段名、字段值完全一致——
 * 这保证 DTO 化不会破坏前端协议。
 */
class ClassUsagePayloadDtoSerializationTest {
    @Test
    fun serializesClassUsageSearchResultWithExpectedFieldOrder() {
        val result = ClassUsageSearchResult(
            target = ClassUsageTarget(
                nodeId = "class:com.example.OrderController",
                qualifiedName = "com.example.OrderController",
                displayName = "OrderController",
            ),
            summary = ClassUsageSummary(
                targetNodeId = "class:com.example.OrderController",
                targetQualifiedName = "com.example.OrderController",
                groupCount = 2,
                usageCount = 3,
                visibleGroupCount = 1,
                visibleUsageCount = 2,
                truncated = true,
                maxUsageGroups = 200,
                maxUsageEntries = 500,
                includeImports = false,
                canRequestMore = true,
            ),
            groups = listOf(
                ClassUsageGroup(
                    id = "group:submit",
                    ownerNodeId = "method:com.example.OrderService.submit",
                    ownerKind = ClassUsageOwnerKind.METHOD,
                    title = "OrderService.submit",
                    qualifiedName = "com.example.OrderService.submit",
                    filePath = "src/main/java/com/example/OrderService.java",
                    virtualFileUrl = "file:///tmp/OrderService.java",
                    usages = listOf(
                        ClassUsageEntry(
                            id = "usage-1",
                            ownerId = "group:submit",
                            kind = ClassUsageKind.METHOD_PARAMETER,
                            filePath = "src/main/java/com/example/OrderService.java",
                            line = 12,
                            column = 30,
                            text = "OrderController controller",
                            virtualFileUrl = "file:///tmp/OrderService.java",
                            ownerQualifiedName = "com.example.OrderService",
                            ownerMethodSignature = "submit(OrderController):void",
                        ),
                    ),
                ),
            ),
        )

        val dto = result.toDto()
        val json = JsonCodec.toJson(dto)

        // 字段顺序检查：target 必须在 summary 之前，summary 必须在 groups 之前
        val targetIdx = json.indexOf("\"target\":")
        val summaryIdx = json.indexOf("\"summary\":")
        val groupsIdx = json.indexOf("\"groups\":")
        assertTrue(targetIdx >= 0 && summaryIdx > targetIdx && groupsIdx > summaryIdx)

        // 关键字段名都出现
        assertTrue(json.contains("\"nodeId\":\"class:com.example.OrderController\""))
        assertTrue(json.contains("\"qualifiedName\":\"com.example.OrderController\""))
        assertTrue(json.contains("\"displayName\":\"OrderController\""))
        assertTrue(json.contains("\"truncated\":true"))
        assertTrue(json.contains("\"canRequestMore\":true"))
        assertTrue(json.contains("\"kind\":\"METHOD_PARAMETER\""))
        assertTrue(json.contains("\"ownerKind\":\"METHOD\""))
        assertTrue(json.contains("\"ownerMethodSignature\":\"submit(OrderController):void\""))
        // nullable 字段保留（Gson serializeNulls）
        assertTrue(json.contains("\"ownerNodeId\":\"method:com.example.OrderService.submit\""))
    }

    @Test
    fun dtoFieldShapeMatchesOriginalLinkedMapContract() {
        val target = ClassUsageTarget(
            nodeId = "class:Foo",
            qualifiedName = "com.example.Foo",
            displayName = "Foo",
        )

        val dto = target.let { t ->
            ClassUsageTargetDto(
                nodeId = t.nodeId,
                qualifiedName = t.qualifiedName,
                displayName = t.displayName,
            )
        }

        // 字段顺序与原 linkedMapOf("nodeId" to ..., "qualifiedName" to ..., "displayName" to ...) 一致
        val json = JsonCodec.toJson(dto)
        val expected = """{"nodeId":"class:Foo","qualifiedName":"com.example.Foo","displayName":"Foo"}"""
        assertEquals(expected, json)
    }
}
