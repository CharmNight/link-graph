package com.charmnight.linkgraph.application.indexed

import com.charmnight.linkgraph.usage.ClassUsageSearchLimits

/** 索引图预设类型，对应工具窗口里几个常用视图入口。 */
enum class IndexedGraphPreset {
    /** 项目整体架构视图。 */
    ARCHITECTURE,
    /** 指定包的依赖关系视图。 */
    PACKAGE_DEPENDENCY,
    /** 类图视图。 */
    CLASS_DIAGRAM,
    /** 代码评审视图。 */
    REVIEW,
}

/** 预设请求参数集合，承载用户从 UI 传入的可选项（包名、节点、diff 项、覆盖项等）。 */
data class IndexedGraphPresetRequest(
    /** 用户选择的预设类型。 */
    val preset: IndexedGraphPreset,
    /** 包依赖视图使用的包名，仅 PACKAGE_DEPENDENCY 预设需要。 */
    val packageName: String? = null,
    /** 限定范围的节点 ID，主要用于类图视图。 */
    val scopeNodeId: String? = null,
    /** 评审视图选中的 diff 项 ID 列表。 */
    val selectedDiffItemIds: List<String> = emptyList(),
    /** 是否包含外部库依赖，null 表示沿用预设默认值。 */
    val includeExternalLibraries: Boolean? = null,
    /** 是否包含 JDK 依赖，null 表示沿用预设默认值。 */
    val includeJdk: Boolean? = null,
    /** 关系细节（成员/调用等粒度）覆盖项。 */
    val relationDetail: IndexedGraphRelationDetail? = null,
    /** 视口参数（缩放/居中等），由前端传入。 */
    val viewport: IndexedGraphViewportOptions = IndexedGraphViewportOptions(),
    /** 类图相关可选项覆盖项。 */
    val classDiagram: IndexedClassDiagramOptions? = null,
    /** 类引用（usage）覆盖层相关可选项。 */
    val usage: IndexedClassUsageOptions? = null,
    /** 评审视图相关可选项覆盖项。 */
    val review: IndexedReviewGraphOptions? = null,
)

/** 索引图请求工厂：把用户选择的预设和可选项翻译成具体的 [IndexedGraphRequest]。 */
object IndexedGraphRequestFactory {
    /** 按预设类型分派，构造对应的索引图请求，并应用通用与专属覆盖项。 */
    fun fromPreset(request: IndexedGraphPresetRequest): IndexedGraphRequest =
        when (request.preset) {
            IndexedGraphPreset.ARCHITECTURE -> requestArchitectureGraphRequest().withCommonOverrides(request)
            IndexedGraphPreset.PACKAGE_DEPENDENCY -> requestPackageDependencyGraphRequest(request.packageName.orEmpty())
                .withCommonOverrides(request)
            IndexedGraphPreset.CLASS_DIAGRAM -> request.classDiagramBaseRequest()
                .withCommonOverrides(request)
                .copy(classDiagram = request.classDiagram?.mergeInto(request.classDiagramBaseRequest().classDiagram)
                    ?: request.classDiagramBaseRequest().classDiagram)
                .copy(usage = request.usage?.mergeInto(IndexedClassUsageOptions()) ?: IndexedClassUsageOptions())
            IndexedGraphPreset.REVIEW -> requestReviewGraphRequest(request.selectedDiffItemIds)
                .withCommonOverrides(request)
                .copy(review = request.review?.mergeInto(IndexedReviewGraphOptions()) ?: IndexedReviewGraphOptions())
        }

    /** 把通用覆盖项（外部库/JDK/关系细节/视口）合并到已有请求上，仅覆盖非 null 的字段。 */
    private fun IndexedGraphRequest.withCommonOverrides(request: IndexedGraphPresetRequest): IndexedGraphRequest =
        copy(
            includeExternalLibraries = request.includeExternalLibraries ?: includeExternalLibraries,
            includeJdk = request.includeJdk ?: includeJdk,
            relationDetail = request.relationDetail ?: relationDetail,
            viewport = request.viewport,
        )

    /** 决定类图视图的基底请求：若开启了引用覆盖层且未指定范围节点，则切换到 usage 覆盖请求，否则按节点展开类图。 */
    private fun IndexedGraphPresetRequest.classDiagramBaseRequest(): IndexedGraphRequest {
        val usageTargetNodeId = usage?.targetNodeId?.takeIf(String::isNotBlank)
        if (scopeNodeId.isNullOrBlank() && usage?.enabled == true && usageTargetNodeId != null) {
            return requestClassUsageOverlayRequest(usageTargetNodeId)
        }
        return requestClassDiagramRequest(scopeNodeId)
    }
}

/** 构造项目架构视图请求：覆盖整个项目，默认排除外部库与 JDK，并复用缓存。 */
fun requestArchitectureGraphRequest(): IndexedGraphRequest =
    IndexedGraphRequest(
        view = IndexedGraphView.ARCHITECTURE,
        scope = IndexedGraphScope.Project,
        includeExternalLibraries = false,
        includeJdk = false,
        refreshPolicy = IndexedGraphRefreshPolicy.ReuseCached,
    )

/** 构造包依赖视图请求：聚焦指定包，默认包含外部库与 JDK，并重新投影缓存。 */
fun requestPackageDependencyGraphRequest(packageName: String = ""): IndexedGraphRequest =
    IndexedGraphRequest(
        view = IndexedGraphView.ARCHITECTURE,
        scope = IndexedGraphScope.Package(packageName.trim()),
        includeExternalLibraries = true,
        includeJdk = true,
        refreshPolicy = IndexedGraphRefreshPolicy.ReprojectCached,
    )

/** 构造类图视图请求：以节点 ID 锚定，未提供时回退到当前编辑器并展开邻域 1 层。 */
fun requestClassDiagramRequest(scopeNodeId: String? = null): IndexedGraphRequest =
    IndexedGraphRequest(
        view = IndexedGraphView.CLASS_DIAGRAM,
        anchor = scopeNodeId
            ?.takeIf(String::isNotBlank)
            ?.let { nodeId -> IndexedGraphAnchor.ArchitectureNode(nodeId) }
            ?: IndexedGraphAnchor.CurrentEditor(requireClass = true),
        scope = scopeNodeId
            ?.takeIf(String::isNotBlank)
            ?.let { nodeId -> IndexedGraphScope.ArchitectureNode(nodeId) }
            ?: IndexedGraphScope.ClassNeighborhood(depth = 1),
        depth = 1,
        refreshPolicy = IndexedGraphRefreshPolicy.ReuseCached,
    )

/** 构造类引用覆盖视图请求：以目标类节点为中心，叠加展示该类的使用位置。 */
fun requestClassUsageOverlayRequest(targetNodeId: String): IndexedGraphRequest =
    IndexedGraphRequest(
        view = IndexedGraphView.CLASS_DIAGRAM,
        anchor = IndexedGraphAnchor.ClassId(targetNodeId.trim()),
        scope = IndexedGraphScope.ClassNeighborhood(depth = 1),
        depth = 1,
        refreshPolicy = IndexedGraphRefreshPolicy.ReuseCached,
    )

/** 构造代码评审视图请求：以选中的 diff 项集合为范围，取首项作为锚点。 */
fun requestReviewGraphRequest(selectedDiffItemIds: List<String> = emptyList()): IndexedGraphRequest =
    IndexedGraphRequest(
        view = IndexedGraphView.REVIEW,
        anchor = selectedDiffItemIds.firstOrNull()?.let(IndexedGraphAnchor::DiffItem),
        scope = IndexedGraphScope.ReviewSelection(selectedDiffItemIds),
        refreshPolicy = IndexedGraphRefreshPolicy.ReuseCached,
    )

/** 把用户传入的类图选项与默认值合并：成员数限制未显式设置时沿用默认。 */
private fun IndexedClassDiagramOptions.mergeInto(defaults: IndexedClassDiagramOptions): IndexedClassDiagramOptions =
    IndexedClassDiagramOptions(
        neighborhoodLimit = neighborhoodLimit,
        memberLimit = memberLimit.takeIf { it != IndexedClassDiagramOptions().memberLimit } ?: defaults.memberLimit,
    )

/** 把用户传入的引用选项与默认值合并，空字符串字段回退到默认，并对用量限制做归一化。 */
private fun IndexedClassUsageOptions.mergeInto(defaults: IndexedClassUsageOptions): IndexedClassUsageOptions =
    IndexedClassUsageOptions(
        enabled = enabled,
        targetNodeId = targetNodeId?.takeIf(String::isNotBlank) ?: defaults.targetNodeId,
        targetQualifiedName = targetQualifiedName?.takeIf(String::isNotBlank) ?: defaults.targetQualifiedName,
        sourceVirtualFileUrl = sourceVirtualFileUrl?.takeIf(String::isNotBlank) ?: defaults.sourceVirtualFileUrl,
        sourcePath = sourcePath?.takeIf(String::isNotBlank) ?: defaults.sourcePath,
        maxUsageGroups = maxUsageGroups.takeIf { it != IndexedClassUsageOptions().maxUsageGroups }
            ?: defaults.maxUsageGroups,
        maxUsageEntries = maxUsageEntries.takeIf { it != IndexedClassUsageOptions().maxUsageEntries }
            ?: defaults.maxUsageEntries,
        includeImports = includeImports,
    ).normalized()

/** 把引用用量限制参数约束到全局允许的范围内，避免传入过大的值。 */
private fun IndexedClassUsageOptions.normalized(): IndexedClassUsageOptions =
    copy(
        maxUsageGroups = ClassUsageSearchLimits.clampUsageGroups(maxUsageGroups),
        maxUsageEntries = ClassUsageSearchLimits.clampUsageEntries(maxUsageEntries),
    )

/** 把用户传入的评审视图选项与默认值合并：相关测试/上下游节点数未显式设置时沿用默认。 */
private fun IndexedReviewGraphOptions.mergeInto(defaults: IndexedReviewGraphOptions): IndexedReviewGraphOptions =
    IndexedReviewGraphOptions(
        maxChangedNodes = maxChangedNodes,
        maxRelatedTestNodes = maxRelatedTestNodes.takeIf { it != IndexedReviewGraphOptions().maxRelatedTestNodes }
            ?: defaults.maxRelatedTestNodes,
        maxUpstreamNodes = maxUpstreamNodes.takeIf { it != IndexedReviewGraphOptions().maxUpstreamNodes }
            ?: defaults.maxUpstreamNodes,
        maxDownstreamNodes = maxDownstreamNodes.takeIf { it != IndexedReviewGraphOptions().maxDownstreamNodes }
            ?: defaults.maxDownstreamNodes,
    )
