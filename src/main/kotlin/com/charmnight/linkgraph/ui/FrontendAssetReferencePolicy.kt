package com.charmnight.linkgraph.ui

import java.net.URI
import java.nio.file.Path

internal object FrontendAssetReferencePolicy {
    private const val ASSET_ROOT = "assets/"

    fun normalizeAssetReference(assetReference: String): String? {
        val referencePath = assetReference
            .substringBefore("#")
            .substringBefore("?")
            .replace('\\', '/')
        if (referencePath.startsWith("//") || ABSOLUTE_URL_REFERENCE_REGEX.containsMatchIn(referencePath)) {
            return null
        }
        val decodedPath = runCatching { URI(referencePath).path }
            .getOrDefault(referencePath)
        return normalizeDecodedAssetPath(decodedPath)
    }

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

    private fun normalizeDecodedAssetPath(decodedPath: String): String? {
        val withoutQuery = decodedPath
            .replace('\\', '/')
            .removePrefix("./")
            .removePrefix("/")
        if (withoutQuery.isBlank()) {
            return null
        }
        val normalizedPath = runCatching { Path.of(withoutQuery).normalize() }.getOrNull() ?: return null
        if (normalizedPath.isAbsolute) {
            return null
        }
        val normalized = normalizedPath.toString().replace('\\', '/')
        if (!normalized.startsWith(ASSET_ROOT) || normalized == ASSET_ROOT.removeSuffix("/")) {
            return null
        }
        return normalized
    }

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

    private val ABSOLUTE_URL_REFERENCE_REGEX = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
}
