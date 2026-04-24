package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphLoggingTest {
    @Test
    fun debugLazyDoesNotBuildMessageWhenDebugDisabled() {
        var evaluated = false
        var loggedMessage: String? = null

        debugLazy(
            debugEnabled = false,
            debug = { loggedMessage = it },
        ) {
            evaluated = true
            "expensive-summary"
        }

        assertFalse(evaluated)
        assertEquals(null, loggedMessage)
    }

    @Test
    fun debugLazyBuildsAndLogsMessageWhenDebugEnabled() {
        var evaluations = 0
        var loggedMessage: String? = null

        debugLazy(
            debugEnabled = true,
            debug = { loggedMessage = it },
        ) {
            evaluations += 1
            "expensive-summary"
        }

        assertEquals(1, evaluations)
        assertEquals("expensive-summary", loggedMessage)
    }

    @Test
    fun defaultUserPathsDoNotKeepInfoLoggingNoise() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val files = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPanel.kt",
            "src/main/kotlin/com/charmnight/linkgraph/services/SubjectGraphWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt",
            "src/main/kotlin/com/charmnight/linkgraph/services/SourceNavigationWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/toolwindow/LinkGraphToolWindowSession.kt",
            "src/main/kotlin/com/charmnight/linkgraph/semantic/provider/code/CodeInvocationSemanticResolver.kt",
        )

        files.forEach { relativePath ->
            val content = Files.readString(projectRoot.resolve(relativePath))
            assertTrue(
                "logger.info(" !in content,
                "默认用户路径文件不应保留 logger.info：$relativePath",
            )
        }
    }

    @Test
    fun qaRuntimeLogsUseQaWordingInsteadOfAuditWording() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val graphBrowserPanel = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPanel.kt"),
        )
        val projectService = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt"),
        )
        val reviewWorkflow = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/services/ReviewWorkflow.kt"),
        )

        assertFalse(
            graphBrowserPanel.contains("收到前端请求：审计"),
            "前端问答请求日志不应继续保留“审计”口径。",
        )
        assertFalse(
            projectService.contains("确认审计候选变更"),
            "候选草稿确认日志不应继续保留“审计”口径。",
        )
        assertFalse(
            projectService.contains("审计候选变更已写入草稿层"),
            "草稿写入日志不应继续保留“审计”口径。",
        )
        assertFalse(
            projectService.contains("取消确认审计候选变更"),
            "取消确认日志不应继续保留“审计”口径。",
        )
        assertFalse(
            reviewWorkflow.contains("异步审计失败"),
            "问答异步失败日志不应继续保留“审计”口径。",
        )
    }
}
