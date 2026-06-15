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

object ClassUsageClassifier {
    fun classify(element: PsiElement): ClassUsageKind {
        if (PsiTreeUtil.getParentOfType(element, PsiImportStatementBase::class.java, false) != null) {
            return ClassUsageKind.IMPORT
        }
        PsiTreeUtil.getParentOfType(element, PsiReferenceList::class.java, false)?.let { referenceList ->
            return when (referenceList.role) {
                PsiReferenceList.Role.EXTENDS_LIST -> ClassUsageKind.EXTENDS
                PsiReferenceList.Role.IMPLEMENTS_LIST -> ClassUsageKind.IMPLEMENTS
                else -> ClassUsageKind.TYPE_REFERENCE
            }
        }
        PsiTreeUtil.getParentOfType(element, PsiNewExpression::class.java, false)?.let { newExpression ->
            if (newExpression.classReference == element ||
                newExpression.classReference?.textRange?.contains(element.textRange) == true
            ) {
                return ClassUsageKind.CONSTRUCTOR_CALL
            }
        }
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
