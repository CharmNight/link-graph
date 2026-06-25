package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticBoundary
import com.charmnight.linkgraph.semantic.model.SemanticDiagnostic
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.SemanticUnit
import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.FlowScopeCategory
import com.charmnight.linkgraph.semantic.model.FlowScopeUnit
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MergeUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.TerminalUnit
import com.charmnight.linkgraph.semantic.model.FlowEdgeRole
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.model.SemanticIdFactory
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.methodDisplayName
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.charmnight.linkgraph.semantic.subject.sourcePathOf
import com.charmnight.linkgraph.semantic.subject.sourceRangeOf
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiStatement

/**
 * 语义累加器：在单次分析过程中收集所有单元、关系、源码映射、锚点、诊断和边界，
 * 内部维护插入顺序并对相同 key 去重，最终由 [build] 输出不可变结果。
 */
internal class CodeSemanticAccumulator(
    val handle: CodeSubjectHandle,
) {
    private val units = linkedMapOf<String, SemanticUnit>()
    private val relations = linkedMapOf<String, SemanticRelation>()
    private val sourceMappings = linkedMapOf<String, SourceMapping>()
    private val anchors = linkedMapOf<String, SemanticAnchor>()
    private val diagnostics = mutableListOf<SemanticDiagnostic>()
    private val boundaries = mutableListOf<SemanticBoundary>()

    fun build(): SemanticAnalysisResult = SemanticAnalysisResult(
        subject = handle,
        anchors = anchors.values.toList(),
        semanticUnits = units.values.toList(),
        relations = relations.values.toList(),
        diagnostics = diagnostics.distinct(),
        boundaries = boundaries.distinct(),
        sourceMappings = sourceMappings.values.toList(),
    )

    fun addAnchor(targetUnitId: String, label: String) {
        anchors.putIfAbsent(
            targetUnitId,
            SemanticAnchor(id = "anchor:${targetUnitId.substringAfter(':')}", targetUnitId = targetUnitId, label = label),
        )
    }

    fun addMethod(method: PsiMethod): MethodLikeUnit {
        val signature = methodSignature(method)
        val unit = MethodLikeUnit(
            id = SemanticIdFactory.methodUnitId(signature),
            title = methodDisplayName(method),
            signature = signature,
            doc = methodDocSummary(method),
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, method.navigationElement ?: method)
        return unit
    }

    fun addScope(
        ownerSignature: String, element: PsiElement, title: String, scopeKind: String,
        scopeCategory: FlowScopeCategory? = null, incomplete: Boolean = false,
        ownerMethodUnitId: String,
    ): FlowScopeUnit {
        val unit = FlowScopeUnit(
            id = semanticElementId("scope", ownerSignature, element, scopeKind),
            title = title, scopeKind = scopeKind, scopeCategory = scopeCategory, incomplete = incomplete,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    fun addAction(
        ownerSignature: String, element: PsiElement, title: String, actionKind: String,
        ownerMethodUnitId: String,
    ): FlowActionUnit {
        val unit = FlowActionUnit(
            id = semanticElementId("action", ownerSignature, element, actionKind),
            title = title, actionKind = actionKind,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    fun addInvocation(
        ownerSignature: String, sourceUnitId: String, targetSignature: String,
        title: String, element: PsiElement, ownerMethodUnitId: String,
    ): InvocationUnit {
        val unit = InvocationUnit(
            id = SemanticIdFactory.compose("invoke", "$ownerSignature:$sourceUnitId:$targetSignature:${element.textRange?.startOffset ?: 0}"),
            title = title, targetSignature = targetSignature,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    fun addTerminal(
        ownerSignature: String, element: PsiElement, title: String, terminalKind: String,
        ownerMethodUnitId: String,
    ): TerminalUnit {
        val unit = TerminalUnit(
            id = semanticElementId("terminal", ownerSignature, element, terminalKind),
            title = title, terminalKind = terminalKind,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    fun addMerge(
        ownerSignature: String, element: PsiElement, title: String,
        ownerMethodUnitId: String,
    ): MergeUnit {
        val unit = MergeUnit(
            id = semanticElementId("merge", ownerSignature, element, title),
            title = title,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    fun addRelation(relation: SemanticRelation) {
        val key = listOf(
            relation.kind.name, relation.fromUnitId, relation.toUnitId,
            relation.label.orEmpty(), relation.flowEdgeRole?.name.orEmpty(),
            relation.incomplete.toString(), relation.synthetic.toString(), relation.provenance.name,
        ).joinToString("|")
        relations.putIfAbsent(key, relation)
    }

    fun addBoundary(boundary: SemanticBoundary) { boundaries += boundary }
    fun addDiagnostic(diagnostic: SemanticDiagnostic) { diagnostics += diagnostic }

    fun addResolution(resolution: CodeInvocationSemanticResolution) {
        resolution.semanticUnits.forEach { unit -> units.putIfAbsent(unit.id, unit) }
        resolution.relations.forEach(::addRelation)
        resolution.sourceMappings.forEach { mapping -> sourceMappings.putIfAbsent(mapping.targetUnitId, mapping) }
    }

    private fun addContains(fromUnitId: String, toUnitId: String) {
        addRelation(SemanticRelation(kind = SemanticRelationKind.CONTAINS, fromUnitId = fromUnitId, toUnitId = toUnitId))
    }

    private fun addSourceMapping(unitId: String, element: PsiElement) {
        val file = element.containingFile ?: return
        val range = element.textRange ?: return
        sourceMappings.putIfAbsent(
            unitId,
            SourceMapping(sourcePath = sourcePathOf(file), sourceRange = sourceRangeOf(file, normalizeTextRange(range)), targetUnitId = unitId),
        )
    }

    private fun semanticElementId(namespace: String, ownerSignature: String, element: PsiElement, discriminator: String): String {
        val startOffset = element.textRange?.startOffset ?: 0
        return SemanticIdFactory.compose(namespace, "$ownerSignature:$discriminator:$startOffset")
    }

    private fun normalizeTextRange(range: TextRange): TextRange {
        val safeEnd = range.endOffset.coerceAtLeast(range.startOffset)
        return TextRange(range.startOffset, safeEnd)
    }
}

/** 流程构建中的中间片段：入口单元、出口集合以及触发该片段的 PSI 元素。 */
internal data class FlowFragment(
    val entryUnitId: String?,
    val exits: LinkedHashSet<FlowExit>,
    val entryElement: PsiElement? = null,
)

/** 流程出口：携带出口单元 ID 与可选的标签/边角色（TRUE/FALSE/EXCEPTION 等）。 */
internal data class FlowExit(
    val unitId: String,
    val label: String? = null,
    val flowEdgeRole: FlowEdgeRole? = null,
)

/** 带标签的分支片段，用于 switch/when 等多路分支的中间表示。 */
internal data class LabeledBranchFragment(
    val label: String,
    val fragment: FlowFragment,
)

/** switch 分支的中间结构：标签 + 该分支下的语句列表。 */
internal data class SwitchBranch(
    val label: String,
    val statements: List<PsiStatement>,
)

/** 单个方法的构建产物：发现的可下行方法列表、可选边界与诊断。 */
internal data class FlowBuildResult(
    val discoveredMethods: List<PsiMethod>,
    val boundary: SemanticBoundary? = null,
    val diagnostics: List<SemanticDiagnostic> = emptyList(),
)

/** 把分支标签转换为对应的流程边角色：DEFAULT 标签 → DEFAULT 边，其它 → CASE 边。 */
internal fun String.toCaseFlowRole(): FlowEdgeRole =
    if (this == "DEFAULT") FlowEdgeRole.DEFAULT else FlowEdgeRole.CASE
