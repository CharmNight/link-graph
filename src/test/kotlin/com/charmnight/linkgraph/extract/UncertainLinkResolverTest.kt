package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.testing.addJavaFixture
import com.charmnight.linkgraph.testing.addResourceFixture
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue

class UncertainLinkResolverTest : BasePlatformTestCase() {
    fun testKeepsReflectionSpiAndProxyLinksVisible() {
        loadJavaFixture("uncertain/ReflectionInvoker.java")
        loadResourceFixture("uncertain/META-INF/services/com.charmnight.linkgraph.fixtures.uncertain.PluginHandler", "META-INF/services/com.charmnight.linkgraph.fixtures.uncertain.PluginHandler")

        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.uncertain.ReflectionInvoker",
            "render",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val invokeReflectively = result.document.nodes.single { it.title == "ReflectionInvoker.invokeReflectively" }
        val callThroughProxy = result.document.nodes.single { it.title == "ReflectionInvoker.callThroughProxy" }
        val reflectionTarget = result.document.nodes.single { it.title == "ReflectionTarget.handle" }
        val proxiedImplementation = result.document.nodes.single { it.title == "ProxiedHandlerImpl.handle" }
        val alphaProvider = result.document.nodes.single { it.type == NodeType.CLASS && it.title == "AlphaPluginHandler" }
        val betaProvider = result.document.nodes.single { it.type == NodeType.CLASS && it.title == "BetaPluginHandler" }

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.REFLECTS_TO &&
                    edge.fromNodeId == invokeReflectively.id &&
                    edge.toNodeId == reflectionTarget.id &&
                    edge.certainty == Certainty.RULE_INFERRED
            },
        )

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.SPI_RESOLVES_TO &&
                    edge.toNodeId == alphaProvider.id &&
                    edge.certainty == Certainty.RULE_INFERRED
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.SPI_RESOLVES_TO &&
                    edge.toNodeId == betaProvider.id &&
                    edge.certainty == Certainty.RULE_INFERRED
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.USES_PROXY &&
                    edge.fromNodeId == callThroughProxy.id &&
                    edge.certainty == Certainty.RULE_INFERRED
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.toNodeId == proxiedImplementation.id
            },
        )
    }

    fun testBindsConfigAndDocsToTargets() {
        loadJavaFixture("uncertain/ReflectionInvoker.java")
        loadResourceFixture("uncertain/application.yml")
        loadResourceFixture("uncertain/uncertain-config.xml")
        loadResourceFixture("uncertain/uncertain-links.md")

        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.uncertain.ReflectionInvoker",
            "render",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val reflectionTarget = result.document.nodes.single { it.title == "ReflectionTarget.handle" }
        val docPage = result.document.nodes.single { it.type == NodeType.DOC_PAGE && it.title == "uncertain-links.md" }

        assertTrue(
            result.document.nodes.any { node ->
                node.type == NodeType.CONFIG_ITEM &&
                    node.metadata["key"] == "uncertain.target.method" &&
                    node.metadata["value"] == "com.charmnight.linkgraph.fixtures.uncertain.ReflectionTarget#handle"
            },
        )
        assertTrue(
            result.document.nodes.any { node ->
                node.type == NodeType.CONFIG_ITEM &&
                    node.metadata["key"] == "uncertain.target.alias" &&
                    node.metadata["value"] == "com.charmnight.linkgraph.fixtures.uncertain.ReflectionTarget#handle"
            },
        )
        assertTrue(
            result.document.edges.count { edge ->
                edge.type == EdgeType.BINDS_CONFIG &&
                    edge.toNodeId == reflectionTarget.id
            } >= 2,
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.LINKS_DOC &&
                    edge.fromNodeId == docPage.id &&
                    edge.toNodeId == reflectionTarget.id
            },
        )
    }

    fun testKeepsAopAdviceVisibleAsProxyLink() {
        loadJavaFixture("aop/AopOrderFlow.java")

        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.aop.OrderService",
            "place",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val targetMethod = result.document.nodes.single { it.title == "OrderService.place" }
        val aroundAdvice = result.document.nodes.single { it.title == "TracingAspect.wrapPlace" }
        val beforeAdvice = result.document.nodes.single { it.title == "TracingAspect.beforeAudited" }

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.USES_PROXY &&
                    edge.fromNodeId == aroundAdvice.id &&
                    edge.toNodeId == targetMethod.id &&
                    edge.certainty == Certainty.RULE_INFERRED &&
                    edge.uncertainty?.reason?.contains("AOP", ignoreCase = true) == true
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.USES_PROXY &&
                    edge.fromNodeId == beforeAdvice.id &&
                    edge.toNodeId == targetMethod.id &&
                    edge.certainty == Certainty.RULE_INFERRED
            },
        )
    }

    private fun loadJavaFixture(relativePath: String) {
        myFixture.addJavaFixture(relativePath)
    }

    private fun loadResourceFixture(relativePath: String, projectRelativePath: String = "com/charmnight/linkgraph/fixtures/$relativePath") {
        myFixture.addResourceFixture(relativePath, projectRelativePath)
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
