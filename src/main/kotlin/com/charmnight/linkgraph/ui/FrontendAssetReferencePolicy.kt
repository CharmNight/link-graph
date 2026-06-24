package com.charmnight.linkgraph.ui

import java.net.URI
import java.nio.file.Path

/**
 * 前端资源（assets/）引用策略。
 *
 * 把前端发出的资源引用（图片、字体、脚本等）约束在 assets/ 目录下，
 * 避免前端通过相对路径逃逸到插件其他位置或文件系统其他位置。
 * 同时处理同源判定（用于 CDN URL 转 assets 引用的场景）。
 */
internal object FrontendAssetReferencePolicy {
    /** 资源根目录前缀。所有合法资源引用都应以此开头。 */
    private const val ASSET_ROOT = "assets/"

    /**
     * 把外部传入的资源引用归一化为 assets/ 下的相对路径。
     * 不合法（绝对 URL、protocol、绝对路径、非 assets 开头）返回 null。
     */
    fun normalizeAssetReference(assetReference: String): String? {
        // 去掉 fragment 和 query，统一用正斜杠
        val referencePath = assetReference
            .substringBefore("#")
            .substringBefore("?")
            .replace('\\', '/')
        // 协议相对 URL（//host/...）和绝对 URL（http:...）一律拒绝
        if (referencePath.startsWith("//") || ABSOLUTE_URL_REFERENCE_REGEX.containsMatchIn(referencePath)) {
            return null
        }
        // URL 解码路径；解析失败时退化为原字符串
        val decodedPath = runCatching { URI(referencePath).path }
            .getOrDefault(referencePath)
        return normalizeDecodedAssetPath(decodedPath)
    }

    /**
     * 把同源 URL 转换为 assets 引用。
     * 非 same-origin 或路径非法返回 null。
     *
     * @param url 待转换的 URL
     * @param entry 入口 URL（用于判定同源）
     */
    fun assetReferenceFromSameOriginUrl(url: String?, entry: URI): String? {
        if (url.isNullOrBlank()) {
            return null
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!isSameOrigin(uri, entry)) {
            return null
        }
        return normalizeDecodedAssetPath(uri.path ?: return null)
    }

    /**
     * 把已解码路径归一化为 assets/ 下的相对路径。
     * 步骤：去前缀 → 路径规范化 → 必须非绝对 → 必须以 assets/ 开头。
     */
    private fun normalizeDecodedAssetPath(decodedPath: String): String? {
        val withoutQuery = decodedPath
            .replace('\\', '/')
            .removePrefix("./")
            .removePrefix("/")
        if (withoutQuery.isBlank()) {
            return null
        }
        val normalizedPath = runCatching { Path.of(withoutQuery).normalize() }.getOrNull() ?: return null
        // 绝对路径一律拒绝（避免跨平台歧义）
        if (normalizedPath.isAbsolute) {
            return null
        }
        val normalized = normalizedPath.toString().replace('\\', '/')
        // 必须以 assets/ 开头；刚好等于 assets 也拒绝（无意义）
        if (!normalized.startsWith(ASSET_ROOT) || normalized == ASSET_ROOT.removeSuffix("/")) {
            return null
        }
        return normalized
    }

    /**
     * 判断两个 URI 是否同源（scheme + host + port 一致）。
     * 用于 CDN URL 转 assets 引用的合法性判定。
     */
    fun isSameOrigin(candidate: URI, entry: URI): Boolean {
        val candidateHost = candidate.host ?: return false
        val entryHost = entry.host ?: return false
        if (!candidate.scheme.equals(entry.scheme, ignoreCase = true) ||
            !candidateHost.equals(entryHost, ignoreCase = true)
        ) {
            return false
        }
        return effectivePort(candidate) == effectivePort(entry)
    }

    /**
     * 取 URI 的有效端口。
     * 未显式指定时按 scheme 默认值返回（http=80, https=443）。
     */
    private fun effectivePort(uri: URI): Int =
        if (uri.port != -1) {
            uri.port
        } else {
            when (uri.scheme?.lowercase()) {
                "http" -> 80
                "https" -> 443
                else -> -1
            }
        }

    /** 匹配绝对 URL（带协议前缀如 http:）的正则。 */
    private val ABSOLUTE_URL_REFERENCE_REGEX = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
}
