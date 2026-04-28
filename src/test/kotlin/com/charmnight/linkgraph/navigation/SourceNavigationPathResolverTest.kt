package com.charmnight.linkgraph.navigation

import com.charmnight.linkgraph.testing.*

import com.intellij.openapi.vfs.StandardFileSystems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceNavigationPathResolverTest {
    @Test
    fun normalizesArchiveEntryPathsWithAndWithoutBang() {
        assertEquals(
            "/home/example/link-graph-test/.m2/repository/demo.jar!/org/example/Demo.class",
            SourceNavigationPathResolver.normalizeArchiveEntryPath(
                "/home/example/link-graph-test/.m2/repository/demo.jar/org/example/Demo.class",
            ),
        )
        assertEquals(
            "/home/example/link-graph-test/.jdks/zulu17/src.zip!/java/lang/ThreadLocal.java",
            SourceNavigationPathResolver.normalizeArchiveEntryPath(
                "/home/example/link-graph-test/.jdks/zulu17/src.zip/java/lang/ThreadLocal.java",
            ),
        )
        assertEquals(
            "/home/example/link-graph-test/.m2/repository/demo.jar!/org/example/Demo.class",
            SourceNavigationPathResolver.normalizeArchiveEntryPath(
                "/home/example/link-graph-test/.m2/repository/demo.jar!/org/example/Demo.class",
            ),
        )
        assertNull(SourceNavigationPathResolver.normalizeArchiveEntryPath("/home/example/link-graph-test/demo.txt"))
    }

    @Test
    fun buildsJrtUrlsFromExplodedModulePaths() {
        assertEquals(
            "${StandardFileSystems.JRT_PROTOCOL_PREFIX}/home/example/link-graph-test/jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            SourceNavigationPathResolver.buildJrtUrl(
                "/home/example/link-graph-test/jdks/zulu17/java.base/java/lang/ThreadLocal.class",
            ),
        )
        assertEquals(
            "${StandardFileSystems.JRT_PROTOCOL_PREFIX}/home/example/link-graph-test/.jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            SourceNavigationPathResolver.buildJrtUrl(
                "/home/example/link-graph-test/.jdks/zulu17/java.base/java/lang/ThreadLocal.class",
            ),
        )
        assertEquals(
            "${StandardFileSystems.JRT_PROTOCOL_PREFIX}/home/example/link-graph-test/jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            SourceNavigationPathResolver.buildJrtUrl(
                "/home/example/link-graph-test/jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            ),
        )
        assertNull(SourceNavigationPathResolver.buildJrtUrl("/home/example/link-graph-test/project/src/main/java/com/example/OrderService.java"))
    }
}
