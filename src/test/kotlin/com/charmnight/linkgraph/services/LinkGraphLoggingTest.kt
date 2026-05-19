package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.foundation.debugLazy
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
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/SubjectGraphWorkflow.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationService.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/workflow/SourceNavigationWorkflow.kt",
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
    fun qaRuntimeLogsUseQaWordingInsteadOfObsoleteWording() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val graphBrowserPanel = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPanel.kt"),
        )
        val confirmedDraftCoordinator = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ConfirmedDraftChangeCoordinator.kt"),
        )
        val reviewWorkflow = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt"),
        )

        val obsoleteWording = charArrayOf('审', '计').concatToString()
        assertFalse(graphBrowserPanel.contains("收到前端请求：$obsoleteWording"))
        assertFalse(confirmedDraftCoordinator.contains("确认${obsoleteWording}候选变更"))
        assertFalse(confirmedDraftCoordinator.contains("${obsoleteWording}候选变更已写入草稿层"))
        assertFalse(confirmedDraftCoordinator.contains("取消确认${obsoleteWording}候选变更"))
        assertFalse(reviewWorkflow.contains("异步${obsoleteWording}失败"))
    }

    @Test
    fun renderTraceCallersUseOptionalTraceSinkSoDisabledTraceDoesNotBuildDetails() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val runtimeSupport = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/application/runtime/LinkGraphProjectRuntimeSupport.kt"),
        )
        val components = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationService.kt"),
        )
        val graphBrowserPanel = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPanel.kt"),
        )

        assertTrue(
            runtimeSupport.contains("fun runtimeTraceSink(): (((() -> String) -> Unit))?"),
            "Runtime support should expose a nullable lazy trace sink so callers can skip trace work entirely.",
        )
        assertFalse(
            components.contains("runtimeTrace = { message -> runtimeSupport.runtimeTrace(message) }"),
            "Project components should not pass always-present render trace lambdas that build details when trace is disabled.",
        )
        assertFalse(
            components.contains("runtimeTrace = { message -> runtimeSupport.runtimeTrace { message } }"),
            "Project components should not wrap string traces in an always-present lazy lambda.",
        )
        assertTrue(
            graphBrowserPanel.contains("private val runtimeTraceSink: (((() -> String) -> Unit))?"),
            "Browser panel should pass null trace sinks to transport helpers when trace is disabled.",
        )
    }
}
