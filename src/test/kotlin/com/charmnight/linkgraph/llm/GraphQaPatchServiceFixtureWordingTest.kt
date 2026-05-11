package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphQaPatchServiceFixtureWordingTest {
    @Test
    fun qaPatchServiceTestKeepsOnlyMinimalLegacyAuditCompatibilityCopy() {
        val content = Files.readString(
            Path.of("src/test/kotlin/com/charmnight/linkgraph/llm/GraphQaPatchServiceTest.kt"),
        )

        assertFalse(
            content.contains("远程审计建议"),
            "GraphQaPatchServiceTest 不应继续保留旧“远程审计建议”口径。",
        )
        assertFalse(
            content.contains("远程审计草稿"),
            "GraphQaPatchServiceTest 不应继续保留旧“远程审计草稿”口径。",
        )
        assertFalse(
            content.contains("继续审计"),
            "GraphQaPatchServiceTest 非兼容场景不应继续使用“继续审计”口径。",
        )
        assertFalse(
            content.contains("审计建议"),
            "GraphQaPatchServiceTest 非兼容场景不应继续保留“审计建议”口径。",
        )

        val legacyPromptCount = "请审计".toRegex().findAll(content).count()
        assertTrue(
            legacyPromptCount <= 1,
            "GraphQaPatchServiceTest 只应保留 1 个旧“请审计”样例来覆盖兼容提问分支，当前为 $legacyPromptCount 个。",
        )
    }
}
