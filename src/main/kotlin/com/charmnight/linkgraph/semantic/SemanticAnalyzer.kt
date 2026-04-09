package com.charmnight.linkgraph.semantic

import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticDiagnostic
import com.charmnight.linkgraph.semantic.model.SemanticDiagnosticSeverity
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.relation.ResourceRelationResolver
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceAnchorSubjectResolver
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.intellij.openapi.project.Project

/**
 * 语义分析总入口，负责分发到具体 Provider，并补充资源锚点联动分析。
 */
class SemanticAnalyzer(
    /** 保存语义 Provider 注册表。 */
    private val registry: SemanticProviderRegistry,
    /** 保存当前项目实例，用于资源锚点回查。 */
    private val project: Project? = null,
    /** 保存资源关系解析器。 */
    private val resourceRelationResolver: ResourceRelationResolver = ResourceRelationResolver(),
) {
    /**
     * 对指定主题执行语义分析。
     */
    fun analyze(
        handle: SubjectHandle,
        capturePolicy: SemanticCapturePolicy,
        budgetPolicy: TraversalBudgetPolicy,
    ): SemanticAnalysisResult {
        // 先交给主题对应的主 Provider 产出基础语义结果。
        val baseResult = registry.providerFor(handle).analyze(handle, capturePolicy, budgetPolicy)
        // 只有资源主题才需要继续尝试资源锚点联动分析。
        val resourceHandle = handle as? ResourceSubjectHandle ?: return baseResult
        return mergeResourceAnchorSemantic(resourceHandle, baseResult, capturePolicy, budgetPolicy)
    }

    /**
     * 把资源锚点解析得到的方法语义结果并入基础结果。
     */
    private fun mergeResourceAnchorSemantic(
        handle: ResourceSubjectHandle,
        baseResult: SemanticAnalysisResult,
        capturePolicy: SemanticCapturePolicy,
        budgetPolicy: TraversalBudgetPolicy,
    ): SemanticAnalysisResult {
        // 缺少锚点、项目实例或资源单元时，都无法继续做关联分析。
        val anchor = handle.resourceAnchor ?: return baseResult
        val targetProject = project ?: return baseResult
        val resourceUnit = baseResult.semanticUnits.filterIsInstance<ResourceUnit>().firstOrNull() ?: return baseResult
        // 先解析资源锚点指向的代码主题。
        val resolution = ResourceAnchorSubjectResolver(targetProject).resolve(anchor)
        val resolutionMetadata = resolution.metadata()
        if (resolution.handle == null) {
            // 解析失败时仍然把失败元信息和诊断挂回资源单元，便于界面展示。
            val resourceUnitWithMetadata = resourceUnit.copy(metadata = resourceUnit.metadata + resolutionMetadata)
            return baseResult.copy(
                semanticUnits = baseResult.semanticUnits.map { unit ->
                    if (unit.id == resourceUnit.id) resourceUnitWithMetadata else unit
                },
                diagnostics = baseResult.diagnostics + resolution.toDiagnostic(),
            )
        }

        // 解析成功后，对锚点对应代码主题再次执行语义分析。
        val codeResult = registry.providerFor(resolution.handle).analyze(
            resolution.handle,
            capturePolicy,
            budgetPolicy,
        )
        // 优先使用锚点指向的方法单元作为资源关系连接目标。
        val entryMethod = resolveEntryMethod(codeResult) ?: return baseResult
        return SemanticAnalysisResult(
            subject = handle,
            anchors = (baseResult.anchors + codeResult.anchors).distinct(),
            semanticUnits = (baseResult.semanticUnits + codeResult.semanticUnits).distinctBy { unit -> unit.id },
            relations = (
                baseResult.relations +
                    resourceRelationResolver.resolve(handle, resourceUnit.copy(metadata = resourceUnit.metadata + resolutionMetadata), entryMethod) +
                    codeResult.relations
                ).distinct(),
            diagnostics = (baseResult.diagnostics + codeResult.diagnostics).distinct(),
            boundaries = (baseResult.boundaries + codeResult.boundaries).distinct(),
            sourceMappings = (baseResult.sourceMappings + codeResult.sourceMappings).distinct(),
        )
    }

    /**
     * 从语义结果中解析入口方法单元。
     */
    private fun resolveEntryMethod(result: SemanticAnalysisResult): MethodLikeUnit? {
        // 先按标识索引方法单元，再优先尝试使用锚点命中的目标方法。
        val methodsById = result.semanticUnits
            .filterIsInstance<MethodLikeUnit>()
            .associateBy { unit -> unit.id }
        return result.anchors
            .asSequence()
            .mapNotNull { anchor -> anchor.targetUnitId?.let(methodsById::get) }
            .firstOrNull()
            ?: result.semanticUnits.filterIsInstance<MethodLikeUnit>().firstOrNull()
    }

    /**
     * 把资源锚点解析结果转换为告警诊断。
     */
    private fun com.charmnight.linkgraph.semantic.subject.ResourceAnchorResolution.toDiagnostic(): SemanticDiagnostic {
        val resolutionState = state ?: "UNKNOWN"
        val resolutionMessage = message ?: "资源锚点解析失败。"
        return SemanticDiagnostic(
            severity = SemanticDiagnosticSeverity.WARNING,
            code = "resource-anchor-${resolutionState.lowercase()}",
            message = resolutionMessage,
        )
    }
}
