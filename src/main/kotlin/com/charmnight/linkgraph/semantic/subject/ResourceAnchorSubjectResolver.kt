package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 根据资源锚点定位目标代码主题。
 */
class ResourceAnchorSubjectResolver(
    /** 保存当前项目实例。 */
    private val project: Project,
    /** 保存代码主题句柄工厂。 */
    private val codeSubjectHandleFactory: CodeSubjectHandleFactory = CodeSubjectHandleFactory(),
) {
    /**
     * 解析资源锚点对应的代码主题句柄。
     */
    fun resolve(anchor: ResourceAnchor): ResourceAnchorResolution {
        val scope = GlobalSearchScope.projectScope(project)
        val psiFacade = JavaPsiFacade.getInstance(project)
        // 同时支持按全限定类名和短类名收集候选类。
        val candidateClasses = linkedSetOf<PsiClass>()

        anchor.ownerName.takeIf { '.' in it }
            ?.let { qualifiedName -> psiFacade.findClass(qualifiedName, scope) }
            ?.let(candidateClasses::add)
        PsiShortNamesCache.getInstance(project)
            .getClassesByName(anchor.ownerName.substringAfterLast('.'), scope)
            .forEach(candidateClasses::add)

        val candidateMethods = candidateClasses
            .asSequence()
            .flatMap { psiClass -> psiClass.findMethodsByName(anchor.methodName, true).asSequence() }
            .sortedBy(::methodSignature)
            .toList()

        if (candidateMethods.isEmpty()) {
            return ResourceAnchorResolution(
                state = "NOT_FOUND",
                message = "未找到当前节点关联的目标方法：${formatResourceAnchor(anchor)}，候选为空，请补充更准确的方法引用。",
            )
        }

        // 资源锚点给出参数或返回值时，要求精确签名匹配。
        val hasExplicitSignature = anchor.parameterTypeNames != null || anchor.returnTypeName != null
        if (hasExplicitSignature) {
            val exactMatch = candidateMethods.firstOrNull { method -> matches(method, anchor) }
            if (exactMatch != null) {
                return exactResolution(exactMatch)
            }
            val candidateSignatures = candidateMethods.take(3).map(::methodSignature)
            return ResourceAnchorResolution(
                state = "NO_EXACT_MATCH",
                message = "未找到精确匹配的方法签名，候选：${candidateSignatures.joinToString("；")}",
                candidateSignatures = candidateSignatures,
            )
        }

        // 没有显式签名时，多个候选会被视为歧义。
        if (candidateMethods.size > 1) {
            val candidateSignatures = candidateMethods.take(3).map(::methodSignature)
            return ResourceAnchorResolution(
                state = "AMBIGUOUS",
                message = "当前引用命中多个候选，请补充参数签名后再试：${candidateSignatures.joinToString("；")}",
                candidateSignatures = candidateSignatures,
            )
        }

        return exactResolution(candidateMethods.first())
    }

    /**
     * 把精确命中的 PSI 方法转换为代码主题句柄。
     */
    private fun exactResolution(method: PsiMethod): ResourceAnchorResolution {
        // 方法所在文件失效时返回 STALE 状态，提示调用方重新分析。
        val file = method.containingFile ?: method.navigationElement.containingFile
            ?: return ResourceAnchorResolution(
                state = "STALE",
                message = "目标方法已失效，请重新触发当前节点分析。",
            )
        return ResourceAnchorResolution(
            handle = codeSubjectHandleFactory.create(file, method),
        )
    }

    /**
     * 判断方法是否与资源锚点的签名约束匹配。
     */
    private fun matches(
        method: PsiMethod,
        anchor: ResourceAnchor,
    ): Boolean {
        // 参数类型和返回类型都按标准化文本进行逐项比较。
        anchor.parameterTypeNames?.let { expectedParameterTypes ->
            val actualParameterTypes = method.parameterList.parameters.map { parameter -> canonicalTypeText(parameter.type) }
            if (actualParameterTypes != expectedParameterTypes) {
                return false
            }
        }
        anchor.returnTypeName?.let { expectedReturnType ->
            if (canonicalTypeText(method.returnType) != expectedReturnType) {
                return false
            }
        }
        return true
    }
}
