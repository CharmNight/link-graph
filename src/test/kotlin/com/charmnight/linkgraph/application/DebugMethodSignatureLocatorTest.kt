package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.debug.DebugMethodSignatureLocator
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.testing.addJavaFixture
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals

class DebugMethodSignatureLocatorTest : BasePlatformTestCase() {
    fun testFindsMethodByQualifiedSignature() {
        loadFixture("simple/SimpleCallChain.java")

        val signature = "com.charmnight.linkgraph.fixtures.simple.SimpleCallChain.sanitize(java.lang.String):java.lang.String"
        val method = DebugMethodSignatureLocator.find(project, signature)
        val locatedMethod = requireNotNull(method)

        assertEquals("sanitize", locatedMethod.name)
        assertEquals(signature, methodSignature(locatedMethod))
    }

    fun testFindsMethodBySimpleOwnerSignature() {
        loadFixture("simple/SimpleCallChain.java")

        val method = DebugMethodSignatureLocator.find(
            project,
            "SimpleCallChain.sanitize(java.lang.String):java.lang.String",
        )
        val locatedMethod = requireNotNull(method)

        assertEquals("sanitize", locatedMethod.name)
        assertEquals("SimpleCallChain", locatedMethod.containingClass?.name)
    }

    private fun loadFixture(relativePath: String) {
        myFixture.addJavaFixture(relativePath)
    }
}
