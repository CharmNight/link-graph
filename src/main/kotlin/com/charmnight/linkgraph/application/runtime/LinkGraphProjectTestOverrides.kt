package com.charmnight.linkgraph.application.runtime

import com.charmnight.linkgraph.application.usecase.InvocationExpansionTarget
import com.charmnight.linkgraph.llm.GraphQaContext
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.TestOnly

@Service(Service.Level.PROJECT)
class LinkGraphProjectTestOverrides {
    @Volatile
    @TestOnly
    var openSettings: (() -> Unit)? = null

    @Volatile
    @TestOnly
    var subjectLocator: SubjectLocator? = null

    @Volatile
    @TestOnly
    var semanticAnalyzer: SemanticAnalyzer? = null

    @Volatile
    @TestOnly
    var analysisOutcomeFactory: AnalysisOutcomeFactory? = null

    @Volatile
    @TestOnly
    var qaExecutor: ((GraphQaContext, String) -> GraphPatchResult)? = null

    @Volatile
    @TestOnly
    var effectiveGenerationSettings: LinkGraphSettingsState? = null

    @Volatile
    @TestOnly
    var asyncRequestTimeoutMillis: Long? = null

    @Volatile
    @TestOnly
    var openCodeDraftNativeDiff: ((String) -> Unit)? = null

    @Volatile
    @TestOnly
    var invocationExpansionTargetResolver: ((Project, String) -> InvocationExpansionTarget)? = null

    @Volatile
    @TestOnly
    var invocationExpansionSubjectResolver: ((String) -> CodeSubjectHandle?)? = null
}
