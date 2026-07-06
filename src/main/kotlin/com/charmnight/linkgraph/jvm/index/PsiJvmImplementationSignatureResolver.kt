package com.charmnight.linkgraph.jvm.index

import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch

/** IntelliJ PSI-backed fallback used when the persisted JVM relation index has no implementation edges. */
class PsiJvmImplementationSignatureResolver(
    private val project: Project,
) : JvmImplementationSignatureResolver {
    override fun implementationSignatures(
        method: JvmMethodSymbol,
        ownerClass: JvmClassSymbol,
    ): List<String> =
        readActionIfNeeded {
            val ownerPsiClass = JavaPsiFacade.getInstance(project)
                .findClass(ownerClass.qualifiedName, GlobalSearchScope.projectScope(project))
                ?: return@readActionIfNeeded emptyList()
            val ownerPsiMethod = ownerPsiClass.findMethodsByName(method.simpleName, false)
                .firstOrNull { candidate -> psiMethodShapeMatches(candidate, method) }
                ?: return@readActionIfNeeded emptyList()
            ClassInheritorsSearch.search(ownerPsiClass, GlobalSearchScope.projectScope(project), true)
                .findAll()
                .asSequence()
                .filter(::isConcretePsiClass)
                .flatMap { candidateClass ->
                    candidateClass.findMethodsByName(method.simpleName, false).asSequence()
                        .filter { candidateMethod -> overridesPsiMethod(candidateMethod, ownerPsiMethod) }
                }
                .map(::methodSignature)
                .distinct()
                .sorted()
                .toList()
        }

    override fun diagnostic(
        method: JvmMethodSymbol,
        ownerClass: JvmClassSymbol,
    ): String =
        runCatching {
            readActionIfNeeded {
                val readAccessAllowed = readAccessAllowed()
                val scope = GlobalSearchScope.projectScope(project)
                val ownerPsiClass = JavaPsiFacade.getInstance(project).findClass(ownerClass.qualifiedName, scope)
                    ?: return@readActionIfNeeded "psi=readAccessAllowed:$readAccessAllowed, ownerClass:null"
                val ownerMethods = ownerPsiClass.findMethodsByName(method.simpleName, false).toList()
                val ownerPsiMethod = ownerMethods.firstOrNull { candidate -> psiMethodShapeMatches(candidate, method) }
                val inheritors = ClassInheritorsSearch.search(ownerPsiClass, scope, true).findAll().toList()
                val concreteInheritors = inheritors.filter(::isConcretePsiClass)
                val candidateMethods = ownerPsiMethod?.let { baseMethod ->
                    concreteInheritors
                        .asSequence()
                        .flatMap { candidateClass ->
                            candidateClass.findMethodsByName(method.simpleName, false).asSequence()
                                .filter { candidateMethod -> overridesPsiMethod(candidateMethod, baseMethod) }
                        }
                        .map(::methodSignature)
                        .distinct()
                        .sorted()
                        .toList()
                }.orEmpty()
                "psi=readAccessAllowed:$readAccessAllowed, ownerClass:${ownerPsiClass.qualifiedName}, " +
                    "ownerMethods=${ownerMethods.size}[${ownerMethods.map(::methodSignature).take(SAMPLE_LIMIT).joinToString("|")}], " +
                    "ownerPsiMethod=${ownerPsiMethod?.let(::methodSignature)}, " +
                    "inheritors=${inheritors.size}[${inheritors.mapNotNull(PsiClass::getQualifiedName).take(SAMPLE_LIMIT).joinToString("|")}], " +
                    "concreteInheritors=${concreteInheritors.size}[${concreteInheritors.mapNotNull(PsiClass::getQualifiedName).take(SAMPLE_LIMIT).joinToString("|")}], " +
                    "psiCandidates=${candidateMethods.size}[${candidateMethods.take(SAMPLE_LIMIT).joinToString("|")}]"
            }
        }.getOrElse { throwable ->
            "psi=readAccessAllowed:${readAccessAllowed()}, error=${throwable.javaClass.simpleName}:${throwable.message}"
        }

    private fun psiMethodShapeMatches(
        candidate: PsiMethod,
        method: JvmMethodSymbol,
    ): Boolean =
        candidate.name == method.simpleName &&
            candidate.parameterList.parametersCount == method.parameterTypes.size

    private fun isConcretePsiClass(psiClass: PsiClass): Boolean =
        !psiClass.isInterface &&
            !psiClass.isEnum &&
            !psiClass.isAnnotationType &&
            !psiClass.hasModifierProperty(PsiModifier.ABSTRACT)

    private fun overridesPsiMethod(
        candidate: PsiMethod,
        ownerMethod: PsiMethod,
    ): Boolean {
        if (candidate == ownerMethod) {
            return true
        }
        val superMethods = candidate.findSuperMethods().asSequence() + candidate.findDeepestSuperMethods().asSequence()
        return superMethods.any { superMethod -> superMethod == ownerMethod }
    }

    private fun <T> readActionIfNeeded(action: () -> T): T =
        if (readAccessAllowed()) {
            action()
        } else {
            ReadAction.compute<T, RuntimeException> { action() }
        }

    private fun readAccessAllowed(): Boolean =
        ApplicationManager.getApplication().isReadAccessAllowed

    private companion object {
        const val SAMPLE_LIMIT = 8
    }
}
