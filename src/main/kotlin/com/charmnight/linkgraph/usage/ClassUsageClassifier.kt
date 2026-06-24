package com.charmnight.linkgraph.usage

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * 把"类被使用的位置"分类为具体的使用种类。
 *
 * 同一个类可能出现在 import、继承列表、字段类型、构造器调用等多种位置，
 * 本对象按 PSI 上下文给出 [ClassUsageKind] 分类，让 UI 可以分组展示并按重要性排序。
 */
object ClassUsageClassifier {
    /**
     * 对一个使用点 PSI 元素进行分类。
     *
     * 判定优先级（命中即返回）：
     * 1) import 语句 → IMPORT；
     * 2) extends/implements/reference list → EXTENDS/IMPLEMENTS/TYPE_REFERENCE；
     * 3) new 表达式 → CONSTRUCTOR_CALL；
     * 4) 类型元素的父节点 → FIELD_TYPE/METHOD_PARAMETER/METHOD_RETURN；
     * 5) 兜底 → TYPE_REFERENCE。
     *
     * @param element 待分类的 PSI 元素（通常是某个类名引用）
     * @return 使用种类
     */
    fun classify(element: PsiElement): ClassUsageKind {
        // 在 import 语句中：单独归类，因为 import 通常可批量折叠
        if (PsiTreeUtil.getParentOfType(element, PsiImportStatementBase::class.java, false) != null) {
            return ClassUsageKind.IMPORT
        }
        // 在 extends/implements 列表中：按列表角色细分
        PsiTreeUtil.getParentOfType(element, PsiReferenceList::class.java, false)?.let { referenceList ->
            return when (referenceList.role) {
                PsiReferenceList.Role.EXTENDS_LIST -> ClassUsageKind.EXTENDS
                PsiReferenceList.Role.IMPLEMENTS_LIST -> ClassUsageKind.IMPLEMENTS
                else -> ClassUsageKind.TYPE_REFERENCE
            }
        }
        // 在 new 表达式中且是该 new 的类引用：视为构造器调用
        PsiTreeUtil.getParentOfType(element, PsiNewExpression::class.java, false)?.let { newExpression ->
            if (newExpression.classReference == element ||
                newExpression.classReference?.textRange?.contains(element.textRange) == true
            ) {
                return ClassUsageKind.CONSTRUCTOR_CALL
            }
        }
        // 在类型元素中：按父节点角色细分（字段、参数、返回值等）
        val typeElement = PsiTreeUtil.getParentOfType(element, PsiTypeElement::class.java, false)
        return when (typeElement?.parent) {
            is PsiField -> ClassUsageKind.FIELD_TYPE
            is PsiParameter -> ClassUsageKind.METHOD_PARAMETER
            is PsiMethod -> ClassUsageKind.METHOD_RETURN
            is PsiReturnStatement -> ClassUsageKind.METHOD_RETURN
            else -> ClassUsageKind.TYPE_REFERENCE
        }
    }
}
