package com.charmnight.linkgraph.ui

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphEditorTransportRenderSchedulerTest {
    @Test
    fun rendersThroughBackgroundExecutorBeforeDispatchingOnUiExecutor() {
        val renderExecutor = ManualExecutor()
        val uiExecutor = ManualUiExecutor()
        val scheduler = GraphEditorTransportRenderScheduler(
            renderExecutor = renderExecutor,
            dispatchExecutor = uiExecutor::enqueue,
        )
        var rendered = false
        val dispatchedScripts = mutableListOf<String>()

        scheduler.schedule(
            revision = 2,
            render = {
                rendered = true
                "script-2"
            },
            dispatch = { script -> dispatchedScripts += script },
        )

        assertFalse(rendered)
        assertEquals(emptyList(), dispatchedScripts)

        renderExecutor.runNext()
        assertTrue(rendered)
        assertEquals(emptyList(), dispatchedScripts)

        uiExecutor.runNext()
        assertEquals(listOf("script-2"), dispatchedScripts)
    }

    @Test
    fun skipsOlderRevisionBeforeRenderingWhenNewerRevisionIsAlreadyQueued() {
        val renderExecutor = ManualExecutor()
        val uiExecutor = ManualUiExecutor()
        val scheduler = GraphEditorTransportRenderScheduler(
            renderExecutor = renderExecutor,
            dispatchExecutor = uiExecutor::enqueue,
        )
        val renderedRevisions = mutableListOf<Long>()
        val dispatchedScripts = mutableListOf<String>()

        scheduler.schedule(
            revision = 2,
            render = {
                renderedRevisions += 2
                "script-2"
            },
            dispatch = { script -> dispatchedScripts += script },
        )
        scheduler.schedule(
            revision = 3,
            render = {
                renderedRevisions += 3
                "script-3"
            },
            dispatch = { script -> dispatchedScripts += script },
        )

        renderExecutor.runAll()
        uiExecutor.runAll()

        assertEquals(listOf(3L), renderedRevisions)
        assertEquals(listOf("script-3"), dispatchedScripts)
    }

    @Test
    fun dropsOlderRevisionAtDispatchBoundaryWhenNewerRevisionArrivesDuringRender() {
        val renderExecutor = ManualExecutor()
        val uiExecutor = ManualUiExecutor()
        val scheduler = GraphEditorTransportRenderScheduler(
            renderExecutor = renderExecutor,
            dispatchExecutor = uiExecutor::enqueue,
        )
        val dispatchedScripts = mutableListOf<String>()

        scheduler.schedule(
            revision = 2,
            render = { "script-2" },
            dispatch = { script -> dispatchedScripts += script },
        )
        renderExecutor.runNext()
        scheduler.schedule(
            revision = 3,
            render = { "script-3" },
            dispatch = { script -> dispatchedScripts += script },
        )

        uiExecutor.runNext()
        renderExecutor.runNext()
        uiExecutor.runNext()

        assertEquals(listOf("script-3"), dispatchedScripts)
    }

    private class ManualExecutor : Executor {
        private val actions = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            actions += command
        }

        fun runNext() {
            actions.removeFirst().run()
        }

        fun runAll() {
            while (actions.isNotEmpty()) {
                runNext()
            }
        }
    }

    private class ManualUiExecutor {
        private val actions = ArrayDeque<() -> Unit>()

        fun enqueue(action: () -> Unit) {
            actions += action
        }

        fun runNext() {
            actions.removeFirst().invoke()
        }

        fun runAll() {
            while (actions.isNotEmpty()) {
                runNext()
            }
        }
    }
}
