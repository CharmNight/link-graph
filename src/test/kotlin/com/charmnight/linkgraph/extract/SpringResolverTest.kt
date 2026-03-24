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
import kotlin.test.assertTrue

class SpringResolverTest : BasePlatformTestCase() {
    fun testExtractsSpringControllerServiceRepositoryChain() {
        loadFixture("spring/SpringControllerServiceRepo.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.spring.OrderController",
            "getOrder",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val endpoint = nodesByTitle["GET /orders/{id}"] ?: error("missing endpoint node")
        assertEquals(NodeType.HTTP_ENDPOINT, endpoint.type)
        assertEquals("/orders/{id}", endpoint.metadata["path"])
        assertEquals("GET", endpoint.metadata["httpMethod"])

        assertTrue(nodesByTitle.containsKey("OrderController.getOrder"))
        assertTrue(nodesByTitle.containsKey("DefaultOrderService.findOrder"))
        assertTrue(nodesByTitle.containsKey("JdbcOrderRepository.loadOrder"))

        val classNodes = result.document.nodes
            .filter { it.type == NodeType.CLASS }
            .associateBy { it.title }

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.ROUTES_TO &&
                    edge.fromNodeId == nodesByTitle.getValue("OrderController.getOrder").id &&
                    edge.toNodeId == endpoint.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == nodesByTitle.getValue("OrderController.getOrder").id &&
                    edge.toNodeId == nodesByTitle.getValue("DefaultOrderService.findOrder").id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == nodesByTitle.getValue("DefaultOrderService.findOrder").id &&
                    edge.toNodeId == nodesByTitle.getValue("JdbcOrderRepository.loadOrder").id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.INJECT &&
                    edge.fromNodeId == classNodes.getValue("OrderController").id &&
                    edge.toNodeId == classNodes.getValue("DefaultOrderService").id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.INJECT &&
                    edge.fromNodeId == classNodes.getValue("DefaultOrderService").id &&
                    edge.toNodeId == classNodes.getValue("JdbcOrderRepository").id
            },
        )
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
