package com.charmnight.linkgraph.extract

import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

class MethodFlowBuilder {
    fun build(method: PsiMethod): String? {
        val body = method.body ?: return null
        val parts = mutableListOf<String>()
        collectIfs(body, parts)
        collectSwitches(body, parts)
        collectTryCatch(body, parts)
        return parts.distinct().takeIf { it.isNotEmpty() }?.joinToString(" -> ")
    }

    private fun collectIfs(body: PsiCodeBlock, parts: MutableList<String>) {
        PsiTreeUtil.findChildrenOfType(body, PsiIfStatement::class.java)
            .forEach { statement ->
                statement.condition?.text?.trim()?.takeIf { it.isNotBlank() }?.let {
                    parts += "if ($it)"
                }
            }
    }

    private fun collectSwitches(body: PsiCodeBlock, parts: MutableList<String>) {
        PsiTreeUtil.findChildrenOfType(body, PsiSwitchStatement::class.java)
            .forEach { statement ->
                statement.expression?.text?.trim()?.takeIf { it.isNotBlank() }?.let {
                    parts += "switch ($it)"
                }
                PsiTreeUtil.findChildrenOfType(statement.body, PsiSwitchLabelStatementBase::class.java)
                    .forEach { label ->
                        val labelText = label.text.trim().removeSuffix(":")
                        if (labelText.isNotBlank()) {
                            parts += labelText
                        }
                    }
            }
    }

    private fun collectTryCatch(body: PsiCodeBlock, parts: MutableList<String>) {
        PsiTreeUtil.findChildrenOfType(body, PsiTryStatement::class.java)
            .forEach { statement ->
                if (statement.catchSections.isNotEmpty()) {
                    parts += "try"
                }
                statement.catchSections.forEach { catchSection ->
                    catchSection.toFlowPart()?.let(parts::add)
                }
            }
    }

    private fun PsiCatchSection.toFlowPart(): String? {
        val typeText = parameter?.typeElement?.type?.presentableText ?: return null
        return "catch ($typeText)"
    }
}
