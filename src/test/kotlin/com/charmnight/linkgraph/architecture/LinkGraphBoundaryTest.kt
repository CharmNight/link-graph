package com.charmnight.linkgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphBoundaryTest {
    @Test
    fun projectServiceDoesNotOwnToolwindowOrBrowserAndAppUsesDedicatedStateHooksAndControllers() {
        val projectService = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt"),
        )
        val openAction = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/actions/OpenLinkGraphAction.kt"),
        )
        val appSource = Files.readString(Path.of("web/src/app/App.tsx"))

        assertFalse(projectService.contains("GraphBrowserPanel"))
        assertFalse(projectService.contains("LinkGraphToolWindowSession"))

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
            Path.of("src/main/kotlin/com/charmnight/linkgraph/services/GraphSnapshotDocuments.kt"),
        )
        val graphEditorSnapshot = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorSnapshot.kt"),
        )
        val graphEditorViewSupport = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorViewSupport.kt"),
        )

        assertFalse(qaCapability.contains("legacyAuditExecutor"))
        assertFalse(qaCapability.contains("delegate-legacy-audit-service"))
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
        val auditConversationService = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/workbench/AuditConversationService.kt"),
        )
        val llmTypes = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/LlmTypes.kt"),
        )
        val reviewWorkflow = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/services/ReviewWorkflow.kt"),
        )
        val qaModels = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/workbench/QaModels.kt"),
        )
        val graphAuditPatchService = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/GraphAuditPatchService.kt"),
        )
        val promptFactory = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/LlmPromptFactory.kt"),
        )
        val parser = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/RemoteGraphPatchResultParser.kt"),
        )

        assertFalse(workbenchModels.contains("val investigationLeads: List<AuditInvestigationLead>"))
        assertFalse(workbenchModels.contains("val newInvestigationLeads: List<AuditInvestigationLead>"))
        assertFalse(workbenchModels.contains("data class AuditInvestigationLead("))
        assertFalse(workbenchModels.contains("AuditInvestigationLeadStatus"))
        assertFalse(workbenchModels.contains("internal fun InvestigationThread.toLeadView()"))
        assertFalse(llmTypes.contains("val investigationLeads: List<AuditInvestigationLead>"))
        assertFalse(llmTypes.contains("val newInvestigationLeads: List<AuditInvestigationLead>"))
        assertFalse(auditConversationService.contains("session.investigationLeads"))
        assertFalse(auditConversationService.contains("modelTurn.investigationLeads"))
        assertFalse(auditConversationService.contains("investigationLeads ="))
        assertFalse(reviewWorkflow.contains("investigationLeads = result.investigationLeads"))
        assertFalse(reviewWorkflow.contains("investigationLeads = turnResult.session.investigationLeads"))
        assertFalse(reviewWorkflow.contains("newInvestigationLeads"))
        assertFalse(qaModels.contains("QaInvestigationLead"))
        assertFalse(graphAuditPatchService.contains("AuditInvestigationLead"))
        assertFalse(graphAuditPatchService.contains("investigationLeads"))
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
            Path.of("src/main/kotlin/com/charmnight/linkgraph/services/ReviewWorkflow.kt"),
        )
        val graphEditorMessage = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorMessage.kt"),
        )
        val graphEditorPageRenderer = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRenderer.kt"),
        )
        val apiSource = Files.readString(Path.of("web/src/app/api.ts"))
        val appSource = Files.readString(Path.of("web/src/app/App.tsx"))
        val auditTabSource = Files.readString(Path.of("web/src/app/workbench/AuditTab.tsx"))
        val typesSource = Files.readString(Path.of("web/src/app/types.ts"))
        val investigationThreadListSource = Files.readString(Path.of("web/src/app/workbench/InvestigationThreadList.tsx"))
        val workbenchSectionsSource = Files.readString(Path.of("web/src/app/workbench/workbenchSections.ts"))

        assertFalse(workbenchModels.contains("INVESTIGATE_LEAD"))
        assertFalse(workbenchModels.contains("sourceLeadId"))
        assertFalse(workbenchModels.contains("audit.investigation-leads"))
        assertFalse(qaRequestLifecycleService.contains("sourceLeadId"))
        assertFalse(reviewWorkflow.contains("sourceLeadId"))
        assertFalse(graphEditorMessage.contains("sourceLeadId"))
        assertFalse(graphEditorPageRenderer.contains("sourceLeadId"))
        assertFalse(graphEditorPageRenderer.contains("audit.investigation-leads"))
        assertFalse(apiSource.contains("sourceLeadId"))
        assertFalse(appSource.contains("selectedLeadId"))
        assertFalse(appSource.contains("handleSelectAuditLead"))
        assertFalse(appSource.contains("handleInvestigateAuditLead"))
        assertFalse(appSource.contains("audit.investigation-leads"))
        assertFalse(auditTabSource.contains("selectedLeadId"))
        assertFalse(auditTabSource.contains("InvestigationLeadList"))
        assertFalse(auditTabSource.contains("onSelectLead"))
        assertFalse(auditTabSource.contains("onInvestigateLead"))
        assertFalse(auditTabSource.contains("audit.investigation-leads"))
        assertFalse(typesSource.contains("INVESTIGATE_LEAD"))
        assertFalse(typesSource.contains("sourceLeadId"))
        assertFalse(typesSource.contains("selectedLeadId"))
        assertFalse(typesSource.contains("audit.investigation-leads"))
        assertFalse(investigationThreadListSource.contains("selectedLeadId"))
        assertFalse(investigationThreadListSource.contains("onSelectLead"))
        assertFalse(investigationThreadListSource.contains("onInvestigateLead"))
        assertFalse(workbenchSectionsSource.contains("audit.investigation-leads"))
    }
}
