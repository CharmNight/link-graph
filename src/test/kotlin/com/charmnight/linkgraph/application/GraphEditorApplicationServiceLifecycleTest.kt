package com.charmnight.linkgraph.application

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GraphEditorApplicationServiceLifecycleTest : BasePlatformTestCase() {
    fun testDisposeDoesNotInitializeUnusedWorkflowComposition() {
        val service = GraphEditorApplicationService(project)
        val workflowsDelegateField = GraphEditorApplicationService::class.java
            .getDeclaredField("workflowsDelegate")
            .apply { isAccessible = true }
        val workflowsDelegate = workflowsDelegateField.get(service) as Lazy<*>

        assertFalse(workflowsDelegate.isInitialized())
        service.dispose()
        assertFalse(
            workflowsDelegate.isInitialized(),
            "dispose 不得构造从未使用的工作流对象图",
        )
        assertFailsWith<IllegalStateException> { service.commandDispatcher }
    }
}
