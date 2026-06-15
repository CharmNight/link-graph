package com.charmnight.linkgraph.testing

import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.ui.GraphEditorCommandRouter
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.project.Project
import com.intellij.testFramework.registerServiceInstance

internal fun Project.registerGraphEditorApplicationServicesForTest() {
    registerServiceInstance(GraphEditorStateService::class.java, GraphEditorStateService())
    registerServiceInstance(LinkGraphProjectTestOverrides::class.java, LinkGraphProjectTestOverrides())
    registerServiceInstance(GraphEditorApplicationService::class.java, GraphEditorApplicationService(this))
    registerServiceInstance(GraphEditorCommandRouter::class.java, GraphEditorCommandRouter(this))
}

internal fun Project.graphEditorApplicationServiceForTest(): GraphEditorApplicationService {
    return getService(GraphEditorApplicationService::class.java)
}
