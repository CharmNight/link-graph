package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

class JvmEvidenceIndexAdapter(
    private val indexProvider: (Project) -> ArchitectureGraphIndex = { project ->
        project.architectureIndexRuntime().index()
    },
    private val currentIndexProvider: (Project) -> ArchitectureGraphIndex? = { project ->
        project.architectureIndexRuntime().currentIndex()
    },
) {
    fun acquireIndex(project: Project): ArchitectureGraphIndex {
        val application = ApplicationManager.getApplication()
        if (application.isReadAccessAllowed) {
            return currentIndexProvider(project)
                ?: error("Cannot build ArchitectureGraphIndex inside a synchronous read action without a cached current index.")
        }
        return indexProvider(project)
    }

    fun buildIndexOutsideReadAction(project: Project): ArchitectureGraphIndex {
        val application = ApplicationManager.getApplication()
        if (!application.isReadAccessAllowed) {
            return indexProvider(project)
        }
        return application.executeOnPooledThread<ArchitectureGraphIndex> {
            indexProvider(project)
        }.get()
    }

    fun buildIndex(project: Project): ArchitectureGraphIndex {
        return indexProvider(project)
    }
}
