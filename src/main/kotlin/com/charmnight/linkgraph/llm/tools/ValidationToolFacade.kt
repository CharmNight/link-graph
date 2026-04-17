package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditScopeResolver
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.llm.artifact.CodeEvidenceArtifact
import com.charmnight.linkgraph.llm.EditScope

/**
 * 聚合 runtime 里需要的本地校验逻辑。
 * 第一阶段先把 confirmed intent、代码读取和 edit scope 的硬边界显式编码进去。
 */
class ValidationToolFacade(
    private val codeEditScopeResolver: CodeEditScopeResolver = CodeEditScopeResolver(),
) {
    /** 判断结果是否具备直接证据。 */
    fun hasDirectEvidence(findings: List<ResultEvidenceFinding>): Boolean {
        return findings.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE ||
                finding.evidenceLevel == ResultEvidenceLevel.DIRECT_GRAPH
        }
    }

    /** 检查 existing-file draft 是否具备合法作用域。 */
    fun hasValidEditScope(draft: GeneratedCodeDraft): Boolean {
        if (draft.editOperations.isEmpty()) {
            return true
        }
        if (draft.editScopes.isEmpty()) {
            return false
        }
        return draft.editOperations.all { operation ->
            val scope = codeEditScopeResolver.resolveScope(operation, draft.editScopes)
            scope != null && codeEditScopeResolver.isOperationAllowed(operation, scope)
        }
    }

    /** 检查 existing-file draft 生成前是否读过目标代码。 */
    fun hasReadEvidence(
        draft: GeneratedCodeDraft,
        evidenceArtifacts: List<CodeEvidenceArtifact>,
    ): Boolean {
        if (draft.editOperations.isEmpty()) {
            return true
        }
        val targetPath = draft.targetPath.replace('\\', '/')
        return evidenceArtifacts.any { artifact ->
            artifact.filePath.replace('\\', '/') == targetPath
        }
    }

    /** 判断草稿是否满足最小写回条件。 */
    fun isWritableDraft(draft: GeneratedCodeDraft): Boolean {
        return draft.content != null || (draft.editOperations.isNotEmpty() && draft.editScopes.isNotEmpty())
    }
}
