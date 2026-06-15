package com.charmnight.linkgraph.model

data class GraphSourceLocation(
    val filePath: String? = null,
    val virtualFileUrl: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val column: Int? = null,
    val origin: String? = null,
    val decompiled: Boolean? = null,
) {
    val hasAddress: Boolean
        get() = !filePath.isNullOrBlank() || !virtualFileUrl.isNullOrBlank()
}

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

fun GraphNode.sourceLocation(): GraphSourceLocation = metadata.sourceLocation()

fun GraphNode.sourceFilePathOrLocationPath(): String? =
    sourceLocation().filePath ?: location?.substringBefore(':')

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
