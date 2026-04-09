package com.charmnight.linkgraph.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class FrontendAssetLoaderTest {
    @Test
    fun frontendAssetsInlineDirectlyFromClasspathWithoutCreatingTempDirectory() {
        val tempRoot = Path.of(System.getProperty("java.io.tmpdir"))
        val before = existingFrontendTempDirectories(tempRoot)
        val loaderClass = runCatching {
            Class.forName("com.charmnight.linkgraph.ui.ClasspathFrontendAssetLoader")
        }.getOrElse {
            fail("Expected classpath frontend asset loader implementation")
        }
        val loader = loaderClass.getDeclaredConstructor().newInstance()
        val loadInlineEntryHtml = loaderClass.methods.firstOrNull { method ->
            method.name == "loadInlineEntryHtml" && method.parameterCount == 0
        } ?: fail("Expected frontend asset loader to expose loadInlineEntryHtml()")

        val html = loadInlineEntryHtml.invoke(loader) as? String
            ?: fail("Expected inline frontend asset loader to return HTML")
        val after = existingFrontendTempDirectories(tempRoot)

        assertTrue(html.contains("<style>"))
        assertTrue(html.contains("<script type=\"module\">"))
        assertFalse(html.contains("<script src="))
        assertFalse(html.contains("<link rel=\"stylesheet\""))
        assertEquals(before, after)
    }

    private fun existingFrontendTempDirectories(tempRoot: Path): List<String> {
        if (!Files.exists(tempRoot)) {
            return emptyList()
        }
        Files.list(tempRoot).use { children ->
            return children
                .filter { child -> Files.isDirectory(child) && child.name.startsWith("linkgraph-web-") }
                .map { child -> child.fileName.toString() }
                .sorted()
                .toList()
        }
    }
}
