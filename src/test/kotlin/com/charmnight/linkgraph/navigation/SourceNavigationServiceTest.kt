package com.charmnight.linkgraph.navigation

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.GraphBinding
import com.charmnight.linkgraph.model.GraphConfidence
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.SourceNavigationAnchors
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

    fun testResolveUsesSourceVirtualFileUrlMetadataWhenLocationMissing() {
        val virtualFile = myFixture.addFileToProject(
            "src/main/java/com/example/VirtualUrlTarget.java",
            "package com.example; class VirtualUrlTarget {}",
        ).virtualFile
        val service = project.getService(SourceNavigationService::class.java)

        val target = service.resolve(
            GraphNode(
                id = "jvm:class:virtual-url-target",
                type = NodeType.CLASS,
                title = "VirtualUrlTarget",
                signature = "com.example.VirtualUrlTarget",
                binding = GraphBinding.CODE_BOUND,
                confidence = GraphConfidence.VERIFIED,
                metadata = mapOf(
                    "source.filePath" to "display/VirtualUrlTarget.java",
                    "source.virtualFileUrl" to virtualFile.url,
                    "source.startLine" to "1",
                ),
            ),
        )

        assertNotNull(target)
        assertEquals(virtualFile.url, target!!.virtualFileUrl)
        assertEquals(1, target.line)
    }

    fun testResolveUsesSourceNavigationAnchorForAggregateNode() {
        val virtualFile = myFixture.addFileToProject(
            "src/main/java/com/example/orders/OrderService.java",
            "package com.example.orders; class OrderService {}",
        ).virtualFile
        val service = project.getService(SourceNavigationService::class.java)

        val target = service.resolve(
            GraphNode(
                id = "component:orders",
                type = NodeType.COMPONENT,
                title = "orders",
                binding = GraphBinding.CODE_BOUND,
                confidence = GraphConfidence.VERIFIED,
                metadata = SourceNavigationAnchors.metadata(
                    nodeId = "class:com.example.orders.OrderService",
                    filePath = "display/OrderService.java",
                    virtualFileUrl = virtualFile.url,
                    startLine = 1,
                    reason = "architecture-member-class:component:orders",
                ),
            ),
        )

        assertNotNull(target)
        assertEquals(virtualFile.url, target!!.virtualFileUrl)
        assertEquals(1, target.line)
    }

    fun testResolveClassLikeSignatureFallbackForInterfaceNode() {
        val virtualFile = myFixture.addFileToProject(
            "src/main/java/com/example/Api.java",
            "package com.example; interface Api {}",
        ).virtualFile
        myFixture.configureFromExistingVirtualFile(virtualFile)
        val service = project.getService(SourceNavigationService::class.java)

        val target = service.resolve(
            GraphNode(
                id = "interface:com.example.Api",
                type = NodeType.INTERFACE,
                title = "Api",
                signature = "com.example.Api",
                binding = GraphBinding.CODE_BOUND,
                confidence = GraphConfidence.VERIFIED,
            ),
        )

        assertNotNull(target)
        assertEquals(virtualFile.url, target!!.virtualFileUrl)
    }
}
