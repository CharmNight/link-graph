package com.charmnight.linkgraph.semantic.subject

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import org.jetbrains.kotlin.asJava.getAccessorLightMethods
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor

/**
 * 负责把 PSI 方法转换为统一的代码主题句柄。
 *
 * Java 方法与 Kotlin 函数/构造器/属性访问器在 PSI 中是不同类型，
 * 本工厂把它们统一打包为 [CodeSubjectHandle]，让上层只依赖统一抽象。
 *
 * 关键点：使用 navigationElement 而不是直接用 method，
 * 这样 Kotlin light method 能正确解析到 Kotlin 源声明。
 */
class CodeSubjectHandleFactory {
    /**
     * 根据文件和方法构建代码主题句柄。
     *
     * @param file 当前文件 PSI
     * @param method 方法 PSI（Java 方法或 Kotlin light method）
     * @return 统一的代码主题句柄
     */
    fun create(
        file: PsiFile,
        method: PsiMethod,
    ): CodeSubjectHandle {
        // 导航元素优先指向 Kotlin 源声明，避免只拿到 light method 位置。
        val navigationElement = method.navigationElement
        // 统一根据导航元素计算源码范围，保证跳转和高亮位置准确。
        val range = sourceRangeOf(file, navigationElement.textRange ?: method.textRange)
        // 方法签名是主题句柄稳定标识和显示信息的重要来源。
        val signature = methodSignature(method)
        return CodeSubjectHandle(
            subjectId = stableSubjectId("code-method", signature),
            sourcePath = sourcePathOf(file),
            sourceRange = range,
            displayName = methodDisplayName(method),
            kind = resolveCodeSubjectKind(method),
            methodSignature = signature,
            // 使用 SmartPointer 避免直接持有 PSI（PSI 可能被回收）
            methodPointer = SmartPointerManager.createPointer(method),
        )
    }

    /**
     * 根据导航元素类型判断当前代码主题的具体种类。
     *
     * 优先识别 Kotlin 特有元素（属性访问器、函数、构造器），
     * 最后兜底为 Java 方法。
     */
    private fun resolveCodeSubjectKind(method: PsiMethod): CodeSubjectKind {
        // 对 Kotlin 属性访问器做显式优先判断，避免落入后续通用分支。
        val navigationElement = method.navigationElement
        if (navigationElement is KtPropertyAccessor) {
            return CodeSubjectKind.KOTLIN_PROPERTY_ACCESSOR
        }
        return when (navigationElement) {
            is KtNamedFunction -> CodeSubjectKind.KOTLIN_FUNCTION
            is KtPrimaryConstructor -> CodeSubjectKind.KOTLIN_PRIMARY_CONSTRUCTOR
            is KtSecondaryConstructor -> CodeSubjectKind.KOTLIN_SECONDARY_CONSTRUCTOR
            else -> {
                // 某些 light method 会落到属性声明，这里再兜底识别属性访问器。
                val property = navigationElement as? KtProperty
                if (property?.getAccessorLightMethods()?.allDeclarations?.any { accessor -> accessor.name == method.name } == true) {
                    CodeSubjectKind.KOTLIN_PROPERTY_ACCESSOR
                } else {
                    CodeSubjectKind.JAVA_METHOD
                }
            }
        }
    }
}
