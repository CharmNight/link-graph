package com.charmnight.linkgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphBoundaryTest {
    @Test
    fun projectServiceDoesNotOwnToolwindowOrBrowserAndAppUsesDedicatedStateHooksAndControllers() {
        val projectService = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt"),
        )
        val openAction = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/actions/OpenLinkGraphAction.kt"),
        )
        val appSource = Files.readString(Path.of("web/src/app/App.tsx"))

        assertFalse(projectService.contains("GraphBrowserPanel"))
        assertFalse(projectService.contains("LinkGraphToolWindowSession"))

        assertTrue(openAction.contains("LinkGraphToolWindowSession"))
        assertTrue(appSource.contains("useBridgeCommandController"))
        assertTrue(appSource.contains("useSourceNavigationController"))
        assertTrue(appSource.contains("useWorkbenchCommandController"))
        assertTrue(appSource.contains("useWorkbenchState"))
        assertTrue(appSource.contains("useBootstrapStateController"))
        assertFalse(appSource.contains("./components/GraphCanvas"))
    }
}
