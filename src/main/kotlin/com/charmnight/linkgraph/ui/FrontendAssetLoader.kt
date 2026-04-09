package com.charmnight.linkgraph.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 负责从 classpath 直接读取并内联前端入口资源。
 */
internal interface FrontendAssetLoader {
    fun loadInlineEntryHtml(): String
}

/**
 * 直接从插件 classpath 读取 `linkgraph/index.html` 及其静态资源，避免导出到临时目录。
 */
internal class ClasspathFrontendAssetLoader(
    private val classLoader: ClassLoader = ClasspathFrontendAssetLoader::class.java.classLoader,
) : FrontendAssetLoader {
    override fun loadInlineEntryHtml(): String {
        var html = readResourceText("linkgraph/index.html")
        html = LINK_TAG_REGEX.replace(html) { match ->
            val href = match.groupValues[1]
            if (!href.endsWith(".css")) {
                match.value
            } else {
                "<style>\n${readAssetText(href)}\n</style>"
            }
        }
        html = SCRIPT_TAG_REGEX.replace(html) { match ->
            val src = match.groupValues[1]
            if (!src.endsWith(".js")) {
                match.value
            } else {
                """
                <script type="module">
                ${readAssetText(src)}
                </script>
                """.trimIndent()
            }
        }
        return html
    }

    private fun readAssetText(assetReference: String): String {
        val normalized = assetReference.removePrefix("./").removePrefix("/")
        return readResourceText("linkgraph/$normalized")
    }

    private fun readResourceText(resourcePath: String): String {
        val stream = classLoader.getResourceAsStream(resourcePath)
        if (stream != null) {
            return stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.readText()
            }
        }
        val developmentPath = Path.of("web/dist").resolve(resourcePath.removePrefix("linkgraph/"))
        if (Files.exists(developmentPath)) {
            return Files.readString(developmentPath, StandardCharsets.UTF_8)
        }
        error("未找到前端资源: $resourcePath")
    }

    private companion object {
        private val LINK_TAG_REGEX = Regex("""<link[^>]*href="([^"]+)"[^>]*>""")
        private val SCRIPT_TAG_REGEX = Regex("""<script[^>]*src="([^"]+)"[^>]*></script>""")
    }
}
