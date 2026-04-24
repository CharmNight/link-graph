package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphAuditPatchServiceFixtureWordingTest {
    @Test
    fun auditPatchServiceTestKeepsOnlyMinimalLegacyAuditCompatibilityCopy() {
        val content = Files.readString(
            Path.of("src/test/kotlin/com/charmnight/linkgraph/llm/GraphAuditPatchServiceTest.kt"),
        )

        assertFalse(
            content.contains("远程审计建议"),
            "GraphAuditPatchServiceTest 不应继续保留旧“远程审计建议”口径。",
        )
        assertFalse(
            content.contains("远程审计草稿"),
            "GraphAuditPatchServiceTest 不应继续保留旧“远程审计草稿”口径。",
        )
        assertFalse(
            content.contains("继续审计"),
            "GraphAuditPatchServiceTest 非兼容场景不应继续使用“继续审计”口径。",
        )
        assertFalse(
            content.contains("审计建议"),
            "GraphAuditPatchServiceTest 非兼容场景不应继续保留“审计建议”口径。",
        )

        val legacyPromptCount = "请审计".toRegex().findAll(content).count()
        assertTrue(
            legacyPromptCount <= 1,
            "GraphAuditPatchServiceTest 只应保留 1 个旧“请审计”样例来覆盖兼容提问分支，当前为 $legacyPromptCount 个。",
        )
    }
}
