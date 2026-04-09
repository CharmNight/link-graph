package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticIdFactory
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProvider
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 资源主题 Provider 的通用基类。
 */
abstract class AbstractResourceSemanticProvider(
    /** 保存当前 Provider 支持的资源种类。 */
    private val supportedKind: ResourceSubjectKind,
) : SemanticProvider {
    /**
     * 判断当前主题是否属于本 Provider 支持的资源种类。
     */
    override fun supports(handle: SubjectHandle): Boolean {
        return handle is ResourceSubjectHandle && handle.kind == supportedKind
    }

    /**
     * 对资源主题生成最小语义结果。
     */
    override fun analyze(
        handle: SubjectHandle,
        capturePolicy: SemanticCapturePolicy,
        budgetPolicy: TraversalBudgetPolicy,
    ): SemanticAnalysisResult {
        // 资源 Provider 只接受资源主题句柄。
        val resourceHandle = handle as? ResourceSubjectHandle
            ?: error("资源语义 Provider 只支持 ResourceSubjectHandle")
        // 基类只负责构造单个资源单元和对应的源码映射。
        val resourceUnit = createResourceUnit(resourceHandle)
        val sourceMappings = listOf(
            SourceMapping(
                sourcePath = resourceHandle.sourcePath,
                sourceRange = resourceHandle.sourceRange,
                targetUnitId = resourceUnit.id,
            ),
        )
        return SemanticAnalysisResult(
            subject = resourceHandle,
            anchors = listOf(
                SemanticAnchor(
                    id = "anchor:${resourceUnit.id.substringAfter(':')}",
                    targetUnitId = resourceUnit.id,
                    label = "当前资源",
                ),
            ),
            semanticUnits = listOf(resourceUnit),
            relations = emptyList(),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = sourceMappings,
        )
    }

    /**
     * 返回资源单元的资源类型名称。
     */
    protected abstract fun resourceKind(handle: ResourceSubjectHandle): String

    /**
     * 返回资源单元的显示标题，默认使用句柄展示名。
     */
    protected open fun displayTitle(handle: ResourceSubjectHandle): String = handle.displayName

    /**
     * 根据资源句柄构造资源语义单元。
     */
    private fun createResourceUnit(handle: ResourceSubjectHandle): ResourceUnit {
        return ResourceUnit(
            // 不同资源种类使用不同命名空间生成稳定语义标识。
            id = when (supportedKind) {
                ResourceSubjectKind.MYBATIS_STATEMENT,
                ResourceSubjectKind.SQL_FILE -> SemanticIdFactory.resourceUnitId("sql", handle.subjectId)
                ResourceSubjectKind.CONFIG_ITEM -> SemanticIdFactory.resourceUnitId("config-item", handle.subjectId)
                ResourceSubjectKind.XML_RESOURCE -> SemanticIdFactory.resourceUnitId("xml-resource", handle.subjectId)
                ResourceSubjectKind.MARKDOWN_PAGE -> SemanticIdFactory.resourceUnitId("doc-page", handle.subjectId)
            },
            title = displayTitle(handle),
            resourceKind = resourceKind(handle),
            metadata = handle.attributes,
        )
    }
}
