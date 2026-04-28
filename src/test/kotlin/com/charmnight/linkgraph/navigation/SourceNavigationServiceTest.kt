package com.charmnight.linkgraph.navigation

import com.charmnight.linkgraph.testing.*

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceNavigationServiceTest : BasePlatformTestCase() {
    fun testNavigateToProjectPathOpensProjectRelativeFile() {
        val virtualFile = myFixture.addFileToProject(
            "src/main/java/com/example/InProjectDraft.java",
            "package com.example; class InProjectDraft {}",
        ).virtualFile

        val target = project.getService(SourceNavigationService::class.java)
            .navigateToProjectPath("src/main/java/com/example/InProjectDraft.java")

        assertNotNull(target)
        assertEquals(virtualFile.path, target!!.filePath)
    }

    fun testNavigateToProjectPathRejectsProjectEscapeTraversal() {
        val service = project.getService(SourceNavigationService::class.java)

        val target = service.navigateToProjectPath("../../../../tmp/escape-draft.java")

        assertNull(target)
    }

    fun testNavigateToProjectPathRejectsAbsoluteFileOutsideProjectRoot() {
        val externalFile = Files.createTempFile("link-graph-external-draft", ".java")
        Files.writeString(externalFile, "class ExternalDraft {}")

        val target = project.getService(SourceNavigationService::class.java)
            .navigateToProjectPath(externalFile.toString())

        assertNull(target)
    }
}
