package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 负责从开发 dist 或插件 classpath 读取前端入口与静态资源。
 */
internal interface FrontendAssetLoader {
    fun loadEntryHtml(): String

    fun loadAsset(assetReference: String): FrontendAsset?
}

/**
 * 前端资源内容及其响应 MIME 类型。
 */
internal data class FrontendAsset(
    val bytes: ByteArray,
    val mimeType: String,
)

/**
 * 直接从插件 classpath 读取 `linkgraph/index.html` 及其静态资源。
 */
internal class ClasspathFrontendAssetLoader(
    private val classLoader: ClassLoader = ClasspathFrontendAssetLoader::class.java.classLoader,
    private val developmentDistRoot: Path? = null,
    private val loadingMode: FrontendAssetLoadingMode = FrontendAssetLoadingMode.PACKAGED_ONLY,
) : FrontendAssetLoader {
    override fun loadEntryHtml(): String =
        readResourceText("linkgraph/index.html")

    override fun loadAsset(assetReference: String): FrontendAsset? {
        val normalized = FrontendAssetReferencePolicy.normalizeAssetReference(assetReference) ?: return null
        val bytes = readResourceBytesOrNull("linkgraph/$normalized") ?: return null
        return FrontendAsset(
            bytes = bytes,
            mimeType = mimeTypeFor(normalized),
        )
    }

    private fun readResourceText(resourcePath: String): String {
        return String(readResourceBytes(resourcePath), StandardCharsets.UTF_8)
    }

    private fun readResourceBytes(resourcePath: String): ByteArray =
        readResourceBytesOrNull(resourcePath) ?: error("未找到前端资源: $resourcePath")

    private fun readResourceBytesOrNull(resourcePath: String): ByteArray? {
        if (loadingMode == FrontendAssetLoadingMode.DEV_DIST_THEN_PACKAGED) {
            readDevelopmentResourceBytesOrNull(resourcePath)?.let { return it }
        }
        return classLoader.getResourceAsStream(resourcePath)?.use { stream ->
            stream.readBytes()
        }
    }

    private fun readDevelopmentResourceBytesOrNull(resourcePath: String): ByteArray? {
        val developmentRoot = developmentDistRoot?.toAbsolutePath()?.normalize() ?: return null
        val developmentPath = developmentRoot
            .resolve(resourcePath.removePrefix("linkgraph/"))
            .normalize()
        if (!developmentPath.startsWith(developmentRoot)) {
            return null
        }
        if (Files.isRegularFile(developmentPath)) {
            return Files.readAllBytes(developmentPath)
        }
        return null
    }

    companion object {
        private const val DEV_DIST_ENV = "LINKGRAPH_FRONTEND_DEV_DIST"
        private const val DEV_DIST_PROPERTY = "linkgraph.frontend.devDist"

        fun development(
            classLoader: ClassLoader = ClasspathFrontendAssetLoader::class.java.classLoader,
            developmentDistRoot: Path = Path.of("web/dist"),
        ): ClasspathFrontendAssetLoader =
            ClasspathFrontendAssetLoader(
                classLoader = classLoader,
                developmentDistRoot = developmentDistRoot,
                loadingMode = FrontendAssetLoadingMode.DEV_DIST_THEN_PACKAGED,
            )

        fun runtime(
            classLoader: ClassLoader = ClasspathFrontendAssetLoader::class.java.classLoader,
            environment: Map<String, String> = System.getenv(),
            propertyValue: String? = System.getProperty(DEV_DIST_PROPERTY),
        ): ClasspathFrontendAssetLoader {
            val propertyToken = propertyValue
                ?.trim()
                ?.takeIf(String::isNotBlank)
            val envToken = LinkGraphDebugEnvironment.value(DEV_DIST_ENV, environment)
                ?.trim()
                ?.takeIf(String::isNotBlank)
            val developmentRoot = developmentDistRoot(propertyToken)
                ?: developmentDistRoot(envToken)
            return if (developmentRoot != null) {
                development(
                    classLoader = classLoader,
                    developmentDistRoot = developmentRoot,
                )
            } else {
                ClasspathFrontendAssetLoader(classLoader = classLoader)
            }
        }

        private fun developmentDistRoot(value: String?): Path? {
            val token = value?.trim()?.takeIf(String::isNotBlank) ?: return null
            return when {
                token.equals("true", ignoreCase = true) -> Path.of("web/dist")
                token.equals("false", ignoreCase = true) -> null
                token.equals("0", ignoreCase = true) -> null
                token.equals("no", ignoreCase = true) -> null
                token.equals("off", ignoreCase = true) -> null
                else -> Path.of(token)
            }
        }

        private fun mimeTypeFor(path: String): String =
            when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
                "html" -> "text/html"
                "js", "mjs" -> "application/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "wasm" -> "application/wasm"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> "application/octet-stream"
            }
    }
}

internal enum class FrontendAssetLoadingMode {
    PACKAGED_ONLY,
    DEV_DIST_THEN_PACKAGED,
}
