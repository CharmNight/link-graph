package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.debug.DebugMethodSignatureLocator
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.testing.addJavaFixture
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    fun testFindsMethodWhenSignatureUsesErasedQualifiedReturnType() {
        myFixture.configureByText(
            "AbstractPackageManagerScanner.java",
            """
                package com.example;

                import java.util.List;

                abstract class AbstractPackageManagerScanner {
                    protected abstract List<String> detectPackageManagers();
                }
            """.trimIndent(),
        )

        val method = DebugMethodSignatureLocator.find(
            project,
            "com.example.AbstractPackageManagerScanner.detectPackageManagers():java.util.List",
        )
        val locatedMethod = requireNotNull(method)

        assertEquals("detectPackageManagers", locatedMethod.name)
        assertEquals(
            "com.example.AbstractPackageManagerScanner.detectPackageManagers():List<String>",
            methodSignature(locatedMethod),
        )
    }

    fun testFindsMethodWhenSignatureUsesKotlinArrayNotation() {
        myFixture.configureByText(
            "ArrayHandler.java",
            """
                package com.example;

                class ArrayHandler {
                    void accept(String[] values) {}
                }
            """.trimIndent(),
        )

        val method = DebugMethodSignatureLocator.find(
            project,
            "com.example.ArrayHandler.accept(Array<String>):void",
        )
        val locatedMethod = requireNotNull(method)

        assertEquals("accept", locatedMethod.name)
        assertEquals(
            "com.example.ArrayHandler.accept(String[]):void",
            methodSignature(locatedMethod),
        )
    }

    fun testFindsMethodFromContentRootJavaFileWithoutSourceRoot() {
        val contentRoot = Files.createTempDirectory("debug-method-signature-locator-content-root")
        val sourceFile = contentRoot.resolve("src/main/java/com/example/AbstractPackageManagerScanner.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
                package com.example;

                import java.util.List;

                abstract class AbstractPackageManagerScanner {
                    protected abstract List<String> detectPackageManagers();
                }
            """.trimIndent(),
        )
        val contentRootFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(contentRoot))
        PsiTestUtil.addContentRoot(module, contentRootFile)

        val signature = "com.example.AbstractPackageManagerScanner.detectPackageManagers():java.util.List"
        val diagnostics = requireNotNull(DebugMethodSignatureLocator.collectLookupDiagnostics(project, signature))
        assertTrue(diagnostics.filenameHits.any { hit -> hit.path.endsWith("/AbstractPackageManagerScanner.java") })
        assertTrue(diagnostics.filenameHits.any { hit -> !hit.inSource })
        assertNull(diagnostics.projectScope.qualifiedHit)

        val method = DebugMethodSignatureLocator.find(project, signature)
        val locatedMethod = requireNotNull(method)

        assertEquals("detectPackageManagers", locatedMethod.name)
        assertEquals(
            "com.example.AbstractPackageManagerScanner.detectPackageManagers():List<String>",
            methodSignature(locatedMethod),
        )
    }

    private fun loadFixture(relativePath: String) {
        myFixture.addJavaFixture(relativePath)
    }
}
