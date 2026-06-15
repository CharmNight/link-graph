package com.charmnight.linkgraph.usage

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals

class ClassUsageClassifierTest : BasePlatformTestCase() {
    fun testClassifiesStableJavaUsageKinds() {
        myFixture.addFileToProject(
            "src/main/java/com/example/PaymentPort.java",
            """
            package com.example;
            public interface PaymentPort {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/PaymentService.java",
            """
            package com.example;
            public class PaymentService {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/CardPaymentService.java",
            """
            package com.example;
            public class CardPaymentService extends PaymentService implements PaymentPort {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/PaymentController.java",
            """
            package com.example;
            import com.example.PaymentService;
            public class PaymentController {
                private PaymentService service;
                public PaymentController(PaymentService service) {
                    this.service = service;
                }
                public PaymentService current() {
                    return new PaymentService();
                }
            }
            """.trimIndent(),
        )

        ReadAction.run<RuntimeException> {
            val serviceClass = findClass("com.example.PaymentService")
            val inheritorClass = findClass("com.example.CardPaymentService")
            val serviceKinds = referencesByParent(serviceClass)
                .mapValues { (_, element) -> ClassUsageClassifier.classify(element) }

            assertEquals(ClassUsageKind.IMPORT, serviceKinds[PsiImportStatementBase::class.java])
            assertEquals(ClassUsageKind.FIELD_TYPE, serviceKinds[PsiTypeElement::class.java to "FIELD"])
            assertEquals(ClassUsageKind.METHOD_PARAMETER, serviceKinds[PsiTypeElement::class.java to "PARAMETER"])
            assertEquals(ClassUsageKind.METHOD_RETURN, serviceKinds[PsiTypeElement::class.java to "METHOD"])
            assertEquals(ClassUsageKind.CONSTRUCTOR_CALL, serviceKinds[PsiNewExpression::class.java])
            assertEquals(
                ClassUsageKind.EXTENDS,
                ClassUsageClassifier.classify(requireNotNull(inheritorClass.extendsList?.referenceElements?.firstOrNull())),
            )
            assertEquals(
                ClassUsageKind.IMPLEMENTS,
                ClassUsageClassifier.classify(requireNotNull(inheritorClass.implementsList?.referenceElements?.firstOrNull())),
            )
        }
    }

    private fun findClass(qualifiedName: String): PsiClass =
        requireNotNull(
            JavaPsiFacade.getInstance(project).findClass(
                qualifiedName,
                GlobalSearchScope.projectScope(project),
            ),
        )

    private fun referencesByParent(targetClass: PsiClass): Map<Any, PsiElement> {
        val references = ReferencesSearch.search(targetClass, GlobalSearchScope.projectScope(project)).findAll()
        return references.mapNotNull { reference ->
            val element = reference.element ?: return@mapNotNull null
            when {
                PsiTreeUtil.getParentOfType(element, PsiImportStatementBase::class.java, false) != null ->
                    PsiImportStatementBase::class.java to element
                PsiTreeUtil.getParentOfType(element, PsiNewExpression::class.java, false) != null ->
                    PsiNewExpression::class.java to element
                PsiTreeUtil.getParentOfType(element, PsiReferenceList::class.java, false) != null ->
                    requireNotNull(
                        PsiTreeUtil.getParentOfType(element, PsiReferenceList::class.java, false),
                    ).role to element
                else -> {
                    val typeElement = PsiTreeUtil.getParentOfType(element, PsiTypeElement::class.java, false)
                    val parentKind = typeElement?.parent?.javaClass?.simpleName.orEmpty()
                    PsiTypeElement::class.java to parentKind.removePrefix("Psi").removeSuffix("Impl").uppercase() to element
                }
            }
        }.toMap()
    }
}
