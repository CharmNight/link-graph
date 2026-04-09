package com.charmnight.linkgraph.navigation

import com.intellij.openapi.vfs.StandardFileSystems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceNavigationPathResolverTest {
    @Test
    fun normalizesArchiveEntryPathsWithAndWithoutBang() {
        assertEquals(
            "/Users/night/.m2/repository/demo.jar!/org/example/Demo.class",
            SourceNavigationPathResolver.normalizeArchiveEntryPath(
                "/Users/night/.m2/repository/demo.jar/org/example/Demo.class",
            ),
        )
        assertEquals(
            "/Users/night/.jdks/zulu17/src.zip!/java/lang/ThreadLocal.java",
            SourceNavigationPathResolver.normalizeArchiveEntryPath(
                "/Users/night/.jdks/zulu17/src.zip/java/lang/ThreadLocal.java",
            ),
        )
        assertEquals(
            "/Users/night/.m2/repository/demo.jar!/org/example/Demo.class",
            SourceNavigationPathResolver.normalizeArchiveEntryPath(
                "/Users/night/.m2/repository/demo.jar!/org/example/Demo.class",
            ),
        )
        assertNull(SourceNavigationPathResolver.normalizeArchiveEntryPath("/Users/night/demo.txt"))
    }

    @Test
    fun buildsJrtUrlsFromExplodedModulePaths() {
        assertEquals(
            "${StandardFileSystems.JRT_PROTOCOL_PREFIX}/Users/night/jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            SourceNavigationPathResolver.buildJrtUrl(
                "/Users/night/jdks/zulu17/java.base/java/lang/ThreadLocal.class",
            ),
        )
        assertEquals(
            "${StandardFileSystems.JRT_PROTOCOL_PREFIX}/Users/night/.jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            SourceNavigationPathResolver.buildJrtUrl(
                "/Users/night/.jdks/zulu17/java.base/java/lang/ThreadLocal.class",
            ),
        )
        assertEquals(
            "${StandardFileSystems.JRT_PROTOCOL_PREFIX}/Users/night/jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            SourceNavigationPathResolver.buildJrtUrl(
                "/Users/night/jdks/zulu17!/java.base/java/lang/ThreadLocal.class",
            ),
        )
        assertNull(SourceNavigationPathResolver.buildJrtUrl("/Users/night/project/src/main/java/com/example/OrderService.java"))
    }
}
