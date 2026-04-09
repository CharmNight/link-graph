package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ResourceSemanticProviderTest : BasePlatformTestCase() {
    fun testMyBatisProviderPreservesStructuredStatementMetadata() {
        myFixture.configureByText(
            "UserMapper.xml",
            """
                <mapper namespace="com.example.UserMapper">
                  <select id="select<caret>User" resultType="com.example.User">
                    select * from user
                  </select>
                </mapper>
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)

        val result = MyBatisXmlSemanticProvider().analyze(
            handle = resourceHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(),
        )

        assertEquals(resourceHandle, result.subject)
        assertTrue(result.semanticUnits.any { unit ->
            unit is ResourceUnit && unit.title == "SQL UserMapper.selectUser"
        })
        val resourceUnit = result.semanticUnits.single() as ResourceUnit
        assertEquals("selectUser", resourceUnit.metadata["statementId"])
        assertEquals("com.example.UserMapper", resourceUnit.metadata["namespace"])
        assertTrue(result.relations.isEmpty())
    }

    fun testMarkdownProviderOnlyBuildsDocumentationResourceUnit() {
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                这里会调用 com.example.OrderService#sub<caret>mit
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)

        val result = MarkdownSemanticProvider().analyze(
            handle = resourceHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(),
        )

        assertEquals(resourceHandle, result.subject)
        val resourceUnit = result.semanticUnits.single() as ResourceUnit
        assertEquals("order-flow.md", resourceUnit.title)
        assertEquals(resourceHandle.sourcePath, resourceUnit.metadata["path"])
        assertTrue(result.relations.isEmpty())
    }

    fun testMarkdownProviderKeepsAnchorResolutionForAnalyzerComposition() {
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                这里会调用 com.example.OrderService#sub<caret>mit
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)

        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
    }

    fun testMarkdownProviderDoesNotSynthesizeMethodUnits() {
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                这里会调用 com.example.OrderService#sub<caret>mit
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)

        val result = MarkdownSemanticProvider().analyze(
            handle = resourceHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(),
        )

        assertFalse(result.semanticUnits.any { unit ->
            unit !is ResourceUnit
        })
    }
}
