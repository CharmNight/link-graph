package com.charmnight.linkgraph.services

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
            "src/main/kotlin/com/charmnight/linkgraph/extract/JavaResolver.kt",
        )

        files.forEach { relativePath ->
            val content = Files.readString(projectRoot.resolve(relativePath))
            assertTrue(
                "logger.info(" !in content,
                "默认用户路径文件不应保留 logger.info：$relativePath",
            )
        }
    }
}
