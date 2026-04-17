package com.charmnight.linkgraph.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class LinkGraphToolWindowApiMigrationTest {
    @Test
    fun integrationFixtureDoesNotUseRemovedCurrentMethodApis() {
        val content = Files.readString(
            Path.of("src/integrationTest/kotlin/com/charmnight/linkgraph/ui/LinkGraphToolWindowIT.kt"),
        )

        assertFalse(
            content.contains("RequestCurrentMethodGraph"),
            "集成测试不应继续引用已移除的 RequestCurrentMethodGraph 消息。",
        )
        assertFalse(
            content.contains("loadCurrentMethodGraphAsync"),
            "集成测试不应继续引用已移除的 loadCurrentMethodGraphAsync 入口。",
        )
    }
}
