package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class GraphQaPatchServiceFixtureWordingTest {
    @Test
    fun qaPatchServiceTestUsesQaWordingConsistently() {
        val content = Files.readString(
            Path.of("src/test/kotlin/com/charmnight/linkgraph/llm/GraphQaPatchServiceTest.kt"),
        )

        val obsoleteWording = charArrayOf('审', '计').concatToString()
        assertFalse(content.contains("远程${obsoleteWording}建议"))
        assertFalse(content.contains("远程${obsoleteWording}草稿"))
        assertFalse(content.contains("继续$obsoleteWording"))
        assertFalse(content.contains("${obsoleteWording}建议"))
        assertFalse(content.contains("请$obsoleteWording"))
    }
}
