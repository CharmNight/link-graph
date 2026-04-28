package com.charmnight.linkgraph.navigation

import com.intellij.openapi.vfs.StandardFileSystems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceNavigationPathResolverTest {
    private val testRoot = "/home/example/link-graph-test"

    @Test
    fun normalizesArchiveEntryPathsWithAndWithoutBang() {
        assertEquals(
            "$testRoot/.m2/repository/demo.jar!/org/example/Demo.class",
            SourceNavigationPathResolver.normalizeArchiveEntryPath(
                "$testRoot/.m2/repository/demo.jar/org/example/Demo.class",
            ),
        )
        assertEquals(
            "$testRoot/jdks/zulu17/src.zip!/java/lang/ThreadLocal.java",
            SourceNavigationPathResolver.normalizeArchiveEntryPath(
                "$testRoot/jdks/zulu17/src.zip/java/lang/ThreadLocal.java",
            ),
        )
        assertEquals(
            "$testRoot/.m2/repository/demo.jar!/org/example/Demo.class",
            SourceNavigationPathResolver.normalizeArchiveEntryPath(
                "$testRoot/.m2/repository/demo.jar!/org/example/Demo.class",
            ),
        )
        assertNull(SourceNavigationPathResolver.normalizeArchiveEntryPath("$testRoot/demo.txt"))
    }

    @Test
    fun buildsJrtUrlsFromExplodedModulePaths() {
        assertEquals(
            "${StandardFileSystems.JRT_PROTOCOL_PREFIX}$testRoot/jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            SourceNavigationPathResolver.buildJrtUrl(
                "$testRoot/jdks/zulu17/java.base/java/lang/ThreadLocal.class",
            ),
        )
        assertEquals(
            "${StandardFileSystems.JRT_PROTOCOL_PREFIX}$testRoot/.jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            SourceNavigationPathResolver.buildJrtUrl(
                "$testRoot/.jdks/zulu17/java.base/java/lang/ThreadLocal.class",
            ),
        )
        assertEquals(
            "${StandardFileSystems.JRT_PROTOCOL_PREFIX}$testRoot/jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            SourceNavigationPathResolver.buildJrtUrl(
                "$testRoot/jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            ),
        )
        assertNull(SourceNavigationPathResolver.buildJrtUrl("$testRoot/project/src/main/java/com/example/OrderService.java"))
    }
}
