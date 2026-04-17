package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals

class CheckWritableDraftToolTest : BasePlatformTestCase() {
    fun testRejectsDraftWithoutContentAndEditScopes() {
        val result = CheckWritableDraftTool(ValidationToolFacade()).invoke(
            input = mapOf(
                "draft" to GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "method:upload-file",
                    title = "invalid draft",
                    targetPath = "src/main/java/com/example/CommonController.java",
                ),
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = GraphEditorStateService.Snapshot(),
                artifactStore = com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals(false, result.payload["writable"])
    }

    fun testAcceptsNewFileDraftWithContent() {
        val result = CheckWritableDraftTool(ValidationToolFacade()).invoke(
            input = mapOf(
                "draft" to GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "method:upload-file",
                    title = "new draft",
                    targetPath = "src/main/java/com/example/UploadDraft.java",
                    content = "class UploadDraft {}",
                ),
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = GraphEditorStateService.Snapshot(),
                artifactStore = com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals(true, result.payload["writable"])
    }
}
