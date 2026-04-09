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

class HttpFeignResolverTest : BasePlatformTestCase() {
    fun testResolvesFeignClientToHttpEndpointAndWorkspaceProvider() {
        loadFixture("http/FeignOrderClient.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.http.OrderGatewayService",
            "loadOrder",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val serviceMethod = result.document.nodes.single { it.title == "OrderGatewayService.loadOrder" }
        val feignNode = result.document.nodes.single { node ->
            node.type == NodeType.FEIGN_CLIENT &&
                node.metadata["clientClass"] == "com.charmnight.linkgraph.fixtures.http.FeignOrderClient" &&
                node.metadata["httpMethod"] == "GET" &&
                node.metadata["path"] == "/orders/{id}"
        }
        val endpointNode = result.document.nodes.single { node ->
            node.type == NodeType.HTTP_ENDPOINT &&
                node.metadata["httpMethod"] == "GET" &&
                node.metadata["path"] == "/orders/{id}"
        }
        val providerMethod = result.document.nodes.single { it.title == "OrderProviderController.getOrder" }

        assertEquals("FEIGN_CLIENT_METHOD", feignNode.sourceKind)
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.USES_PROXY &&
                    edge.fromNodeId == serviceMethod.id &&
                    edge.toNodeId == feignNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.ROUTES_TO &&
                    edge.fromNodeId == feignNode.id &&
                    edge.toNodeId == endpointNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.ROUTES_TO &&
                    edge.fromNodeId == providerMethod.id &&
                    edge.toNodeId == endpointNode.id
            },
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
