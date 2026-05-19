package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class QaSourceWordingCleanupTest {
    @Test
    fun qaPrimaryPathSourceCommentsAndCopyDoNotKeepObsoleteWording() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val filesWithoutObsoleteWording = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/application/request/AsyncRequestLifecycleSupport.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/ConfirmedDraftChangeCoordinator.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorMessage.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt",
            "src/main/kotlin/com/charmnight/linkgraph/llm/LlmPromptFactory.kt",
            "src/main/kotlin/com/charmnight/linkgraph/llm/LlmTypes.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/planning/PlanningContextFactory.kt",
            "src/main/kotlin/com/charmnight/linkgraph/llm/GraphQaScopeResolver.kt",
        )

        val obsoleteWording = charArrayOf('审', '计').concatToString()
        filesWithoutObsoleteWording.forEach { relativePath ->
            val content = Files.readString(projectRoot.resolve(relativePath))
            assertFalse(
                content.contains(obsoleteWording),
                "问答主路径源码说明不应继续保留旧口径：$relativePath",
            )
        }

        val qaPatchService = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/llm/GraphQaPatchService.kt"),
        )
        assertFalse(
            qaPatchService.contains(obsoleteWording),
            "GraphQaPatchService 不应继续保留旧提问识别分支。",
        )
    }
}
