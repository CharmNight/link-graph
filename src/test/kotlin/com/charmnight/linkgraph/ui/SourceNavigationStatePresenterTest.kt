package com.charmnight.linkgraph.ui
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceNavigationStatePresenterTest {
    @Test
    fun mapsNavigationOpenedNotFoundAndFailedState() {
        val stateService = GraphEditorStateService()
        val presenter = SourceNavigationStatePresenter(stateService)

        presenter.presentNavigationOpened(
            nodeId = "node-1",
            targetPath = "src/App.kt",
            line = 10,
            column = 2,
            title = "App",
        )

        val opened = stateService.snapshot()
        assertEquals(SourceNavigationPhase.SUCCEEDED, opened.sourceNavigationState.phase)
        assertEquals("src/App.kt", opened.sourceNavigationState.targetPath)
        assertEquals(ApplicationFeedbackLevel.SUCCESS, opened.operationFeedback?.level)

        presenter.presentNavigationFailed("node-2", "boom")
        val failed = stateService.snapshot()
        assertEquals(SourceNavigationPhase.FAILED, failed.sourceNavigationState.phase)
        assertEquals("boom", failed.sourceNavigationState.errorMessage)
    }
}
