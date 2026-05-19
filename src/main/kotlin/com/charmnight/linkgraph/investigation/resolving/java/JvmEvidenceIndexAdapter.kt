package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.intellij.openapi.project.Project

class JvmEvidenceIndexAdapter(
    private val indexProvider: (Project) -> ArchitectureGraphIndex = { project ->
        project.architectureIndexRuntime().index()
    },
) {
    fun buildIndex(project: Project): ArchitectureGraphIndex {
        return indexProvider(project)
    }
}
