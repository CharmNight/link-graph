package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.WorkbenchLayoutPreferencesService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals

class LinkGraphProjectServiceWorkbenchLayoutTest : BasePlatformTestCase() {
    fun testUpdateWorkbenchSectionPreferenceWritesRuntimeAndPersistentStateForInvestigationThreads() {
        var syncRequestedCount = 0
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(
            GraphEditorSyncNotifier.TOPIC,
            object : GraphEditorSyncNotifier.Listener {
                override fun onSyncRequested() {
                    syncRequestedCount += 1
                }
            },
        )

        project.getService(LinkGraphProjectService::class.java)
            .updateWorkbenchSectionPreference("audit.investigation-threads", true)

        assertEquals(
            true,
            project.getService(GraphEditorStateService::class.java)
                .snapshot()
                .workbenchSectionPreferences["audit.investigation-threads"],
        )
        assertEquals(
            true,
            project.getService(WorkbenchLayoutPreferencesService::class.java)
                .snapshot()["audit.investigation-threads"],
        )
        assertEquals(1, syncRequestedCount)
    }
}
