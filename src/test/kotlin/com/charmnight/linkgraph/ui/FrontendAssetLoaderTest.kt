package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.testing.*

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
    fun frontendAssetsLoadDirectlyFromClasspathWithoutCreatingTempDirectory() {
        val tempRoot = Path.of(System.getProperty("java.io.tmpdir"))
        val before = existingFrontendTempDirectories(tempRoot)
        val loaderClass = runCatching {
            Class.forName("com.charmnight.linkgraph.ui.ClasspathFrontendAssetLoader")
        }.getOrElse {
            fail("Expected classpath frontend asset loader implementation")
        }
        val loader = loaderClass.getDeclaredConstructor().newInstance()
        val loadEntryHtml = loaderClass.methods.firstOrNull { method ->
            method.name == "loadEntryHtml" && method.parameterCount == 0
        } ?: fail("Expected frontend asset loader to expose loadEntryHtml()")

        val html = loadEntryHtml.invoke(loader) as? String
            ?: fail("Expected frontend asset loader to return HTML")
        val after = existingFrontendTempDirectories(tempRoot)

        assertTrue(html.contains("<script type=\"module\""))
        assertTrue(html.contains("<link rel=\"stylesheet\""))
        assertFalse(html.contains("<script type=\"module\">\n"))
        assertFalse(html.contains("<style>"))
        assertEquals(before, after)
    }

    @Test
    fun defaultLoaderPrefersPackagedClasspathEvenWhenDevelopmentDistExists() {
        val tempDir = Files.createTempDirectory("linkgraph-frontend-loader-default")
        try {
            val distDir = tempDir.resolve("web/dist/assets")
            Files.createDirectories(distDir)
            Files.writeString(tempDir.resolve("web/dist/index.html"), "<html>dev-dist</html>")
            Files.writeString(distDir.resolve("index.css"), ".from-dev { color: green; }")
            Files.writeString(distDir.resolve("index.js"), "window.__assetSource = 'dev-dist';")
            val loader = ClasspathFrontendAssetLoader(
                classLoader = classpathResourceLoader(),
                developmentDistRoot = tempDir.resolve("web/dist"),
            )

            val html = loader.loadEntryHtml()
            val css = loader.loadAsset("assets/index.css")
            val js = loader.loadAsset("/assets/index.js")

            assertTrue(html.contains("<script type=\"module\" src=\"./assets/index.js\"></script>"))
            assertEquals(".from-classpath { color: red; }", css?.text())
            assertEquals("window.__assetSource = 'classpath';", js?.text())
            assertFalse(html.contains("dev-dist"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun explicitDevelopmentLoaderResolvesChunkAndWorkerResourcesFromDevelopmentDistBeforeClasspath() {
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
            Files.writeString(distDir.resolve("elk-worker.js"), "self.onmessage = () => undefined;")

            val loader = ClasspathFrontendAssetLoader.development(
                classLoader = classpathResourceLoader(),
                developmentDistRoot = tempDir.resolve("web/dist"),
            )

            val html = loader.loadEntryHtml()
            val css = loader.loadAsset("assets/index.css")
            val js = loader.loadAsset("/assets/index.js")
            val worker = loader.loadAsset("./assets/elk-worker.js")

            assertTrue(html.contains("<script type=\"module\" src=\"./assets/index.js\"></script>"))
            assertEquals(".from-dev { color: green; }", css?.text())
            assertEquals("window.__assetSource = 'dev-dist';", js?.text())
            assertEquals("self.onmessage = () => undefined;", worker?.text())
            assertFalse(html.contains(".from-classpath { color: red; }"))
            assertFalse(html.contains("window.__assetSource = 'classpath';"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun explicitDevelopmentLoaderFallsBackToClasspathWhenDistAssetIsMissing() {
        val tempDir = Files.createTempDirectory("linkgraph-frontend-loader-fallback")
        try {
            Files.createDirectories(tempDir.resolve("web/dist/assets"))
            Files.writeString(tempDir.resolve("web/dist/index.html"), "<html>dev shell</html>")
            val loader = ClasspathFrontendAssetLoader.development(
                classLoader = classpathResourceLoader(),
                developmentDistRoot = tempDir.resolve("web/dist"),
            )

            val css = loader.loadAsset("assets/index.css")
            val js = loader.loadAsset("assets/index.js")

            assertEquals(".from-classpath { color: red; }", css?.text())
            assertEquals("window.__assetSource = 'classpath';", js?.text())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun explicitDevelopmentLoaderFallsBackWhenDistAssetIsOversized() {
        val tempDir = Files.createTempDirectory("linkgraph-frontend-loader-oversized")
        try {
            val distDir = tempDir.resolve("web/dist/assets")
            Files.createDirectories(distDir)
            Files.writeString(tempDir.resolve("web/dist/index.html"), "<html>dev shell</html>")
            Files.writeString(distDir.resolve("index.js"), "x".repeat(4 * 1024 * 1024 + 1))
            val loader = ClasspathFrontendAssetLoader.development(
                classLoader = classpathResourceLoader(),
                developmentDistRoot = tempDir.resolve("web/dist"),
            )

            val js = loader.loadAsset("assets/index.js")

            assertEquals("window.__assetSource = 'classpath';", js?.text())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun runtimeLoaderUsesDevelopmentDistOnlyWhenExplicitEnvironmentPathIsProvided() {
        val tempDir = Files.createTempDirectory("linkgraph-frontend-loader-runtime")
        try {
            val distDir = tempDir.resolve("web/dist/assets")
            Files.createDirectories(distDir)
            Files.writeString(tempDir.resolve("web/dist/index.html"), "<html>runtime-dev</html>")
            Files.writeString(distDir.resolve("index.js"), "window.__assetSource = 'runtime-dev';")

            val defaultLoader = ClasspathFrontendAssetLoader.runtime(
                classLoader = classpathResourceLoader(),
                environment = emptyMap(),
                propertyValue = null,
            )
            val developmentLoader = ClasspathFrontendAssetLoader.runtime(
                classLoader = classpathResourceLoader(),
                environment = mapOf("LINKGRAPH_FRONTEND_DEV_DIST" to tempDir.resolve("web/dist").toString()),
                propertyValue = null,
            )

            assertEquals("window.__assetSource = 'classpath';", defaultLoader.loadAsset("assets/index.js")?.text())
            assertEquals("window.__assetSource = 'runtime-dev';", developmentLoader.loadAsset("assets/index.js")?.text())
            assertTrue(developmentLoader.loadEntryHtml().contains("runtime-dev"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun runtimeLoaderTreatsFalseEnvironmentTokenAsDisabled() {
        val disabledTokenRoot = Path.of("false")
        if (Files.exists(disabledTokenRoot)) {
            fail("Test requires no existing relative path named false")
        }
        try {
            Files.createDirectories(disabledTokenRoot.resolve("assets"))
            Files.writeString(disabledTokenRoot.resolve("index.html"), "<html>disabled-dev</html>")
            Files.writeString(disabledTokenRoot.resolve("assets/index.js"), "window.__assetSource = 'disabled-dev';")

            val loader = ClasspathFrontendAssetLoader.runtime(
                classLoader = classpathResourceLoader(),
                environment = mapOf("LINKGRAPH_FRONTEND_DEV_DIST" to "false"),
                propertyValue = null,
            )

            assertEquals("window.__assetSource = 'classpath';", loader.loadAsset("assets/index.js")?.text())
            assertFalse(loader.loadEntryHtml().contains("disabled-dev"))
        } finally {
            disabledTokenRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun assetRequestRejectsPathTraversal() {
        val tempDir = Files.createTempDirectory("linkgraph-frontend-loader-empty")
        try {
            Files.writeString(tempDir.resolve("index.html"), "<html>outside assets</html>")
            Files.createDirectories(tempDir.resolve("assets/chunk-dir"))
            Files.writeString(tempDir.resolve("assets/index.js"), "window.__assetSource = 'dev-dist';")
            val loader = ClasspathFrontendAssetLoader(
                classLoader = object : ClassLoader(null) {
                    override fun getResourceAsStream(name: String?) = when (name) {
                        "linkgraph/index.html" -> byteStream("<html></html>")
                        else -> null
                    }
                },
                developmentDistRoot = tempDir,
            )

            assertEquals(null, loader.loadAsset("../secrets.txt"))
            assertEquals(null, loader.loadAsset("assets/../../secrets.txt"))
            assertEquals(null, loader.loadAsset("assets/../index.html"))
            assertEquals(null, loader.loadAsset("assets/%2e%2e/index.html"))
            assertEquals(null, loader.loadAsset("assets/%5c..%5cindex.html"))
            assertEquals(null, loader.loadAsset("assets/%00.js"))
            assertEquals(null, loader.loadAsset("""assets\..\index.html"""))
            assertEquals(null, loader.loadAsset("https://example.com/assets/index.js"))
            assertEquals(null, loader.loadAsset("//example.com/assets/index.js"))
            assertEquals(null, loader.loadAsset("assets"))
            assertEquals(null, loader.loadAsset("assets/chunk-dir"))
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

    private fun classpathResourceLoader(): ClassLoader =
        object : ClassLoader(null) {
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
                "linkgraph/assets/elk-worker.js" -> byteStream("self.onmessage = () => 'classpath';")
                else -> null
            }
        }

    private fun byteStream(content: String) = content.byteInputStream(StandardCharsets.UTF_8)

    private fun FrontendAsset.text(): String = String(bytes, StandardCharsets.UTF_8)
}
