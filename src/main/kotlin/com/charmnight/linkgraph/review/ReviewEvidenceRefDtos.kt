package com.charmnight.linkgraph.review

/**
 * Review 证据引用 DTO（P2-6 替代之前的 untyped map payload）。
 *
 * evidenceRefs 字段是 union 类型，承载 3 类证据：
 * - [ReviewChangedSymbolEvidenceRef]：变更符号（含 hunk 信息）
 * - [ReviewRelationEvidenceRef]：关键关系（SPI/反射/ServiceLoader/代理/测试）
 * - [ReviewSymbolEvidenceRef]：上下游与相关测试符号
 *
 * 每条证据可选附带 [snippet]，承载源码片段读取结果。
 *
 * Gson 反射序列化按声明顺序输出，与原 mapOf + spread 顺序一致。
 */
sealed interface ReviewEvidenceRef {
    val snippet: ReviewSnippetPayload?
    val snippetUnavailable: ReviewSnippetUnavailable?
}

/** 源码片段读取结果（P2-6 替代之前的 mapOf）。 */
data class ReviewSnippetPayload(
    /** 实际读取到的片段文本（已截断到 2000 字符）。 */
    val snippet: String,
    /** 片段来源（项目源码 / 反编译）。 */
    val snippetOrigin: String,
    /** 虚拟文件 URL（jar:file://... 等）。 */
    val snippetVirtualFileUrl: String?,
    /** 实际起始行号。 */
    val snippetStartLine: Int?,
    /** 实际结束行号。 */
    val snippetEndLine: Int?,
    /** 是否为反编译产物。 */
    val snippetDecompiled: Boolean,
)

/** 片段不可用时的原因标记（P2-6 替代之前的 mapOf("snippetUnavailableReason" to ...)）。 */
data class ReviewSnippetUnavailable(
    val snippetUnavailableReason: String,
)

/** 变更符号证据（含 hunk 信息 + 源码片段）。 */
data class ReviewChangedSymbolEvidenceRef(
    val symbolId: String,
    val qualifiedName: String?,
    val filePath: String?,
    val startLine: Int?,
    val endLine: Int?,
    val changeKind: String?,
    val baselineOnly: Boolean?,
    val blastRadiusIncomplete: Boolean?,
    val unavailableReason: String?,
    val hunkHeader: String?,
    val hunkNewStartLine: Int?,
    val hunkNewLineCount: Int?,
    override val snippet: ReviewSnippetPayload? = null,
    /** 片段不可用时的原因（与 [snippet] 互斥）。 */
    override val snippetUnavailable: ReviewSnippetUnavailable? = null,
) : ReviewEvidenceRef

/** 关系证据（SPI/反射/ServiceLoader/代理/测试等）。 */
data class ReviewRelationEvidenceRef(
    val relationId: String,
    val kind: String,
    val confidence: String,
    val filePath: String?,
    val virtualFileUrl: String?,
    val startLine: Int?,
    val endLine: Int?,
    val decompiled: Boolean?,
    val claim: String?,
    override val snippet: ReviewSnippetPayload? = null,
    override val snippetUnavailable: ReviewSnippetUnavailable? = null,
) : ReviewEvidenceRef

/** 上下游与相关测试符号证据。 */
data class ReviewSymbolEvidenceRef(
    val symbolId: String,
    val qualifiedName: String?,
    val filePath: String?,
    val virtualFileUrl: String?,
    val decompiled: Boolean,
    val origin: String,
    override val snippet: ReviewSnippetPayload? = null,
    override val snippetUnavailable: ReviewSnippetUnavailable? = null,
) : ReviewEvidenceRef
