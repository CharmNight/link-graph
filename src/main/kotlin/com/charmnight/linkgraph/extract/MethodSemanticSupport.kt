package com.charmnight.linkgraph.extract

import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.asJava.getAccessorLightMethods
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor

/**
 * 定义方法语义所属的语言域。
 */
internal enum class MethodSemanticDomain {
    /** 表示 Java 语义域。 */
    JAVA,
    /** 表示 Kotlin 语义域。 */
    KOTLIN,
    /** 表示无法归类的其他语义域。 */
    OTHER,
}

/**
 * 定义方法语义的更细粒度种类。
 */
internal enum class MethodSemanticKind {
    /** 表示普通 Java 方法。 */
    JAVA_METHOD,
    /** 表示 Kotlin 命名函数。 */
    KOTLIN_NAMED_FUNCTION,
    /** 表示 Kotlin 属性访问器。 */
    KOTLIN_PROPERTY_ACCESSOR,
    /** 表示 Kotlin 主构造函数。 */
    KOTLIN_PRIMARY_CONSTRUCTOR,
    /** 表示 Kotlin 次构造函数。 */
    KOTLIN_SECONDARY_CONSTRUCTOR,
    /** 表示其他未知方法形态。 */
    OTHER,
}

/**
 * 表示解码后的方法语义信息。
 */
internal data class DecodedMethodSemantic(
    /** 保存语义域。 */
    val domain: MethodSemanticDomain,
    /** 保存语义种类。 */
    val kind: MethodSemanticKind,
    /** 保存方法本体语言标识。 */
    val methodLanguageId: String,
    /** 保存所在文件语言标识。 */
    val fileLanguageId: String?,
    /** 保存导航元素语言标识。 */
    val navigationLanguageId: String,
    /** 保存方法 PSI 类名。 */
    val methodClassName: String,
    /** 保存导航元素类名。 */
    val navigationClassName: String,
    /** 保存方法体类名。 */
    val bodyClassName: String?,
) {
    /**
     * 对不受支持的方法语义生成显式边界说明。
     */
    fun explicitCurrentMethodBoundary(methodSignature: String): ExtractionBoundary? {
        if (kind != MethodSemanticKind.OTHER || bodyClassName == null || bodyClassName == PsiCodeBlock::class.java.name) {
            return null
        }
        return boundary(
            methodSignature = methodSignature,
            title = "非 Java/Kotlin 静态提取边界",
            description = "当前方法不属于受支持的 Java/Kotlin 方法体语义，静态调用链提取无法安全展开该声明体。",
            kind = "NON_JAVA_KOTLIN_BOUNDARY",
        )
    }

    /**
     * 构造一条带调试信息的提取边界。
     */
    fun boundary(
        methodSignature: String,
        title: String,
        description: String,
        kind: String,
    ): ExtractionBoundary {
        return ExtractionBoundary(
            title = title,
            reason = "$description ${debugInfo(methodSignature)}",
            kind = kind,
        )
    }

    /**
     * 生成便于排查的调试说明文本。
     */
    fun debugInfo(methodSignature: String): String {
        return buildString {
            append("signature=")
            append(methodSignature)
            append(", semanticDomain=")
            append(domain.name)
            append(", semanticKind=")
            append(kind.name)
            append(", methodClass=")
            append(methodClassName)
            append(", methodLanguage=")
            append(methodLanguageId)
            append(", fileLanguage=")
            append(fileLanguageId ?: "n/a")
            append(", navigationClass=")
            append(navigationClassName)
            append(", navigationLanguage=")
            append(navigationLanguageId)
            append(", bodyClass=")
            append(bodyClassName ?: "null")
        }
    }
}

/**
 * 表示 Kotlin 属性访问器的真实源码来源。
 */
internal data class KotlinPropertyAccessorSource(
    /** 保存属性或参数声明本体。 */
    val owner: KtNamedDeclaration,
    /** 保存显式访问器声明；若为参数访问器可能为空。 */
    val accessor: KtPropertyAccessor?,
)

/**
 * 从 light method 或导航元素中回溯出 Kotlin 属性访问器来源。
 */
internal fun resolveKotlinPropertyAccessorSource(method: PsiMethod): KotlinPropertyAccessorSource? {
    // 导航元素本身就是访问器时，直接返回属性与访问器。
    val navigationElement = method.navigationElement
    if (navigationElement is KtPropertyAccessor) {
        return KotlinPropertyAccessorSource(
            owner = navigationElement.property,
            accessor = navigationElement,
        )
    }
    return when (navigationElement) {
        is KtProperty -> {
            // 普通属性需要通过 light method 对应 getter/setter 名称反查访问器。
            val accessors = navigationElement.getAccessorLightMethods()
            when {
                accessors.getter?.name == method.name -> KotlinPropertyAccessorSource(
                    owner = navigationElement,
                    accessor = navigationElement.getter,
                )
                accessors.setter?.name == method.name -> KotlinPropertyAccessorSource(
                    owner = navigationElement,
                    accessor = navigationElement.setter,
                )
                else -> null
            }
        }
        is KtParameter -> {
            // 只有带 val/var 的构造参数才会生成属性访问器。
            if (!navigationElement.hasValOrVar()) {
                return null
            }
            val accessors = navigationElement.getAccessorLightMethods()
            when {
                accessors.getter?.name == method.name -> KotlinPropertyAccessorSource(
                    owner = navigationElement,
                    accessor = null,
                )
                accessors.setter?.name == method.name -> KotlinPropertyAccessorSource(
                    owner = navigationElement,
                    accessor = null,
                )
                else -> null
            }
        }
        else -> null
    }
}

/**
 * 负责把 PSI 方法解码为统一的方法语义信息。
 */
internal object MethodSemanticDecoder {
    /**
     * 解码方法的语义域、语义种类和调试元信息。
     */
    fun decode(method: PsiMethod, body: PsiElement? = method.body): DecodedMethodSemantic {
        // 优先识别 Kotlin 属性访问器，因为它可能伪装成普通方法。
        val navigationElement = method.navigationElement
        val kind = when {
            resolveKotlinPropertyAccessorSource(method) != null -> MethodSemanticKind.KOTLIN_PROPERTY_ACCESSOR
            else -> when (navigationElement) {
                is KtNamedFunction -> MethodSemanticKind.KOTLIN_NAMED_FUNCTION
                is KtPrimaryConstructor -> MethodSemanticKind.KOTLIN_PRIMARY_CONSTRUCTOR
                is KtSecondaryConstructor -> MethodSemanticKind.KOTLIN_SECONDARY_CONSTRUCTOR
                is PsiMethod -> MethodSemanticKind.JAVA_METHOD
                else -> MethodSemanticKind.OTHER
            }
        }
        val domain = when (kind) {
            MethodSemanticKind.JAVA_METHOD -> MethodSemanticDomain.JAVA
            MethodSemanticKind.KOTLIN_NAMED_FUNCTION,
            MethodSemanticKind.KOTLIN_PROPERTY_ACCESSOR,
            MethodSemanticKind.KOTLIN_PRIMARY_CONSTRUCTOR,
            MethodSemanticKind.KOTLIN_SECONDARY_CONSTRUCTOR -> MethodSemanticDomain.KOTLIN
            MethodSemanticKind.OTHER -> {
                val methodLanguage = method.language.id
                val fileLanguage = method.containingFile?.language?.id
                val navigationLanguage = navigationElement.language.id
                if (methodLanguage == "JAVA" && fileLanguage == "JAVA" && navigationLanguage == "JAVA") {
                    MethodSemanticDomain.JAVA
                } else {
                    MethodSemanticDomain.OTHER
                }
            }
        }
        // 汇总语言、类名和方法体信息，便于后续做边界判定和调试输出。
        return DecodedMethodSemantic(
            domain = domain,
            kind = kind,
            methodLanguageId = method.language.id,
            fileLanguageId = method.containingFile?.language?.id,
            navigationLanguageId = navigationElement.language.id,
            methodClassName = method.javaClass.name,
            navigationClassName = navigationElement.javaClass.name,
            bodyClassName = body?.javaClass?.name,
        )
    }
}
