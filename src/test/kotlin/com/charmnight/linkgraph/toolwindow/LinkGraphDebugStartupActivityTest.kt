package com.charmnight.linkgraph.toolwindow

import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.testing.registerGraphEditorApplicationServicesForTest
import com.charmnight.linkgraph.toolwindow.debug.LinkGraphDebugAutomationCoordinator
import com.charmnight.linkgraph.toolwindow.debug.LinkGraphDebugAutomationRequest
import com.charmnight.linkgraph.toolwindow.debug.LinkGraphDebugStartupActivity
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance
import java.util.concurrent.atomic.AtomicReference

class LinkGraphDebugStartupActivityTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerGraphEditorApplicationServicesForTest()
        project.registerServiceInstance(
            LinkGraphDebugAutomationCoordinator::class.java,
            LinkGraphDebugAutomationCoordinator(project),
        )

        val toolWindowManager = ToolWindowManager.getInstance(project)
        if (toolWindowManager.getToolWindow(LinkGraphToolWindowFactory.TOOL_WINDOW_ID) == null) {
            val toolWindow = toolWindowManager.registerToolWindow(
                RegisterToolWindowTask.notClosable(
                    LinkGraphToolWindowFactory.TOOL_WINDOW_ID,
                    ToolWindowAnchor.RIGHT,
                ),
            )
            LinkGraphToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
    }

    fun testAutoOpensToolWindowOnlyWhenDebugFlagIsEnabled() {
        val disabled = LinkGraphDebugStartupActivity(
            requestProvider = { LinkGraphDebugAutomationRequest() },
        )
        disabled.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val disabledSnapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertFalse(disabledSnapshot.toolWindowOpenRequested)

        val enabled = LinkGraphDebugStartupActivity(
            requestProvider = { LinkGraphDebugAutomationRequest(autoOpenToolWindow = true) },
        )
        enabled.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val enabledSnapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertTrue(enabledSnapshot.toolWindowOpenRequested)
    }

    fun testTriggersDebugOnlyStartupActionForNonOpenAutomationRequests() {
        val capturedRequest = AtomicReference<LinkGraphDebugAutomationRequest?>()
        val activity = LinkGraphDebugStartupActivity(
            requestProvider = { LinkGraphDebugAutomationRequest(autoRequestPlan = true) },
            startupAction = { _, request -> capturedRequest.set(request) },
        )

        activity.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val request = capturedRequest.get()
        assertNotNull("Expected debug startup to dispatch non-open automation requests", request)
        assertTrue(request!!.autoRequestPlan)
    }

    fun testTriggersDebugOnlyStartupActionForArchitectureGraphRequest() {
        val capturedRequest = AtomicReference<LinkGraphDebugAutomationRequest?>()
        val activity = LinkGraphDebugStartupActivity(
            requestProvider = { LinkGraphDebugAutomationRequest(autoRequestArchitectureGraph = true) },
            startupAction = { _, request -> capturedRequest.set(request) },
        )

        activity.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val request = capturedRequest.get()
        assertNotNull("Expected debug startup to dispatch architecture graph automation requests", request)
        assertTrue(request!!.autoRequestArchitectureGraph)
    }

    fun testTriggersDebugOnlyStartupActionForClassDiagramRequest() {
        val capturedRequest = AtomicReference<LinkGraphDebugAutomationRequest?>()
        val activity = LinkGraphDebugStartupActivity(
            requestProvider = { LinkGraphDebugAutomationRequest(autoRequestClassDiagram = true) },
            startupAction = { _, request -> capturedRequest.set(request) },
        )

        activity.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val request = capturedRequest.get()
        assertNotNull("Expected debug startup to dispatch class diagram automation requests", request)
        assertTrue(request!!.autoRequestClassDiagram)
    }

    fun testTriggersDebugOnlyStartupActionForScopedClassDiagramRequest() {
        val classNodeId = stableJvmId("class", "org.springframework.beans.BeanInstantiationException")
        val capturedRequest = AtomicReference<LinkGraphDebugAutomationRequest?>()
        val activity = LinkGraphDebugStartupActivity(
            requestProvider = { LinkGraphDebugAutomationRequest(autoRequestClassDiagramScopeNodeId = classNodeId) },
            startupAction = { _, request -> capturedRequest.set(request) },
        )

        activity.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val request = capturedRequest.get()
        assertNotNull("Expected debug startup to dispatch scoped class diagram automation requests", request)
        assertEquals(classNodeId, request!!.autoRequestClassDiagramScopeNodeId)
    }

    fun testParsesScopedClassDiagramRequestFromEnvironment() {
        val className = "org.springframework.beans.BeanInstantiationException"
        val classNodeId = stableJvmId("class", className)

        val fromQualifiedName = LinkGraphDebugAutomationRequest.fromEnvironment(
            mapOf(LinkGraphDebugAutomationRequest.DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_SCOPE_ENV to className),
        )
        val fromNodeId = LinkGraphDebugAutomationRequest.fromEnvironment(
            mapOf(LinkGraphDebugAutomationRequest.DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_SCOPE_ENV to classNodeId),
        )

        assertEquals(classNodeId, fromQualifiedName.autoRequestClassDiagramScopeNodeId)
        assertTrue(fromQualifiedName.autoRequestClassDiagram)
        assertEquals(classNodeId, fromNodeId.autoRequestClassDiagramScopeNodeId)
        assertTrue(fromNodeId.autoRequestClassDiagram)
    }

    fun testTriggersDebugOnlyStartupActionForClassUsageRequest() {
        val classNodeId = stableJvmId("class", "org.springframework.beans.AbstractNestablePropertyAccessor")
        val capturedRequest = AtomicReference<LinkGraphDebugAutomationRequest?>()
        val activity = LinkGraphDebugStartupActivity(
            requestProvider = { LinkGraphDebugAutomationRequest(autoRequestClassUsageTargetNodeId = classNodeId) },
            startupAction = { _, request -> capturedRequest.set(request) },
        )

        activity.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val request = capturedRequest.get()
        assertNotNull("Expected debug startup to dispatch class usage automation requests", request)
        assertEquals(classNodeId, request!!.autoRequestClassUsageTargetNodeId)
    }

    fun testParsesClassUsageRequestFromEnvironment() {
        val className = "org.springframework.beans.AbstractNestablePropertyAccessor"
        val classNodeId = stableJvmId("class", className)

        val fromQualifiedName = LinkGraphDebugAutomationRequest.fromEnvironment(
            mapOf(LinkGraphDebugAutomationRequest.DEBUG_AUTO_REQUEST_CLASS_USAGE_TARGET_ENV to className),
        )
        val fromNodeId = LinkGraphDebugAutomationRequest.fromEnvironment(
            mapOf(LinkGraphDebugAutomationRequest.DEBUG_AUTO_REQUEST_CLASS_USAGE_TARGET_ENV to classNodeId),
        )

        assertEquals(classNodeId, fromQualifiedName.autoRequestClassUsageTargetNodeId)
        assertEquals(className, fromQualifiedName.autoRequestClassUsageTargetQualifiedName)
        assertEquals(classNodeId, fromNodeId.autoRequestClassUsageTargetNodeId)
        assertEquals(null, fromNodeId.autoRequestClassUsageTargetQualifiedName)
    }

    fun testTriggersDebugOnlyStartupActionForSourceNavigationRequest() {
        val capturedRequest = AtomicReference<LinkGraphDebugAutomationRequest?>()
        val activity = LinkGraphDebugStartupActivity(
            requestProvider = { LinkGraphDebugAutomationRequest(autoRequestSourceNavigation = true) },
            startupAction = { _, request -> capturedRequest.set(request) },
        )

        activity.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val request = capturedRequest.get()
        assertNotNull("Expected debug startup to dispatch source navigation automation requests", request)
        assertTrue(request!!.autoRequestSourceNavigation)
    }
}
