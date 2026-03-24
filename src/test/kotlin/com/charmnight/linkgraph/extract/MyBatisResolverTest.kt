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

class MyBatisResolverTest : BasePlatformTestCase() {
    fun testMapsMapperMethodToXmlSqlStatement() {
        loadJavaFixture("mybatis/UserMapper.java")
        loadResourceFixture("mybatis/UserMapper.xml")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.mybatis.UserQueryService",
            "loadUser",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val mapperMethod = result.document.nodes.single { it.title == "UserMapper.selectUser" }
        val sqlNode = result.document.nodes.single { node ->
            node.type == NodeType.SQL &&
                node.metadata["namespace"] == "com.charmnight.linkgraph.fixtures.mybatis.UserMapper" &&
                node.metadata["statementId"] == "selectUser"
        }

        assertEquals("MYBATIS_XML_STATEMENT", sqlNode.sourceKind)
        assertTrue(sqlNode.location!!.contains("UserMapper.xml"))
        assertTrue(sqlNode.evidence.any { it.detail == "namespace=com.charmnight.linkgraph.fixtures.mybatis.UserMapper" })
        assertTrue(sqlNode.evidence.any { it.detail == "statementId=selectUser" })
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.MAPS_TO_SQL &&
                    edge.fromNodeId == mapperMethod.id &&
                    edge.toNodeId == sqlNode.id
            },
        )
    }

    private fun loadJavaFixture(relativePath: String) {
        val fixturePath = Path.of("src/testFixtures/java/com/charmnight/linkgraph/fixtures/$relativePath")
        val projectRelativePath = "com/charmnight/linkgraph/fixtures/$relativePath"
        myFixture.addFileToProject(projectRelativePath, Files.readString(fixturePath))
    }

    private fun loadResourceFixture(relativePath: String) {
        val fixturePath = Path.of("src/testFixtures/resources/com/charmnight/linkgraph/fixtures/$relativePath")
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
