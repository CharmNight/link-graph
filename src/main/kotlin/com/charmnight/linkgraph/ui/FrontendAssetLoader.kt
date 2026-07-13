package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.source.readBytesBounded
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 负责从开发 dist 或插件 classpath 读取前端入口与静态资源。
 */
internal interface FrontendAssetLoader {
    /** 读取前端入口 HTML 文本，作为内嵌页面加载到工具窗口中。 */
    fun loadEntryHtml(): String

    /** 根据前端引用路径读取对应的静态资源，找不到时返回 null。 */
    fun loadAsset(assetReference: String): FrontendAsset?
}

/**
 * 前端资源内容及其响应 MIME 类型。
 */
internal data class FrontendAsset(
    /** 资源的原始字节内容，按二进制原样返回，避免对图片、字体等做编码处理。 */
    val bytes: ByteArray,
    /** 资源对应的 MIME 类型，用于 JCEF 响应头。 */
    val mimeType: String,
)

/**
 * 直接从插件 classpath 读取 `linkgraph/index.html` 及其静态资源。
 */
internal class ClasspathFrontendAssetLoader(
    /** 用于读取打包资源的类加载器，默认取当前类的类加载器。 */
    private val classLoader: ClassLoader = ClasspathFrontendAssetLoader::class.java.classLoader,
    /** 开发态 dist 目录根路径，开启开发模式时会优先从这里读取资源。 */
    private val developmentDistRoot: Path? = null,
    /** 资源加载模式：仅打包资源，或先读 dist 再回退到打包资源。 */
    private val loadingMode: FrontendAssetLoadingMode = FrontendAssetLoadingMode.PACKAGED_ONLY,
) : FrontendAssetLoader {
    /** 读取 linkgraph/index.html 入口文件，按 UTF-8 文本返回。 */
    override fun loadEntryHtml(): String =
        readResourceText("linkgraph/index.html")

    /** 规范化前端资源引用并读取对应的字节内容，再附带 MIME 类型一并返回。 */
    override fun loadAsset(assetReference: String): FrontendAsset? {
        val normalized = FrontendAssetReferencePolicy.normalizeAssetReference(assetReference) ?: return null
        val bytes = readResourceBytesOrNull("linkgraph/$normalized") ?: return null
        return FrontendAsset(
            bytes = bytes,
            mimeType = mimeTypeFor(normalized),
        )
    }

    /** 以 UTF-8 解码资源字节流，仅用于 HTML 等纯文本资源。 */
    private fun readResourceText(resourcePath: String): String {
        return String(readResourceBytes(resourcePath), StandardCharsets.UTF_8)
    }

    /** 强制读取资源字节流，找不到时抛出明确错误，用于必须存在的资源。 */
    private fun readResourceBytes(resourcePath: String): ByteArray =
        readResourceBytesOrNull(resourcePath) ?: error("未找到前端资源: $resourcePath")

    /** 按加载模式尝试读取资源字节流；若开启了开发模式则优先从 dist 目录读取，找不到再回退到 classpath。 */
    private fun readResourceBytesOrNull(resourcePath: String): ByteArray? {
        if (loadingMode == FrontendAssetLoadingMode.DEV_DIST_THEN_PACKAGED) {
            readDevelopmentResourceBytesOrNull(resourcePath)?.let { return it }
        }
        return classLoader.getResourceAsStream(resourcePath)?.use { stream ->
            stream.readBytesBounded(MAX_FRONTEND_ASSET_BYTES)
        }
    }

    /** 在开发态 dist 目录下定位并读取资源；若解析后路径越出根目录或不存在则返回 null。 */
    private fun readDevelopmentResourceBytesOrNull(resourcePath: String): ByteArray? {
        val developmentRoot = developmentDistRoot?.toAbsolutePath()?.normalize() ?: return null
        val developmentPath = developmentRoot
            .resolve(resourcePath.removePrefix("linkgraph/"))
            .normalize()
        if (!developmentPath.startsWith(developmentRoot)) {
            return null
        }
        if (Files.isRegularFile(developmentPath) && Files.size(developmentPath) <= MAX_FRONTEND_ASSET_BYTES) {
            return Files.newInputStream(developmentPath).use { stream ->
                stream.readBytesBounded(MAX_FRONTEND_ASSET_BYTES)
            }
        }
        return null
    }

    companion object {
        /** 控制开发态 dist 根路径的环境变量名。 */
        private const val DEV_DIST_ENV = "LINKGRAPH_FRONTEND_DEV_DIST"
        /** 控制开发态 dist 根路径的系统属性名。 */
        private const val DEV_DIST_PROPERTY = "linkgraph.frontend.devDist"
        /** 单个前端资源读取上限，避免错误 devDist 或异常资源拖垮 JCEF 响应。 */
        private const val MAX_FRONTEND_ASSET_BYTES = 4 * 1024 * 1024

        /** 构造开发态加载器：优先从指定 dist 目录读取，未命中再回退到打包资源。 */
        fun development(
            classLoader: ClassLoader = ClasspathFrontendAssetLoader::class.java.classLoader,
            developmentDistRoot: Path = Path.of("web/dist"),
        ): ClasspathFrontendAssetLoader =
            ClasspathFrontendAssetLoader(
                classLoader = classLoader,
                developmentDistRoot = developmentDistRoot,
                loadingMode = FrontendAssetLoadingMode.DEV_DIST_THEN_PACKAGED,
            )

        /** 根据运行时环境变量与系统属性决定是否启用开发模式，并构造对应的加载器。 */
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

        /** 把环境变量或系统属性的值解释成 dist 根路径，识别 true/false/0/no/off 等开关型取值。 */
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

        /** 依据扩展名推断 MIME 类型，覆盖前端常用静态资源；未命中时回退为通用二进制流。 */
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

/** 前端资源加载模式枚举。 */
internal enum class FrontendAssetLoadingMode {
    /** 仅从打包后的 classpath 读取，适用于正式发布的插件包。 */
    PACKAGED_ONLY,
    /** 先尝试开发态 dist 目录，未命中再回退到打包资源。 */
    DEV_DIST_THEN_PACKAGED,
}
