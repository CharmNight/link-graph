package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class PromptSchemaCopyTest {
    @Test
    fun qaAndDiffSchemaPlaceholdersUseQaOrDiffWording() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val qaService = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/llm/GraphQaPatchService.kt"),
        )
        val diffService = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/llm/GraphDiffPatchService.kt"),
        )

        assertFalse(
            qaService.contains("\"answer\": \"问答或差异说明\""),
            "问答链路的 JSON 占位文案不应继续保留“复核”口径。",
        )
        assertFalse(
            diffService.contains("\"answer\": \"问答或差异说明\""),
            "差异链路的 JSON 占位文案不应继续混入“复核”口径。",
        )
    }
}
