package com.charmnight.linkgraph.source

import com.charmnight.linkgraph.foundation.truncateUtf8
import com.charmnight.linkgraph.foundation.utf8ByteCount
import com.charmnight.linkgraph.foundation.utf8ByteLengthAtMost

/** 源码来源种类，标识内容的项目/库/JDK/附加 jar/反编译等来源。 */
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

/** 经过 UTF-8 字节预算约束的源码内容。 */
data class BoundedSourceContent(
    val text: String,
    val displayPath: String,
    val virtualFileUrl: String?,
    val origin: SourceOrigin,
    val language: String?,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val utf8ByteCount: Int = utf8ByteCount(text),
    val truncated: Boolean = false,
    val decompiled: Boolean = false,
    val diagnostic: String? = null,
    internal val maxUtf8Bytes: Int = SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES,
) {
    init {
        require(utf8ByteCount == utf8ByteCount(text)) { "utf8ByteCount must match text" }
        require(utf8ByteCount <= maxUtf8Bytes) { "source content exceeds UTF-8 byte limit" }
    }

    /** 以相同元数据和预算创建一个新的有界文本切片。 */
    fun withText(
        text: String,
        startLine: Int? = this.startLine,
        endLine: Int? = this.endLine,
    ): BoundedSourceContent = create(
        text = text,
        displayPath = displayPath,
        virtualFileUrl = virtualFileUrl,
        origin = origin,
        language = language,
        startLine = startLine,
        endLine = endLine,
        decompiled = decompiled,
        diagnostic = diagnostic,
        maxUtf8Bytes = maxUtf8Bytes,
        alreadyTruncated = truncated,
    )

    companion object {
        /** 统一构造入口：按 UTF-8 安全截断并记录诊断。 */
        fun create(
            text: String,
            displayPath: String,
            virtualFileUrl: String?,
            origin: SourceOrigin,
            language: String?,
            startLine: Int? = null,
            endLine: Int? = null,
            decompiled: Boolean = false,
            diagnostic: String? = null,
            maxUtf8Bytes: Int = SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES,
            alreadyTruncated: Boolean = false,
        ): BoundedSourceContent {
            require(maxUtf8Bytes >= 0) { "maxUtf8Bytes must be non-negative" }
            val exceedsLimit = !utf8ByteLengthAtMost(text, maxUtf8Bytes)
            val boundedText = if (exceedsLimit) truncateUtf8(text, maxUtf8Bytes) else text
            val truncated = alreadyTruncated || exceedsLimit
            val effectiveDiagnostic = if (truncated) {
                listOfNotNull(diagnostic, SOURCE_CONTENT_TRUNCATED_DIAGNOSTIC)
                    .distinct()
                    .joinToString(";")
            } else {
                diagnostic
            }
            return BoundedSourceContent(
                text = boundedText,
                displayPath = displayPath,
                virtualFileUrl = virtualFileUrl,
                origin = origin,
                language = language,
                startLine = startLine,
                endLine = endLine,
                utf8ByteCount = utf8ByteCount(boundedText),
                truncated = truncated,
                decompiled = decompiled,
                diagnostic = effectiveDiagnostic,
                maxUtf8Bytes = maxUtf8Bytes,
            )
        }

        private const val SOURCE_CONTENT_TRUNCATED_DIAGNOSTIC = "SOURCE_CONTENT_TRUNCATED"
    }
}

/** 源码访问策略，控制是否允许读取外部库与 JDK 来源。 */
data class SourceContentAccessPolicy(
    val allowExternalLibraries: Boolean = true,
    val allowJdk: Boolean = true,
) {
    /**
     * 判断指定来源是否被策略放行：JDK 相关来源受 [allowJdk] 控制，
     * 外部库与反编译来源受 [allowExternalLibraries] 控制，
     * 项目源码、内容根、用户附加 jar 与本地文件始终允许。
     */
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
        /** 默认访问策略：允许外部库与 JDK 来源。 */
        val DEFAULT = SourceContentAccessPolicy()
    }
}

/** 源码内容解析器接口，定义多种源码寻址方式。 */
interface SourceContentResolver {
    /** 按 IntelliJ 虚拟文件 URL 读取内容。 */
    fun readByVirtualFileUrl(url: String): BoundedSourceContent?

    /** 按路径读取完整内容。 */
    fun readByPath(path: String): BoundedSourceContent?

    /** 按路径与行范围读取代码片段。 */
    fun readSnippetByPath(path: String, startLine: Int?, endLine: Int?): BoundedSourceContent?

    /** 按类限定名读取类源码。 */
    fun readClassByQualifiedName(qualifiedName: String): BoundedSourceContent?

    /** 按资源路径读取资源内容。 */
    fun readResourceByPath(resourcePath: String): BoundedSourceContent?
}

/** 组合式源码解析器：按顺序尝试多个解析器，返回首个成功结果。 */
class CompositeSourceContentResolver(
    private val resolvers: List<SourceContentResolver>,
) : SourceContentResolver {
    /** 依次尝试子解析器，返回首个非空结果。 */
    override fun readByVirtualFileUrl(url: String): BoundedSourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readByVirtualFileUrl(url) }

    /** 依次尝试子解析器，返回首个非空结果。 */
    override fun readByPath(path: String): BoundedSourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readByPath(path) }

    /** 依次尝试子解析器按行范围读取片段，返回首个非空结果。 */
    override fun readSnippetByPath(path: String, startLine: Int?, endLine: Int?): BoundedSourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readSnippetByPath(path, startLine, endLine) }

    /** 依次尝试子解析器按限定名读取类源码，返回首个非空结果。 */
    override fun readClassByQualifiedName(qualifiedName: String): BoundedSourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readClassByQualifiedName(qualifiedName) }

    /** 依次尝试子解析器按资源路径读取资源内容，返回首个非空结果。 */
    override fun readResourceByPath(resourcePath: String): BoundedSourceContent? =
        resolvers.firstNotNullOfOrNull { resolver -> resolver.readResourceByPath(resourcePath) }

    /** 返回组合解析器中任一子解析器报告的最近不可用原因。 */
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
