package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class PromptSchemaCopyTest {
    @Test
    fun auditAndDiffSchemaPlaceholdersUseQaOrDiffWording() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val auditService = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/llm/GraphAuditPatchService.kt"),
        )
        val diffService = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/llm/GraphDiffPatchService.kt"),
        )

        assertFalse(
            auditService.contains("\"answer\": \"审计或差异说明\""),
            "问答链路的 JSON 占位文案不应继续保留“审计”口径。",
        )
        assertFalse(
            diffService.contains("\"answer\": \"审计或差异说明\""),
            "差异链路的 JSON 占位文案不应继续混入“审计”口径。",
        )
    }
}
