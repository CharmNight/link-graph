package com.charmnight.linkgraph.application.port

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditResult
import com.charmnight.linkgraph.llm.tools.ToolGraphSnapshot
import com.charmnight.linkgraph.model.GraphDocument

fun interface EditorSnapshotProvider {
    fun snapshot(): WorkflowEditorSnapshot
}

fun interface ApplicationSnapshotProvider {
    fun snapshot(): com.charmnight.linkgraph.application.model.ApplicationSnapshot
}

fun interface ToolGraphSnapshotProvider {
    fun snapshot(): ToolGraphSnapshot
}

interface WorkspaceGraphCommitter {
    fun commitWorkspaceGraph(
        expectedSnapshotRevision: Long? = null,
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
        syncBrowser: Boolean = true,
        graphEditTransaction: GraphEditTransaction? = null,
    ): Boolean
}

fun interface GraphEditRequestExecutor {
    fun apply(request: GraphEditRequest): GraphEditResult
}
