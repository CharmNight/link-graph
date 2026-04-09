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

class DubboResolverTest : BasePlatformTestCase() {
    fun testResolvesDubboConsumerProxyAndProviderImplementation() {
        loadFixture("dubbo/OrderDubboService.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.dubbo.OrderDubboFacade",
            "loadOrder",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val consumerMethod = result.document.nodes.single { it.title == "OrderDubboFacade.loadOrder" }
        val providerMethod = result.document.nodes.single { it.title == "OrderDubboServiceImpl.fetchOrder" }
        val contractClass = result.document.nodes.single { it.type == NodeType.CLASS && it.title == "OrderDubboService" }
        val implementationClass = result.document.nodes.single { it.type == NodeType.CLASS && it.title == "OrderDubboServiceImpl" }
        val serviceNode = result.document.nodes.single { node ->
            node.type == NodeType.DUBBO_SERVICE &&
                node.metadata["serviceInterface"] == "com.charmnight.linkgraph.fixtures.dubbo.OrderDubboService"
        }

        assertEquals("DUBBO_REFERENCE", serviceNode.sourceKind)
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.USES_PROXY &&
                    edge.fromNodeId == consumerMethod.id &&
                    edge.toNodeId == serviceNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == consumerMethod.id &&
                    result.document.nodes.any { node ->
                        node.id == edge.toNodeId &&
                            node.type == NodeType.FLOW_ACTION &&
                            node.title.contains("orderDubboService.fetchOrder(id)")
                    }
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    result.document.nodes.any { node ->
                        node.id == edge.fromNodeId &&
                            node.type == NodeType.FLOW_ACTION &&
                            node.title.contains("orderDubboService.fetchOrder(id)")
                    } &&
                    edge.toNodeId == providerMethod.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.IMPLEMENTS &&
                    edge.fromNodeId == implementationClass.id &&
                    edge.toNodeId == contractClass.id
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
