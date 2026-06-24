package com.charmnight.linkgraph.model

/**
 * 节点/边对应的源码位置。
 *
 * 把"在哪个文件、哪一行、哪一段范围"等信息统一封装，
 * 用于点击跳转、edit scope 计算等场景。所有字段都可空，
 * 因为不同来源（本地文件、反编译、外部 jar）能提供的信息量不同。
 */
data class GraphSourceLocation(
    /** 文件路径。 */
    val filePath: String? = null,
    /** IntelliJ 虚拟文件 URL；跨项目跳转时使用。 */
    val virtualFileUrl: String? = null,
    /** 起始字符偏移。 */
    val startOffset: Int? = null,
    /** 结束字符偏移。 */
    val endOffset: Int? = null,
    /** 起始行号。 */
    val startLine: Int? = null,
    /** 结束行号。 */
    val endLine: Int? = null,
    /** 列号。 */
    val column: Int? = null,
    /** 来源标记（导入、索引、设计等）。 */
    val origin: String? = null,
    /** 是否来自反编译。 */
    val decompiled: Boolean? = null,
) {
    /** 是否携带可定位信息（文件路径或虚拟文件 URL 至少其一非空）。 */
    val hasAddress: Boolean
        get() = !filePath.isNullOrBlank() || !virtualFileUrl.isNullOrBlank()
}

/**
 * 从节点/边元数据中解析出源码位置。
 * 字段名对应 [GraphMetadataKeys.Source] 中定义的 key。
 */
fun Map<String, String>.sourceLocation(): GraphSourceLocation =
    GraphSourceLocation(
        filePath = this[GraphMetadataKeys.Source.FILE_PATH]?.takeIf(String::isNotBlank),
        virtualFileUrl = this[GraphMetadataKeys.Source.VIRTUAL_FILE_URL]?.takeIf(String::isNotBlank),
        startOffset = this[GraphMetadataKeys.Source.START_OFFSET]?.toIntOrNull(),
        endOffset = this[GraphMetadataKeys.Source.END_OFFSET]?.toIntOrNull(),
        startLine = this[GraphMetadataKeys.Source.START_LINE]?.toIntOrNull(),
        endLine = this[GraphMetadataKeys.Source.END_LINE]?.toIntOrNull(),
        column = this[GraphMetadataKeys.Source.COLUMN]?.toIntOrNull(),
        origin = this[GraphMetadataKeys.Source.ORIGIN]?.takeIf(String::isNotBlank),
        decompiled = this[GraphMetadataKeys.Source.DECOMPILED]?.toBooleanStrictOrNull(),
    )

/** 节点上的源码位置便捷访问。 */
fun GraphNode.sourceLocation(): GraphSourceLocation = metadata.sourceLocation()

/**
 * 节点上的源码文件路径便捷访问。
 * 优先取 metadata 中的路径，缺失时回退到 location 字段（去掉冒号后的部分）。
 */
fun GraphNode.sourceFilePathOrLocationPath(): String? =
    sourceLocation().filePath ?: location?.substringBefore(':')

/**
 * 把源码位置写回元数据映射。
 * 只写入非空字段，避免污染元数据。
 */
fun MutableMap<String, String>.putSourceLocation(
    location: GraphSourceLocation,
) {
    location.filePath?.let { put(GraphMetadataKeys.Source.FILE_PATH, it) }
    location.virtualFileUrl?.let { put(GraphMetadataKeys.Source.VIRTUAL_FILE_URL, it) }
    location.startOffset?.let { put(GraphMetadataKeys.Source.START_OFFSET, it.toString()) }
    location.endOffset?.let { put(GraphMetadataKeys.Source.END_OFFSET, it.toString()) }
    location.startLine?.let { put(GraphMetadataKeys.Source.START_LINE, it.toString()) }
    location.endLine?.let { put(GraphMetadataKeys.Source.END_LINE, it.toString()) }
    location.column?.let { put(GraphMetadataKeys.Source.COLUMN, it.toString()) }
    location.origin?.let { put(GraphMetadataKeys.Source.ORIGIN, it) }
    location.decompiled?.let { put(GraphMetadataKeys.Source.DECOMPILED, it.toString()) }
}
