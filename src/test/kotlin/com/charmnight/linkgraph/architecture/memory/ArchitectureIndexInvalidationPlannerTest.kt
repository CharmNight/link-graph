package com.charmnight.linkgraph.architecture.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureIndexInvalidationPlannerTest {
    @Test
    fun marksOnlySlicesOwningChangedFilesStale() {
        val sourceSlice = slice("source", ProjectSliceKind.JVM_SOURCE, "src/main/java/com/example/OrderService.java")
        val resourceSlice = slice("resource", ProjectSliceKind.RESOURCE, "src/main/resources/application.yml")
        val planner = ArchitectureIndexInvalidationPlanner()

        val plan = planner.plan(
            manifest = ProjectSliceManifest("project", ProjectSliceManifest.CURRENT_SCHEMA_VERSION, listOf(sourceSlice, resourceSlice)),
            changes = listOf(ProjectFileChange("src/main/java/com/example/OrderService.java")),
        )

        assertEquals(setOf("source"), plan.staleSliceIds)
        assertEquals(false, plan.fullInvalidation)
    }

    @Test
    fun rootSettingsAndAttachedJarChangesForceFullInvalidation() {
        val planner = ArchitectureIndexInvalidationPlanner()
        val manifest = ProjectSliceManifest("project", ProjectSliceManifest.CURRENT_SCHEMA_VERSION, emptyList())

        listOf("settings.gradle.kts", ".idea/modules.xml", "libs/client.jar").forEach { path ->
            val plan = planner.plan(manifest, listOf(ProjectFileChange(path)))
            assertTrue(plan.fullInvalidation, "$path should force full invalidation")
        }
    }

    private fun slice(id: String, kind: ProjectSliceKind, path: String): ProjectSlice =
        ProjectSlice(
            id = id,
            moduleName = "orders",
            contentRoot = "/repo",
            sourceSet = "main",
            packagePrefix = null,
            kind = kind.name,
            files = listOf(ProjectFileFingerprint(path, 1, 1, null)),
        )
}
