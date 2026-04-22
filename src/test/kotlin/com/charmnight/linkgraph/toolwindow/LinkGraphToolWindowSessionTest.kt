package com.charmnight.linkgraph.toolwindow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class LinkGraphToolWindowSessionTest {
    @Test
    fun mainToolWindowSessionDoesNotContainDebugAutomation() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/toolwindow/LinkGraphToolWindowSession.kt"),
        )

        assertFalse(
            source.contains("scheduleDebugGraphAutoloadIfRequested"),
            "Expected main tool window session to stop owning debug graph autoload orchestration",
        )
        assertFalse(
            source.contains("scheduleDebugWorkbenchAutomationIfRequested"),
            "Expected main tool window session to stop owning debug workbench automation orchestration",
        )
        assertFalse(
            source.contains("LINKGRAPH_DEBUG_AUTOLOAD_GRAPH"),
            "Expected debug graph env parsing to live outside the main tool window session",
        )
        assertFalse(
            source.contains("LINKGRAPH_DEBUG_AUTOLOAD_METHOD_SIGNATURE"),
            "Expected debug method autoload env parsing to live outside the main tool window session",
        )
        assertFalse(
            source.contains("LINKGRAPH_DEBUG_AUTO_REQUEST_PLAN"),
            "Expected debug plan env parsing to live outside the main tool window session",
        )
        assertFalse(
            source.contains("LINKGRAPH_DEBUG_AUTO_REQUEST_CODE_DRAFTS"),
            "Expected debug code draft env parsing to live outside the main tool window session",
        )
        assertFalse(
            source.contains("LinkGraphProjectService"),
            "Expected main tool window session to remain a product-path UI host instead of a debug orchestrator",
        )
    }
}
