package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaResolverTest : BasePlatformTestCase() {
    fun testExtractsCallerCalleeGraphAndMethodMetadata() {
        loadFixture("simple/SimpleCallChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.SimpleCallChain",
            "load",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val loadNode = nodesByTitle["SimpleCallChain.load"] ?: error("missing load node")
        assertEquals(NodeType.METHOD, loadNode.type)
        assertEquals(
            "SimpleCallChain.load(java.lang.String):java.lang.String",
            loadNode.signature,
        )
        assertEquals(listOf("java.lang.String"), loadNode.inputs)
        assertEquals(listOf("java.lang.String"), loadNode.outputs)
        assertEquals("Loads an order summary.", loadNode.doc)
        assertNotNull(loadNode.location)
        assertTrue(loadNode.location!!.contains("SimpleCallChain.java:"))

        assertTrue(nodesByTitle.containsKey("SimpleCallChain.sanitize"))
        assertTrue(nodesByTitle.containsKey("OrderGatewayImpl.fetch"))
        assertTrue(nodesByTitle.containsKey("OrderGatewayImpl.repositoryFetch"))

        val edges = result.document.edges
        assertTrue(
            edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == loadNode.id &&
                    edge.toNodeId == nodesByTitle.getValue("SimpleCallChain.sanitize").id
            },
        )
        assertTrue(
            edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == loadNode.id &&
                    edge.toNodeId == nodesByTitle.getValue("OrderGatewayImpl.fetch").id
            },
        )
    }

    fun testResolvesInterfaceImplementationsAndBuildsSimplifiedMethodFlow() {
        loadFixture("simple/SimpleCallChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.SimpleCallChain",
            "branchy",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodes = result.document.nodes
        val branchyNode = nodes.single { it.title == "SimpleCallChain.branchy" }
        val implNode = nodes.single { it.title == "OrderGatewayImpl" && it.type == NodeType.CLASS }
        val interfaceNode = nodes.single { it.title == "OrderGateway" && it.type == NodeType.CLASS }

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.IMPLEMENTS &&
                    edge.fromNodeId == implNode.id &&
                    edge.toNodeId == interfaceNode.id
            },
        )

        val flow = branchyNode.metadata["flow"]
        assertNotNull(flow)
        assertTrue(flow!!.contains("if (state == null)"))
        assertTrue(flow.contains("switch (state)"))
        assertTrue(flow.contains("case \"NEW\""))
        assertTrue(flow.contains("catch (IllegalArgumentException)"))
    }

    private fun loadFixture(relativePath: String) {
        val fixturePath = Path.of("src/testFixtures/java/com/charmnight/linkgraph/fixtures/$relativePath")
        val projectRelativePath = "com/charmnight/linkgraph/fixtures/$relativePath"
        myFixture.addFileToProject(projectRelativePath, Files.readString(fixturePath))
    }

    private fun findMethod(className: String, methodName: String): PsiMethod {
        val psiClass = findClass(className)
        return psiClass.findMethodsByName(methodName, false).single()
    }

    private fun findClass(className: String): PsiClass {
        return JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))
            ?: error("Class not found: $className")
    }
}
