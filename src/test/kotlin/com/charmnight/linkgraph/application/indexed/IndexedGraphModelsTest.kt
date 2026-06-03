package com.charmnight.linkgraph.application.indexed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexedGraphModelsTest {
    @Test
    fun architectureFactoryBuildsUnifiedProjectRequest() {
        val request = requestArchitectureGraphRequest()

        assertEquals(IndexedGraphView.ARCHITECTURE, request.view)
        assertEquals(IndexedGraphScope.Project, request.scope)
        assertEquals(null, request.anchor)
        assertEquals(IndexedGraphRefreshPolicy.ReuseCached, request.refreshPolicy)
        assertTrue(request.includeProjectSources)
        assertEquals(false, request.includeExternalLibraries)
        assertEquals(false, request.includeJdk)
    }

    @Test
    fun classDiagramFactoryMapsScopeNodeToArchitectureAnchor() {
        val request = requestClassDiagramRequest("arch:service:orders")

        assertEquals(IndexedGraphView.CLASS_DIAGRAM, request.view)
        assertEquals("arch:service:orders", assertIs<IndexedGraphAnchor.ArchitectureNode>(request.anchor).nodeId)
        assertEquals("arch:service:orders", assertIs<IndexedGraphScope.ArchitectureNode>(request.scope).nodeId)
        assertEquals("arch:service:orders", request.classDiagramScopeNodeId())
        assertFalse(request.includeExternalLibraries)
        assertFalse(request.includeJdk)
    }

    @Test
    fun unscopedClassDiagramFactoryMapsToCurrentEditorAnchor() {
        val request = requestClassDiagramRequest()

        assertEquals(IndexedGraphView.CLASS_DIAGRAM, request.view)
        assertEquals(true, assertIs<IndexedGraphAnchor.CurrentEditor>(request.anchor).requireClass)
        assertEquals(1, assertIs<IndexedGraphScope.ClassNeighborhood>(request.scope).depth)
        assertEquals(null, request.classDiagramScopeNodeId())
        assertFalse(request.includeExternalLibraries)
        assertFalse(request.includeJdk)
    }

    @Test
    fun reviewGraphFactoryMapsSelectedDiffItemsToReviewSelection() {
        val request = requestReviewGraphRequest(listOf("diff:a", "diff:b"))

        assertEquals(IndexedGraphView.REVIEW, request.view)
        assertEquals("diff:a", assertIs<IndexedGraphAnchor.DiffItem>(request.anchor).id)
        assertEquals(listOf("diff:a", "diff:b"), assertIs<IndexedGraphScope.ReviewSelection>(request.scope).selectedDiffItemIds)
        assertEquals(listOf("diff:a", "diff:b"), request.reviewSelectedDiffItemIds())
        assertFalse(request.includeExternalLibraries)
        assertFalse(request.includeJdk)
    }

    @Test
    fun indexedGraphSummaryCarriesDefaultFreshnessContract() {
        val summary = IndexedGraphSummary(
            view = "ARCHITECTURE",
            scopeKind = "PROJECT",
            scopeLabel = "Project",
            depth = 1,
            projectNodeCount = 0,
            projectClassCount = 0,
            externalNodeCount = 0,
            jdkNodeCount = 0,
            scopedNodeCount = 0,
            visibleNodeCount = 0,
            hiddenNodeCount = 0,
            hiddenEdgeCount = 0,
            candidateNodeCount = 0,
            candidateEdgeCount = 0,
            truncated = false,
            completeness = "Interactive",
            cacheState = "CACHE_MISS",
        )

        assertEquals("FRESH", summary.freshness.state)
        assertNull(summary.freshness.dirtyReason)
        assertEquals(0, summary.freshness.pendingFileCount)
        assertEquals(emptyList(), summary.freshness.pendingFileSamples)
        assertNull(summary.freshness.lastIndexedAtEpochMillis)
        assertNull(summary.freshness.staleSinceEpochMillis)
    }
}
