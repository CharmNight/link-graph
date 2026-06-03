package com.charmnight.linkgraph.application.indexed

enum class IndexedGraphPreset {
    ARCHITECTURE,
    PACKAGE_DEPENDENCY,
    CLASS_DIAGRAM,
    REVIEW,
}

data class IndexedGraphPresetRequest(
    val preset: IndexedGraphPreset,
    val packageName: String? = null,
    val scopeNodeId: String? = null,
    val selectedDiffItemIds: List<String> = emptyList(),
    val includeExternalLibraries: Boolean? = null,
    val includeJdk: Boolean? = null,
    val viewport: IndexedGraphViewportOptions = IndexedGraphViewportOptions(),
    val classDiagram: IndexedClassDiagramOptions? = null,
    val review: IndexedReviewGraphOptions? = null,
)

object IndexedGraphRequestFactory {
    fun fromPreset(request: IndexedGraphPresetRequest): IndexedGraphRequest =
        when (request.preset) {
            IndexedGraphPreset.ARCHITECTURE -> requestArchitectureGraphRequest().withCommonOverrides(request)
            IndexedGraphPreset.PACKAGE_DEPENDENCY -> requestPackageDependencyGraphRequest(request.packageName.orEmpty())
                .withCommonOverrides(request)
            IndexedGraphPreset.CLASS_DIAGRAM -> requestClassDiagramRequest(request.scopeNodeId)
                .withCommonOverrides(request)
                .copy(classDiagram = request.classDiagram?.mergeInto(requestClassDiagramRequest(request.scopeNodeId).classDiagram)
                    ?: requestClassDiagramRequest(request.scopeNodeId).classDiagram)
            IndexedGraphPreset.REVIEW -> requestReviewGraphRequest(request.selectedDiffItemIds)
                .withCommonOverrides(request)
                .copy(review = request.review?.mergeInto(IndexedReviewGraphOptions()) ?: IndexedReviewGraphOptions())
        }

    private fun IndexedGraphRequest.withCommonOverrides(request: IndexedGraphPresetRequest): IndexedGraphRequest =
        copy(
            includeExternalLibraries = request.includeExternalLibraries ?: includeExternalLibraries,
            includeJdk = request.includeJdk ?: includeJdk,
            viewport = request.viewport,
        )
}

fun requestArchitectureGraphRequest(): IndexedGraphRequest =
    IndexedGraphRequest(
        view = IndexedGraphView.ARCHITECTURE,
        scope = IndexedGraphScope.Project,
        includeExternalLibraries = false,
        includeJdk = false,
        refreshPolicy = IndexedGraphRefreshPolicy.ReuseCached,
    )

fun requestPackageDependencyGraphRequest(packageName: String = ""): IndexedGraphRequest =
    IndexedGraphRequest(
        view = IndexedGraphView.ARCHITECTURE,
        scope = IndexedGraphScope.Package(packageName.trim()),
        includeExternalLibraries = true,
        includeJdk = true,
        refreshPolicy = IndexedGraphRefreshPolicy.ReprojectCached,
    )

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

fun requestReviewGraphRequest(selectedDiffItemIds: List<String> = emptyList()): IndexedGraphRequest =
    IndexedGraphRequest(
        view = IndexedGraphView.REVIEW,
        anchor = selectedDiffItemIds.firstOrNull()?.let(IndexedGraphAnchor::DiffItem),
        scope = IndexedGraphScope.ReviewSelection(selectedDiffItemIds),
        refreshPolicy = IndexedGraphRefreshPolicy.ReuseCached,
    )

private fun IndexedClassDiagramOptions.mergeInto(defaults: IndexedClassDiagramOptions): IndexedClassDiagramOptions =
    IndexedClassDiagramOptions(
        neighborhoodLimit = neighborhoodLimit,
        memberLimit = memberLimit.takeIf { it != IndexedClassDiagramOptions().memberLimit } ?: defaults.memberLimit,
    )

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
