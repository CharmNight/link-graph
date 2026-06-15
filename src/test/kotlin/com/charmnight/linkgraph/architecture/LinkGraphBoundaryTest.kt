package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphBoundaryTest {
    private val obsoleteQaName = "au" + "dit"
    private val obsoleteQaTypePrefix = "Au" + "dit"

    @Test
    fun projectServiceDoesNotOwnToolwindowOrBrowserAndAppUsesDedicatedStateHooksAndControllers() {
        val components = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationService.kt"),
        )
        val openAction = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/actions/OpenLinkGraphAction.kt"),
        )
        val appSource = Files.readString(Path.of("web/src/app/App.tsx"))

        assertFalse(Files.exists(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt")))
        assertFalse(components.contains("GraphBrowserPanel"))
        assertFalse(components.contains("LinkGraphToolWindowSession"))

        assertTrue(openAction.contains("LinkGraphToolWindowSession"))
        assertTrue(appSource.contains("useBridgeCommandController"))
        assertTrue(appSource.contains("useSourceNavigationController"))
        assertTrue(appSource.contains("useWorkbenchCommandController"))
        assertTrue(appSource.contains("useWorkbenchState"))
        assertTrue(appSource.contains("useBootstrapStateController"))
        assertFalse(appSource.contains("./components/GraphCanvas"))
    }

    @Test
    fun runtimeCapabilitiesDoNotDependOnLegacyExecutorsOrArtifactCleanup() {
        val qaCapability = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/capability/QaCapability.kt"),
        )
        val planCapability = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/capability/PlanCapability.kt"),
        )
        val codegenCapability = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/capability/CodegenCapability.kt"),
        )
        val graphSnapshotDocuments = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/application/model/GraphSnapshotDocuments.kt"),
        )
        val graphEditorSnapshot = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorSnapshot.kt"),
        )
        val graphEditorViewSupport = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorViewSupport.kt"),
        )

        assertFalse(qaCapability.contains("legacyQaExecutor"))
        assertFalse(qaCapability.contains("delegate-legacy-$obsoleteQaName-service"))
        assertFalse(planCapability.contains("legacyPlanExecutor"))
        assertFalse(planCapability.contains("delegate-legacy-plan-service"))
        assertFalse(codegenCapability.contains("legacyCodegenExecutor"))
        assertFalse(codegenCapability.contains("delegate-legacy-codegen-service"))

        assertFalse(graphSnapshotDocuments.contains("withoutLegacyDraftProjectionArtifacts"))
        assertFalse(graphEditorSnapshot.contains("withoutLegacyDraftProjectionArtifacts"))
        assertFalse(graphEditorViewSupport.contains("withoutLegacyDraftProjectionArtifacts"))
        assertFalse(Files.exists(Path.of("src/main/kotlin/com/charmnight/linkgraph/model/LegacyDraftProjectionArtifacts.kt")))
    }

    @Test
    fun qaRiskModelUsesInvestigationThreadsAsCanonicalRuntimeShape() {
        val workbenchModels = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/workbench/WorkbenchModels.kt"),
        )
        val qaConversationService = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/workbench/QaConversationService.kt"),
        )
        val llmTypes = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/LlmTypes.kt"),
        )
        val reviewWorkflow = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt"),
        )
        val qaModels = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/workbench/QaModels.kt"),
        )
        val graphQaPatchService = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/GraphQaPatchService.kt"),
        )
        val promptFactory = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/LlmPromptFactory.kt"),
        )
        val parser = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/RemoteGraphPatchResultParser.kt"),
        )

        assertFalse(workbenchModels.contains("val investigationLeads: List<${obsoleteQaTypePrefix}InvestigationLead>"))
        assertFalse(workbenchModels.contains("val newInvestigationLeads: List<${obsoleteQaTypePrefix}InvestigationLead>"))
        assertFalse(workbenchModels.contains("data class ${obsoleteQaTypePrefix}InvestigationLead("))
        assertFalse(workbenchModels.contains("${obsoleteQaTypePrefix}InvestigationLeadStatus"))
        assertFalse(workbenchModels.contains("internal fun InvestigationThread.toLeadView()"))
        assertFalse(llmTypes.contains("val investigationLeads: List<${obsoleteQaTypePrefix}InvestigationLead>"))
        assertFalse(llmTypes.contains("val newInvestigationLeads: List<${obsoleteQaTypePrefix}InvestigationLead>"))
        assertFalse(qaConversationService.contains("session.investigationLeads"))
        assertFalse(qaConversationService.contains("modelTurn.investigationLeads"))
        assertFalse(qaConversationService.contains("investigationLeads ="))
        assertFalse(reviewWorkflow.contains("investigationLeads = result.investigationLeads"))
        assertFalse(reviewWorkflow.contains("investigationLeads = turnResult.session.investigationLeads"))
        assertFalse(reviewWorkflow.contains("newInvestigationLeads"))
        assertFalse(qaModels.contains("QaInvestigationLead"))
        assertFalse(graphQaPatchService.contains("${obsoleteQaTypePrefix}InvestigationLead"))
        assertFalse(graphQaPatchService.contains("investigationLeads"))
        assertFalse(promptFactory.contains("investigationLeads"))
        assertFalse(promptFactory.contains("leadId"))
        assertFalse(parser.contains("investigationLeads"))
        assertFalse(parser.contains("leadId"))
    }

    @Test
    fun qaRuntimeAndWorkbenchNamingUseThreadTerminology() {
        val workbenchModels = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/workbench/WorkbenchModels.kt"),
        )
        val qaRequestLifecycleService = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/workbench/QaRequestLifecycleService.kt"),
        )
        val reviewWorkflow = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt"),
        )
        val graphEditorMessage = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorMessage.kt"),
        )
        val graphEditorPageRenderer = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRenderer.kt"),
        )
        val apiSource = Files.readString(Path.of("web/src/app/api.ts"))
        val appSource = Files.readString(Path.of("web/src/app/App.tsx"))
        val assistantThreadSource = Files.readString(Path.of("web/src/app/assistant/AssistantThread.tsx"))
        val typesSource = Files.readString(Path.of("web/src/app/types.ts"))
        val riskThreadCardSource = Files.readString(Path.of("web/src/app/assistant/cards/RiskThreadCard.tsx"))

        assertFalse(workbenchModels.contains("INVESTIGATE_LEAD"))
        assertFalse(workbenchModels.contains("sourceLeadId"))
        val obsoleteLeadSectionId = "$obsoleteQaName.investigation-leads"
        assertFalse(workbenchModels.contains(obsoleteLeadSectionId))
        assertFalse(qaRequestLifecycleService.contains("sourceLeadId"))
        assertFalse(reviewWorkflow.contains("sourceLeadId"))
        assertFalse(graphEditorMessage.contains("sourceLeadId"))
        assertFalse(graphEditorPageRenderer.contains("sourceLeadId"))
        assertFalse(graphEditorPageRenderer.contains(obsoleteLeadSectionId))
        assertFalse(apiSource.contains("sourceLeadId"))
        assertFalse(appSource.contains("selectedLeadId"))
        assertFalse(appSource.contains("handleSelect${obsoleteQaTypePrefix}Lead"))
        assertFalse(appSource.contains("handleInvestigate${obsoleteQaTypePrefix}Lead"))
        assertFalse(appSource.contains(obsoleteLeadSectionId))
        assertFalse(assistantThreadSource.contains("selectedLeadId"))
        assertFalse(assistantThreadSource.contains("InvestigationLeadList"))
        assertFalse(assistantThreadSource.contains("onSelectLead"))
        assertFalse(assistantThreadSource.contains("onInvestigateLead"))
        assertFalse(assistantThreadSource.contains(obsoleteLeadSectionId))
        assertFalse(typesSource.contains("INVESTIGATE_LEAD"))
        assertFalse(typesSource.contains("sourceLeadId"))
        assertFalse(typesSource.contains("selectedLeadId"))
        assertFalse(typesSource.contains(obsoleteLeadSectionId))
        assertFalse(riskThreadCardSource.contains("selectedLeadId"))
        assertFalse(riskThreadCardSource.contains("onSelectLead"))
        assertFalse(riskThreadCardSource.contains("onInvestigateLead"))
    }
}
