package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.WorkbenchLayoutPreferencesService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.fail

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

        project.getService(GraphEditorCommandRouter::class.java)
            .updateWorkbenchSectionPreference("audit.investigation-threads", true)

        waitForRuntimePreference("audit.investigation-threads")

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
        assertEquals(true, syncRequestedCount >= 1)
    }

    private fun waitForRuntimePreference(sectionId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (project.getService(GraphEditorStateService::class.java)
                    .snapshot()
                    .workbenchSectionPreferences[sectionId] == true
            ) {
                return
            }
            Thread.sleep(50)
        }
        fail("等待工作台偏好写回运行时状态超时")
    }
}
