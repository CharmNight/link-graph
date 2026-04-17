package com.charmnight.linkgraph.llm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaSourceWordingCleanupTest {
    @Test
    fun qaPrimaryPathSourceCommentsAndCopyDoNotKeepAuditWording() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val filesWithoutAuditWording = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/services/AsyncRequestLifecycleSupport.kt",
            "src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt",
            "src/main/kotlin/com/charmnight/linkgraph/services/ReviewWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorMessage.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt",
            "src/main/kotlin/com/charmnight/linkgraph/llm/LlmPromptFactory.kt",
            "src/main/kotlin/com/charmnight/linkgraph/llm/LlmTypes.kt",
            "src/main/kotlin/com/charmnight/linkgraph/services/PlanningContextFactory.kt",
            "src/main/kotlin/com/charmnight/linkgraph/llm/GraphAuditScopeResolver.kt",
        )

        filesWithoutAuditWording.forEach { relativePath ->
            val content = Files.readString(projectRoot.resolve(relativePath))
            assertFalse(
                content.contains("审计"),
                "问答主路径源码说明不应继续保留“审计”口径：$relativePath",
            )
        }

        val auditPatchService = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/llm/GraphAuditPatchService.kt"),
        )
        val auditWordingCount = "审计".toRegex().findAll(auditPatchService).count()
        assertEquals(
            1,
            auditWordingCount,
            "GraphAuditPatchService 只应保留兼容旧提问方式所需的一处“审计”识别。",
        )
        assertTrue(
            auditPatchService.contains("question.contains(\"审计\")"),
            "GraphAuditPatchService 保留的唯一“审计”字样应来自兼容提问识别分支。",
        )
    }
}
