package com.charmnight.linkgraph.architecture

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArchitectureIndexServiceFreshnessTest : BasePlatformTestCase() {
    fun testCachedIndexBuildReportsBuildingAndRestoresStaleWhenBuilderFails() {
        val service = project.architectureIndexService()
        val changedPath = "${project.basePath}/src/main/kotlin/com/example/OrderService.kt"
        service.invalidate("VFS_CHANGE", listOf(changedPath))
        val cacheKey = ArchitectureGraphCacheKey(
            projectLocationHash = 1,
            projectRootModificationCount = 1,
            psiModificationCount = 1,
            includeTests = false,
            includeExternalLibraries = false,
            includeJdk = false,
            includeUserAttachedJars = false,
            maxProjectClasses = 10,
            maxExternalClasses = 0,
            maxMethods = 10,
            maxRelations = 10,
            attachedJars = emptyList(),
        )
        var observedDuringBuild: ArchitectureIndexFreshnessSnapshot? = null

        assertFailsWith<IllegalStateException> {
            service.getOrBuildCachedIndex(cacheKey, forceRebuild = true) {
                observedDuringBuild = service.freshness()
                throw IllegalStateException("index build failed")
            }
        }

        assertEquals("BUILDING", observedDuringBuild?.state)
        assertEquals("VFS_CHANGE", observedDuringBuild?.dirtyReason)
        assertEquals(1, observedDuringBuild?.pendingFileCount)
        val afterFailure = service.freshness()
        assertEquals("STALE", afterFailure.state)
        assertEquals("VFS_CHANGE", afterFailure.dirtyReason)
        assertEquals(1, afterFailure.pendingFileCount)
    }
}
