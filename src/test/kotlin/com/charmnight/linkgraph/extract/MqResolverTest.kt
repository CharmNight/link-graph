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

class MqResolverTest : BasePlatformTestCase() {
    fun testResolvesMqProducerTopicAndConsumerHandler() {
        loadFixture("mq/OrderCreatedProducer.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.mq.OrderCreatedProducer",
            "publish",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val producerMethod = result.document.nodes.single { it.title == "OrderCreatedProducer.publish" }
        val topicNode = result.document.nodes.single { node ->
            node.type == NodeType.MQ_TOPIC &&
                node.metadata["topic"] == "order.created"
        }
        val consumerNode = result.document.nodes.single { node ->
            node.type == NodeType.MQ_CONSUMER &&
                node.metadata["topic"] == "order.created"
        }
        val consumerMethod = result.document.nodes.single { it.title == "OrderCreatedConsumer.onOrderCreated" }

        assertEquals("MQ_TOPIC", topicNode.sourceKind)
        assertEquals("MQ_CONSUMER_METHOD", consumerNode.sourceKind)
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.PUBLISHES_TO &&
                    edge.fromNodeId == producerMethod.id &&
                    edge.toNodeId == topicNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CONSUMES_FROM &&
                    edge.fromNodeId == consumerNode.id &&
                    edge.toNodeId == topicNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.ROUTES_TO &&
                    edge.fromNodeId == consumerNode.id &&
                    edge.toNodeId == consumerMethod.id
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
