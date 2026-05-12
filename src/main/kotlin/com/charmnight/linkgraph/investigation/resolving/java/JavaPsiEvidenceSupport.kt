package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.investigation.domain.EvidenceCandidate
import com.charmnight.linkgraph.investigation.domain.EvidenceFact
import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.EvidenceLevel
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 提供 Java PSI 取证 resolver 共享的符号解析与事实构造能力。
 */
internal object JavaPsiEvidenceSupport {
    /**
     * 按目标中的类名、方法名与参数解析候选方法。
     */
    fun resolveMethodCandidates(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): MethodCandidates {
        val ownerName = goal.ownerClassName?.takeIf(String::isNotBlank)
            ?: return MethodCandidates(emptyList(), "缺少所属类名。")
        val methodName = goal.methodName?.takeIf(String::isNotBlank)
            ?: return MethodCandidates(emptyList(), "缺少方法名。")
        val classCandidates = ownerNameCandidates(ownerName, goal)
            .flatMap { candidateOwnerName -> resolveClassCandidates(context, candidateOwnerName) }
            .distinctBy { psiClass -> psiClass.qualifiedName ?: psiClass.name.orEmpty() }
        if (classCandidates.isEmpty()) {
            return MethodCandidates(emptyList(), "未找到类 $ownerName。")
        }
        val methods = classCandidates
            .flatMap { psiClass -> psiClass.findMethodsByName(methodName, false).toList() }
            .filter { method -> matchesRequestedSignature(method, goal.parameterTypes, goal.returnType) }
            .sortedBy(::methodSignatureText)
        return MethodCandidates(methods, null)
    }

    /**
     * 根据参数或返回值里的包名补全短类名候选。
     */
    private fun ownerNameCandidates(
        ownerName: String,
        goal: EvidenceGoal,
    ): List<String> {
        val names = linkedSetOf(ownerName)
        if ('.' !in ownerName) {
            (goal.parameterTypes + listOfNotNull(goal.returnType))
                .mapNotNull { typeName -> typeName.substringBeforeLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank) }
                .forEach { packageName -> names += "$packageName.$ownerName" }
        }
        return names.toList()
    }

    /**
     * 按类名解析项目内候选类，支持全限定类名和短类名。
     */
    fun resolveClassCandidates(
        context: InvestigationContext,
        className: String,
    ): List<PsiClass> {
        val scope = GlobalSearchScope.projectScope(context.project)
        val facade = JavaPsiFacade.getInstance(context.project)
        facade.findClass(className, scope)?.let { psiClass ->
            return listOf(psiClass)
        }
        val shortName = className.substringAfterLast('.')
        val indexedClasses = PsiShortNamesCache.getInstance(context.project)
            .getClassesByName(shortName, scope)
            .sortedBy { psiClass -> psiClass.qualifiedName ?: psiClass.name.orEmpty() }
            .toList()
        if (indexedClasses.isNotEmpty()) {
            return indexedClasses
        }
        return emptyList()
    }

    /**
     * 判断方法是否满足请求参数与返回类型。
     */
    fun matchesRequestedSignature(
        method: PsiMethod,
        parameterTypes: List<String>,
        returnType: String?,
    ): Boolean {
        if (parameterTypes.isNotEmpty()) {
            val actualParameters = method.parameterList.parameters.map { parameter ->
                normalizeType(parameter.type)
            }
            val expectedParameters = parameterTypes.map(::normalizeRequestedType)
            if (actualParameters.size != expectedParameters.size ||
                actualParameters.zip(expectedParameters).any { (actual, expected) -> !typesMatch(actual, expected) }
            ) {
                return false
            }
        }
        if (!returnType.isNullOrBlank() && !typesMatch(normalizeType(method.returnType), normalizeRequestedType(returnType))) {
            return false
        }
        return true
    }

    /**
     * 比较类型名，兼容 PSI 未能补齐包名时的短名匹配。
     */
    private fun typesMatch(
        actual: String,
        expected: String,
    ): Boolean {
        return actual == expected || actual.substringAfterLast('.') == expected.substringAfterLast('.')
    }

    /**
     * 判断方法是否位于项目源码内。
     */
    fun isProjectSourceMethod(method: PsiMethod): Boolean {
        val virtualFile = method.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(method.project).isInContent(virtualFile)
    }

    /**
     * 把 PSI 方法转换为直接源码事实。
     */
    fun methodFact(
        goal: EvidenceGoal,
        resolverId: String,
        method: PsiMethod,
        level: EvidenceLevel = EvidenceLevel.DIRECT_SOURCE_RESOLVED,
        claim: String = "已确认方法 ${methodSignatureText(method)} 存在于真实源码。",
        whyResolved: String,
    ): EvidenceFact {
        val range = lineRange(method.navigationElement)
        val file = method.navigationElement.containingFile ?: method.containingFile
        return EvidenceFact(
            factId = "${goal.goalId}-$resolverId-${methodSignatureText(method).hashCode()}",
            level = level,
            resolverId = resolverId,
            symbolSignature = methodSignatureText(method),
            filePath = file?.virtualFile?.path ?: file?.name.orEmpty(),
            startLine = range.first,
            endLine = range.second,
            claim = claim,
            whyResolved = whyResolved,
        )
    }

    /**
     * 把 PSI 类转换为直接源码事实。
     */
    fun classFact(
        goal: EvidenceGoal,
        resolverId: String,
        psiClass: PsiClass,
        level: EvidenceLevel = EvidenceLevel.DIRECT_SOURCE_RESOLVED,
        claim: String,
        whyResolved: String,
    ): EvidenceFact {
        val range = lineRange(psiClass.navigationElement)
        val file = psiClass.navigationElement.containingFile ?: psiClass.containingFile
        return EvidenceFact(
            factId = "${goal.goalId}-$resolverId-${(psiClass.qualifiedName ?: psiClass.name).hashCode()}",
            level = level,
            resolverId = resolverId,
            symbolSignature = psiClass.qualifiedName ?: psiClass.name.orEmpty(),
            filePath = file?.virtualFile?.path ?: file?.name.orEmpty(),
            startLine = range.first,
            endLine = range.second,
            claim = claim,
            whyResolved = whyResolved,
        )
    }

    /**
     * 把 PSI 方法转换为候选证据。
     */
    fun methodCandidate(
        goal: EvidenceGoal,
        resolverId: String,
        method: PsiMethod,
        reason: String,
    ): EvidenceCandidate {
        return EvidenceCandidate(
            candidateId = "${goal.goalId}-$resolverId-candidate-${methodSignatureText(method).hashCode()}",
            symbolSignature = methodSignatureText(method),
            resolverId = resolverId,
            reason = reason,
        )
    }

    /**
     * 把 PSI 类型转换为稳定全限定文本。
     */
    fun normalizeType(type: PsiType?): String {
        val text = type?.canonicalText ?: "void"
        val resolvedClass = (type as? com.intellij.psi.PsiClassType)?.resolve()
        return resolvedClass?.qualifiedName ?: normalizeRequestedType(text)
    }

    /**
     * 生成取证内部使用的稳定方法签名，尽量补齐同包短类型。
     */
    fun methodSignatureText(method: PsiMethod): String {
        val ownerName = method.containingClass?.qualifiedName
            ?: method.containingClass?.name
            ?: method.name
        val packageName = (method.containingFile as? PsiJavaFile)?.packageName.orEmpty()
        val parameters = method.parameterList.parameters.joinToString(",") { parameter ->
            normalizeTypeInPackage(parameter.type, packageName)
        }
        val returnType = normalizeTypeInPackage(method.returnType, packageName)
        return "$ownerName.${method.name}($parameters):$returnType"
    }

    /**
     * 标准化类型，并在 PSI 未解析同包类型时补齐包名。
     */
    private fun normalizeTypeInPackage(
        type: PsiType?,
        packageName: String,
    ): String {
        val normalized = normalizeType(type)
        if (normalized == "void" || '.' in normalized || normalized.firstOrNull()?.isLowerCase() == true) {
            return normalized
        }
        return packageName.takeIf(String::isNotBlank)?.let { "$it.$normalized" } ?: normalized
    }

    /**
     * 标准化请求中的类型文本。
     */
    fun normalizeRequestedType(rawType: String): String {
        return when (rawType.trim()) {
            "String" -> "java.lang.String"
            "Object" -> "java.lang.Object"
            "Integer" -> "java.lang.Integer"
            "Long" -> "java.lang.Long"
            "Boolean" -> "java.lang.Boolean"
            "Double" -> "java.lang.Double"
            "Float" -> "java.lang.Float"
            "Short" -> "java.lang.Short"
            "Byte" -> "java.lang.Byte"
            "Character" -> "java.lang.Character"
            "Void" -> "java.lang.Void"
            else -> rawType.trim()
        }
    }

    /**
     * 计算 PSI 元素所在行号范围。
     */
    fun lineRange(element: PsiElement?): Pair<Int?, Int?> {
        val target = element ?: return null to null
        val file = target.containingFile ?: return null to null
        val virtualFile = file.virtualFile ?: return null to null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: (file as? PsiJavaFile)?.viewProvider?.document
            ?: return null to null
        val range = target.textRange ?: return null to null
        return document.getLineNumber(range.startOffset).plus(1) to
            document.getLineNumber(range.endOffset.coerceAtLeast(range.startOffset)).plus(1)
    }
}

/**
 * 保存方法解析候选与不可解析原因。
 */
internal data class MethodCandidates(
    /** 保存匹配到的方法列表。 */
    val methods: List<PsiMethod>,
    /** 保存未能执行解析的原因。 */
    val unresolvedReason: String?,
)
