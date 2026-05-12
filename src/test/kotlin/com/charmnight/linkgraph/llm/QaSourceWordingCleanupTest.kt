package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

        filesWithoutObsoleteWording.forEach { relativePath ->
            val content = Files.readString(projectRoot.resolve(relativePath))
            assertFalse(
                content.contains("审计"),
                "问答主路径源码说明不应继续保留“审计”口径：$relativePath",
            )
        }

        val qaPatchService = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/llm/GraphQaPatchService.kt"),
        )
        val legacyWordingCount = "审计".toRegex().findAll(qaPatchService).count()
        assertEquals(
            1,
            legacyWordingCount,
            "GraphQaPatchService 只应保留兼容旧提问方式所需的一处“审计”识别。",
        )
        assertTrue(
            qaPatchService.contains("question.contains(\"审计\")"),
            "GraphQaPatchService 保留的唯一“审计”字样应来自兼容提问识别分支。",
        )
    }
}
