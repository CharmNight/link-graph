package com.charmnight.linkgraph.source

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue

class IdeSourceContentResolverTest : BasePlatformTestCase() {
    fun testBoundsOversizedUnsavedDocumentText() {
        val psiFile = myFixture.configureByText("Large.java", "class Large {}")
        val virtualFile = requireNotNull(psiFile.virtualFile)
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(virtualFile))
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("汉".repeat(800_000))
        }

        val content = requireNotNull(IdeSourceContentResolver(project).readByVirtualFileUrl(virtualFile.url))

        assertTrue(content.truncated)
        assertTrue(content.utf8ByteCount <= SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES)
        assertTrue(content.diagnostic.orEmpty().contains("SOURCE_CONTENT_TRUNCATED"))
    }
}
