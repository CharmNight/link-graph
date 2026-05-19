package com.charmnight.linkgraph.source

enum class SourceOrigin {
    PROJECT_SOURCE,
    CONTENT_ROOT,
    LIBRARY_SOURCE_JAR,
    LIBRARY_CLASS_JAR,
    JDK_SOURCE,
    JDK_CLASS,
    USER_ATTACHED_SOURCE_JAR,
    USER_ATTACHED_CLASS_JAR,
    DECOMPILED,
    LOCAL_FILE,
}

data class SourceContent(
    val text: String,
    val displayPath: String,
    val virtualFileUrl: String?,
    val origin: SourceOrigin,
    val language: String?,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val decompiled: Boolean = false,
    val diagnostic: String? = null,
)

data class SourceContentAccessPolicy(
    val allowExternalLibraries: Boolean = true,
    val allowJdk: Boolean = true,
) {
    fun allowsOrigin(origin: SourceOrigin): Boolean =
        when (origin) {
            SourceOrigin.JDK_SOURCE,
            SourceOrigin.JDK_CLASS,
            -> allowJdk
            SourceOrigin.LIBRARY_SOURCE_JAR,
            SourceOrigin.LIBRARY_CLASS_JAR,
            SourceOrigin.DECOMPILED,
            -> allowExternalLibraries
            SourceOrigin.PROJECT_SOURCE,
            SourceOrigin.CONTENT_ROOT,
            SourceOrigin.USER_ATTACHED_SOURCE_JAR,
            SourceOrigin.USER_ATTACHED_CLASS_JAR,
            SourceOrigin.LOCAL_FILE,
            -> true
        }

    companion object {
        val DEFAULT = SourceContentAccessPolicy()
    }
}

interface SourceContentResolver {
    fun readByVirtualFileUrl(url: String): SourceContent?

    fun readByPath(path: String): SourceContent?

    fun readSnippetByPath(path: String, startLine: Int?, endLine: Int?): SourceContent?

    fun readClassByQualifiedName(qualifiedName: String): SourceContent?

    fun readResourceByPath(resourcePath: String): SourceContent?
}

class CompositeSourceContentResolver(
    private val resolvers: List<SourceContentResolver>,
) : SourceContentResolver {
    override fun readByVirtualFileUrl(url: String): SourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readByVirtualFileUrl(url) }

    override fun readByPath(path: String): SourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readByPath(path) }

    override fun readSnippetByPath(path: String, startLine: Int?, endLine: Int?): SourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readSnippetByPath(path, startLine, endLine) }

    override fun readClassByQualifiedName(qualifiedName: String): SourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readClassByQualifiedName(qualifiedName) }

    override fun readResourceByPath(resourcePath: String): SourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readResourceByPath(resourcePath) }

    fun lastUnavailableReason(): String? =
        resolvers.firstNotNullOfOrNull { resolver ->
            when (resolver) {
                is CompositeSourceContentResolver -> resolver.lastUnavailableReason()
                is AttachedJarContentResolver -> resolver.lastUnavailableReason
                is IdeSourceContentResolver -> resolver.lastUnavailableReason
                else -> null
            }
        }
}
