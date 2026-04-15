package com.charmnight.linkgraph.services

import kotlin.test.Test
import kotlin.test.assertFalse

class LinkGraphProjectServiceStructureTest {
    @Test
    fun planningContextHelpersLiveOutsideFacade() {
        val declaredMethodNames = LinkGraphProjectService::class.java.declaredMethods.map { it.name }.toSet()
        val nestedTypeNames = LinkGraphProjectService::class.java.declaredClasses.map { it.simpleName }.toSet()

        setOf(
            "withComputedPlanningContext",
            "computePlanningPayload",
            "buildPlanSnapshot",
            "buildGraphBeautificationContext",
            "buildAuditGraphs",
            "currentVisibleGraph",
            "currentWorkingGraph",
            "resolveBeautificationAnchorNodeId",
            "computeBeautificationHiddenCounts",
            "collectBeautificationCurrentMethodNodeIds",
            "buildSourceSnippetContexts",
            "readSourceSnippet",
            "normalizeSourceSnippetForPrompt",
            "clipSnippetAtBoundary",
            "mergeWriteReport",
            "canNavigateToSource",
            "computeCurrentMethodNode",
            "ensureGraphContainsNode",
            "mergeGraphNode",
            "clearLastAnalysisCache",
            "sourceForSubject",
            "shouldDeferCurrentMethodResolutionUntilSmart",
            "currentEditorPreviewKind",
            "locateCurrentSubject",
            "locateCurrentCodeSubject",
            "locateCodeSubjectBySignatureInReadAction",
            "resolveCodeSubjectBySignatureAsync",
            "computeAnalysisResultInReadAction",
            "isBenignCurrentSubjectGraphCancellation",
            "positionNodeForCanvas",
        ).forEach { helperName ->
            assertFalse(
                helperName in declaredMethodNames,
                "LinkGraphProjectService 不应继续保留 $helperName；该职责应由独立协作者承接。",
            )
        }

        setOf(
            "PlanningPayload",
            "AuditGraphs",
            "CurrentMethodNode",
            "AnalysisExecutionResult",
            "AnalysisOutcomeAsyncResult",
        ).forEach { nestedTypeName ->
            assertFalse(
                nestedTypeName in nestedTypeNames,
                "LinkGraphProjectService 不应继续保留内部类型 $nestedTypeName；避免旧新实现并存。",
            )
        }
    }
}
