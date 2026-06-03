package com.charmnight.linkgraph.application.indexed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
}
