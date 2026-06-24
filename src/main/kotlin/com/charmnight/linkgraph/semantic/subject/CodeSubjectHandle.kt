package com.charmnight.linkgraph.semantic.subject

import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPsiElementPointer

/**
 * 表示代码主题对应的句柄信息。
 *
 * 主题（subject）是语义分析的基本单位，可以是类、方法、字段等。
 * 句柄（handle）则把主题与它的源码位置、PSI 引用打包在一起，
 * 让上游既能做语义操作，也能随时定位到代码。
 */
data class CodeSubjectHandle(
    /** 保存主题唯一标识。 */
    override val subjectId: String,
    /** 保存源码路径。 */
    override val sourcePath: String,
    /** 保存源码范围。 */
    override val sourceRange: SourceRange,
    /** 保存展示名称。 */
    override val displayName: String,
    /** 保存代码主题种类。 */
    val kind: CodeSubjectKind,
    /** 保存方法签名。 */
    val methodSignature: String,
    /** 保存方法 PSI 智能指针，避免直接持有 PSI 元素导致内存泄漏或失效访问。 */
    val methodPointer: SmartPsiElementPointer<PsiMethod>,
) : SubjectHandle
