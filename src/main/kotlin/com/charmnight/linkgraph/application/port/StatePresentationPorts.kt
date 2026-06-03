package com.charmnight.linkgraph.application.port

import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink

interface GraphEditorPresentationProvider {
    fun editorSnapshotProvider(): EditorSnapshotProvider
    fun applicationSnapshotProvider(): ApplicationSnapshotProvider
    fun toolGraphSnapshotProvider(): ToolGraphSnapshotProvider
    fun workspaceGraphCommitter(): WorkspaceGraphCommitter
    fun eventSink(): GraphEditorApplicationEventSink
}
