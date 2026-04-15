package com.charmnight.linkgraph.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
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

    @Test
    fun developmentDistTakesPrecedenceOverClasspathAssetsWhenPresent() {
        val tempDir = Files.createTempDirectory("linkgraph-frontend-loader-test")
        try {
            val distDir = tempDir.resolve("web/dist/assets")
            Files.createDirectories(distDir)
            Files.writeString(
                tempDir.resolve("web/dist/index.html"),
                """
                <html>
                  <head>
                    <link rel="stylesheet" href="./assets/index.css">
                  </head>
                  <body>
                    <script type="module" src="./assets/index.js"></script>
                  </body>
                </html>
                """.trimIndent(),
            )
            Files.writeString(distDir.resolve("index.css"), ".from-dev { color: green; }")
            Files.writeString(distDir.resolve("index.js"), "window.__assetSource = 'dev-dist';")

            val loader = ClasspathFrontendAssetLoader(
                classLoader = object : ClassLoader(null) {
                    override fun getResourceAsStream(name: String?) = when (name) {
                        "linkgraph/index.html" -> byteStream(
                            """
                            <html>
                              <head>
                                <link rel="stylesheet" href="./assets/index.css">
                              </head>
                              <body>
                                <script type="module" src="./assets/index.js"></script>
                              </body>
                            </html>
                            """.trimIndent(),
                        )

                        "linkgraph/assets/index.css" -> byteStream(".from-classpath { color: red; }")
                        "linkgraph/assets/index.js" -> byteStream("window.__assetSource = 'classpath';")
                        else -> null
                    }
                },
                developmentDistRoot = tempDir.resolve("web/dist"),
            )

            val html = loader.loadInlineEntryHtml()

            assertTrue(html.contains(".from-dev { color: green; }"))
            assertTrue(html.contains("window.__assetSource = 'dev-dist';"))
            assertFalse(html.contains(".from-classpath { color: red; }"))
            assertFalse(html.contains("window.__assetSource = 'classpath';"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
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

    private fun byteStream(content: String) = content.byteInputStream(StandardCharsets.UTF_8)
}
