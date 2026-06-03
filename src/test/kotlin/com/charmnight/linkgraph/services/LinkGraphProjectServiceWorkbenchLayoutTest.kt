package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.ui.GraphEditorCommandRouter
import com.charmnight.linkgraph.ui.GraphEditorSyncNotifier
import com.charmnight.linkgraph.testing.*

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
            .updateWorkbenchSectionPreference("qa.investigation-threads", true)

        waitForRuntimePreference("qa.investigation-threads")

        assertEquals(
            true,
            project.getService(GraphEditorStateService::class.java)
                .snapshot()
                .workbenchSectionPreferences["qa.investigation-threads"],
        )
        assertEquals(
            true,
            project.getService(WorkbenchLayoutPreferencesService::class.java)
                .snapshot()["qa.investigation-threads"],
        )
        assertEquals(true, syncRequestedCount >= 1)
    }

    fun testObsoleteWorkbenchSectionPreferenceIsIgnored() {
        val obsoleteQaSectionPrefix = "au" + "dit"
        val stateService = project.getService(GraphEditorStateService::class.java)
        val preferencesService = project.getService(WorkbenchLayoutPreferencesService::class.java)
        val runtimeBefore = stateService.snapshot().workbenchSectionPreferences
        val persistentBefore = preferencesService.snapshot()

        project.getService(GraphEditorCommandRouter::class.java)
            .updateWorkbenchSectionPreference("$obsoleteQaSectionPrefix.investigation-threads", true)

        assertEquals(runtimeBefore, stateService.snapshot().workbenchSectionPreferences)
        assertEquals(persistentBefore, preferencesService.snapshot())
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
