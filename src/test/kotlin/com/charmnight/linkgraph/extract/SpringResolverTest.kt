package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.testing.addJavaFixture
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
                    result.document.nodes.any { node ->
                        node.id == edge.toNodeId &&
                            node.type == NodeType.FLOW_ACTION &&
                            node.title.contains("service.findOrder(id)")
                    }
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    result.document.nodes.any { node ->
                        node.id == edge.fromNodeId &&
                            node.type == NodeType.FLOW_ACTION &&
                            node.title.contains("service.findOrder(id)")
                    } &&
                    edge.toNodeId == nodesByTitle.getValue("DefaultOrderService.findOrder").id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == nodesByTitle.getValue("DefaultOrderService.findOrder").id &&
                    result.document.nodes.any { node ->
                        node.id == edge.toNodeId &&
                            node.type == NodeType.FLOW_ACTION &&
                            node.title.contains("repository.loadOrder(id)")
                    }
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    result.document.nodes.any { node ->
                        node.id == edge.fromNodeId &&
                            node.type == NodeType.FLOW_ACTION &&
                            node.title.contains("repository.loadOrder(id)")
                    } &&
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

    fun testDoesNotLeaveStandaloneSpringBeanClassNodeWhenNoDependencyEdgeExists() {
        loadFixture("spring/SpringBeanWithoutDependencies.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.spring.SpringBeanWithoutDependencies",
            "normalize",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        assertTrue(
            result.document.nodes.none { node ->
                node.type == NodeType.CLASS && node.title == "SpringBeanWithoutDependencies"
            },
            "expected no standalone spring bean class node when the class has no dependency edges",
        )
    }

    private fun loadFixture(relativePath: String) {
        myFixture.addJavaFixture(relativePath)
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
