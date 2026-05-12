package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodeSemanticProviderRegistryBoundaryTest : BasePlatformTestCase() {
    fun testRegistryRejectsDuplicateCodeSubjectKindRegistration() {
        val handle = javaMethodHandle()
        val first = FakeCodeProvider(setOf(CodeSubjectKind.JAVA_METHOD))
        val second = FakeCodeProvider(setOf(CodeSubjectKind.JAVA_METHOD))
        val registry = SemanticProviderRegistry(listOf(first, second))

        assertFailsWith<IllegalStateException> {
            registry.providerFor(handle)
        }
    }

    fun testRegistryRejectsMissingCodeSubjectKindRegistrationWithClearMessage() {
        val handle = javaMethodHandle()
        val registry = SemanticProviderRegistry(emptyList())

        val error = assertFailsWith<IllegalStateException> {
            registry.providerFor(handle)
        }
        assertTrue(
            error.message?.contains("missing") == true || error.message?.contains("缺少") == true,
            "missing CodeSubjectKind registration should have an explicit error message",
        )
    }

    fun testRegistrySelectsProviderByExactCodeSubjectKind() {
        val handle = javaMethodHandle()
        val provider = FakeCodeProvider(setOf(CodeSubjectKind.JAVA_METHOD))
        val registry = SemanticProviderRegistry(listOf(provider))

        assertSame(provider, registry.providerFor(handle))
    }

    fun testCodeProvidersDeclareSupportedKindsAndAvoidHardcodedRouterBranching() {
        val registrySource = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/semantic/provider/SemanticProviderRegistry.kt"),
        )
        val javaSource = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/semantic/provider/code/JavaCodeSemanticProvider.kt"),
        )
        val kotlinSource = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/semantic/provider/code/KotlinCodeSemanticProvider.kt"),
        )
        val codeRouterSource = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/semantic/provider/code/CodeSemanticProvider.kt"),
        )

        assertTrue(registrySource.contains("supportedKinds"))
        assertTrue(javaSource.contains("supportedKinds"))
        assertTrue(kotlinSource.contains("supportedKinds"))
        assertFalse(kotlinSource.contains("kind != CodeSubjectKind.JAVA_METHOD"))
        assertFalse(codeRouterSource.contains("when (codeHandle.kind)"))
    }

    private fun javaMethodHandle(): CodeSubjectHandle {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void pla<caret>ce(String value) {
                        System.out.println(value);
                    }
                }
            """.trimIndent(),
        )
        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        assertNotNull(handle)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)
        assertEquals(CodeSubjectKind.JAVA_METHOD, codeHandle.kind)
        return codeHandle
    }

    private class FakeCodeProvider(
        override val supportedKinds: Set<CodeSubjectKind>,
    ) : CodeSubjectSemanticProvider {

        override fun analyze(
            handle: SubjectHandle,
            capturePolicy: SemanticCapturePolicy,
            budgetPolicy: TraversalBudgetPolicy,
        ): SemanticAnalysisResult {
            return SemanticAnalysisResult(
                subject = handle,
                anchors = emptyList(),
                semanticUnits = emptyList(),
                relations = emptyList(),
                diagnostics = emptyList(),
                boundaries = emptyList(),
                sourceMappings = emptyList(),
            )
        }
    }
}
