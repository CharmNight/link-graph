package com.charmnight.linkgraph.application.debug

import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 调试场景下按“方法签名字符串”回到真实 PSI 方法。
 * 主要用于 runIde 自动复现当前项目里的真实方法链路，而不是依赖人工点击。
 */
internal object DebugMethodSignatureLocator {
    /**
     * 根据方法签名在项目中查找真实的 PSI 方法。
     */
    fun find(
        project: Project,
        signature: String,
    ): PsiMethod? {
        // 调试输入先做 trim，避免无效空白影响解析。
        val normalizedSignature = signature.trim()
        if (normalizedSignature.isEmpty()) {
            return null
        }
        // 先把签名拆成类与方法信息，解析失败则无法继续查找。
        val parsedSignature = parse(normalizedSignature) ?: return null
        val scope = GlobalSearchScope.projectScope(project)
        val psiFacade = JavaPsiFacade.getInstance(project)
        // 用有序集合收集候选类，支持全限定类名与简单类名两种匹配方式。
        val candidateClasses = linkedSetOf<com.intellij.psi.PsiClass>()

        parsedSignature.qualifiedOwner?.let { qualifiedOwner ->
            psiFacade.findClass(qualifiedOwner, scope)?.let(candidateClasses::add)
        }
        parsedSignature.simpleOwner?.let { simpleOwner ->
            PsiShortNamesCache.getInstance(project)
                .getClassesByName(simpleOwner, scope)
                .forEach(candidateClasses::add)
        }

        if (candidateClasses.isEmpty()) {
            return null
        }

        // 统一把签名转换为可比较形式，抹平限定名与简单类名的差异。
        val comparableExpectedSignature = comparableMethodSignature(normalizedSignature)
        return candidateClasses
            .asSequence()
            .flatMap { psiClass -> psiClass.findMethodsByName(parsedSignature.methodName, false).asSequence() }
            .sortedBy(::methodSignature)
            .firstOrNull { method ->
                val candidateSignature = methodSignature(method)
                candidateSignature == normalizedSignature ||
                    comparableMethodSignature(candidateSignature) == comparableExpectedSignature
            }
    }

    /**
     * 解析方法签名中的类名和方法名。
     */
    private fun parse(signature: String): ParsedMethodSignature? {
        // 必须先找到参数起始位置，才能拆出前面的类名和方法名。
        val argumentsStart = signature.indexOf('(')
        if (argumentsStart <= 0) {
            return null
        }
        val ownerAndMethod = signature.substring(0, argumentsStart)
        val owner = ownerAndMethod.substringBeforeLast('.', missingDelimiterValue = "")
        val methodName = ownerAndMethod.substringAfterLast('.', missingDelimiterValue = "")
        if (owner.isBlank() || methodName.isBlank()) {
            return null
        }
        return ParsedMethodSignature(
            qualifiedOwner = owner.takeIf { '.' in it },
            simpleOwner = owner.substringAfterLast('.').takeIf(String::isNotBlank),
            methodName = methodName,
        )
    }

    /**
     * 把方法签名转换为便于宽松比较的形式。
     */
    private fun comparableMethodSignature(signature: String): String {
        val argumentsStart = signature.indexOf('(')
        if (argumentsStart <= 0) {
            return signature
        }
        val ownerAndMethod = signature.substring(0, argumentsStart)
        val owner = ownerAndMethod.substringBeforeLast('.', missingDelimiterValue = ownerAndMethod)
        val methodName = ownerAndMethod.substringAfterLast('.')
        val simpleOwner = owner.substringAfterLast('.')
        return "$simpleOwner.$methodName${signature.substring(argumentsStart)}"
    }

    /**
     * 表示拆解后的方法签名结构。
     */
    private data class ParsedMethodSignature(
        /** 保存全限定所属类名。 */
        val qualifiedOwner: String?,
        /** 保存简单类名。 */
        val simpleOwner: String?,
        /** 保存方法名。 */
        val methodName: String,
    )
}
