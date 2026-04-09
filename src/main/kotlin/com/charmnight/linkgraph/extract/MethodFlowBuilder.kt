package com.charmnight.linkgraph.extract

import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/**
 * 从方法体里抽取轻量级流程摘要。
 * V1 不做完整 CFG，只保留 if/switch/try-catch 这类对链路阅读最有帮助的结构片段。
 */
class MethodFlowBuilder {
    /**
     * 从方法体中提取可读的流程摘要字符串。
     */
    fun build(method: PsiMethod): String? {
        // 没有方法体时无法提取流程摘要。
        val body = method.body ?: return null
        // 统一收集 if、switch、try-catch 等关键流程片段。
        val parts = mutableListOf<String>()
        collectIfs(body, parts)
        collectSwitches(body, parts)
        collectTryCatch(body, parts)
        // 去重后按发现顺序拼接，生成轻量流程摘要。
        return parts.distinct().takeIf { it.isNotEmpty() }?.joinToString(" -> ")
    }

    /**
     * 收集方法体中的 if 条件片段。
     */
    private fun collectIfs(body: PsiCodeBlock, parts: MutableList<String>) {
        PsiTreeUtil.findChildrenOfType(body, PsiIfStatement::class.java)
            .forEach { statement ->
                statement.condition?.text?.trim()?.takeIf { it.isNotBlank() }?.let {
                    parts += "if ($it)"
                }
            }
    }

    /**
     * 收集方法体中的 switch 分支片段。
     */
    private fun collectSwitches(body: PsiCodeBlock, parts: MutableList<String>) {
        PsiTreeUtil.findChildrenOfType(body, PsiSwitchStatement::class.java)
            .forEach { statement ->
                statement.expression?.text?.trim()?.takeIf { it.isNotBlank() }?.let {
                    parts += "switch ($it)"
                }
                PsiTreeUtil.findChildrenOfType(statement.body, PsiSwitchLabelStatementBase::class.java)
                    .forEach { label ->
                        // 去掉尾部冒号，只保留适合展示的 case 文本。
                        val labelText = label.text.trim().removeSuffix(":")
                        if (labelText.isNotBlank()) {
                            parts += labelText
                        }
                    }
            }
    }

    /**
     * 收集方法体中的 try/catch 片段。
     */
    private fun collectTryCatch(body: PsiCodeBlock, parts: MutableList<String>) {
        PsiTreeUtil.findChildrenOfType(body, PsiTryStatement::class.java)
            .forEach { statement ->
                // 存在 catch 时才把 try 作为一个流程节点加入。
                if (statement.catchSections.isNotEmpty()) {
                    parts += "try"
                }
                statement.catchSections.forEach { catchSection ->
                    catchSection.toFlowPart()?.let(parts::add)
                }
            }
    }

    /**
     * 把 catch 片段转换为摘要文本。
     */
    private fun PsiCatchSection.toFlowPart(): String? {
        // 只提取 catch 参数中的异常类型，保持摘要简洁。
        val typeText = parameter?.typeElement?.type?.presentableText ?: return null
        return "catch ($typeText)"
    }
}
