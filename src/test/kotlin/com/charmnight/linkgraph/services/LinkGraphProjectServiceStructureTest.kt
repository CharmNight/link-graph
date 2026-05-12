package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphProjectServiceStructureTest {
    @Test
    fun legacyFacadeIsDeletedAndPlanningHelpersRemainInDedicatedCollaborators() {
        assertFalse(
            Files.exists(Path.of("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt")),
            "LinkGraphProjectService production facade must stay deleted.",
        )

        assertTrue(
            Files.exists(Path.of("src/main/kotlin/com/charmnight/linkgraph/application/planning/PlanningContextFactory.kt")),
            "Planning context construction should live in a dedicated application planning collaborator.",
        )
        assertTrue(
            Files.exists(Path.of("src/main/kotlin/com/charmnight/linkgraph/application/workflow/SubjectGraphWorkflow.kt")),
            "Subject graph orchestration should live under the application workflow boundary.",
        )
    }
}
