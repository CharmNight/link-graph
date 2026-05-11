package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class SecondarySourceWordingCleanupTest {
    @Test
    fun secondaryProductionSourceCommentsDoNotKeepAuditWording() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val files = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/application/diagnostics/GenerationDiagnostics.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/planning/InteractiveGraphProjector.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/debug/DebugGraphFactory.kt",
            "src/main/kotlin/com/charmnight/linkgraph/codegen/CodeGenerationService.kt",
        )

        files.forEach { relativePath ->
            val content = Files.readString(projectRoot.resolve(relativePath))
            assertFalse(
                content.contains("审计"),
                "二级生产源码说明不应继续保留“审计”口径：$relativePath",
            )
        }
    }
}
