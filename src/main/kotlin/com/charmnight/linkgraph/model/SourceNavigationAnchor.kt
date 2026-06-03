package com.charmnight.linkgraph.model

data class SourceNavigationAnchor(
    val filePath: String? = null,
    val virtualFileUrl: String? = null,
    val line: Int = 1,
    val column: Int = 1,
    val signature: String? = null,
    val nodeType: NodeType? = null,
)

object SourceNavigationAnchors {
    const val NODE_ID_KEY = "source.navigation.nodeId"
    const val FILE_PATH_KEY = "source.navigation.filePath"
    const val VIRTUAL_FILE_URL_KEY = "source.navigation.virtualFileUrl"
    const val START_LINE_KEY = "source.navigation.startLine"
    const val END_LINE_KEY = "source.navigation.endLine"
    const val COLUMN_KEY = "source.navigation.column"
    const val SIGNATURE_KEY = "source.navigation.signature"
    const val NODE_TYPE_KEY = "source.navigation.nodeType"
    const val REASON_KEY = "source.navigation.reason"

    fun metadata(
        nodeId: String? = null,
        filePath: String? = null,
        virtualFileUrl: String? = null,
        startLine: Int? = null,
        endLine: Int? = null,
        column: Int? = null,
        signature: String? = null,
        nodeType: NodeType? = null,
        reason: String? = null,
    ): Map<String, String> = buildMap {
        nodeId?.trim()?.takeIf(String::isNotEmpty)?.let { put(NODE_ID_KEY, it) }
        filePath?.trim()?.takeIf(String::isNotEmpty)?.let { put(FILE_PATH_KEY, it) }
        virtualFileUrl?.trim()?.takeIf(String::isNotEmpty)?.let { put(VIRTUAL_FILE_URL_KEY, it) }
        startLine?.takeIf { it > 0 }?.let { put(START_LINE_KEY, it.toString()) }
        endLine?.takeIf { it > 0 }?.let { put(END_LINE_KEY, it.toString()) }
        column?.takeIf { it > 0 }?.let { put(COLUMN_KEY, it.toString()) }
        signature?.trim()?.takeIf(String::isNotEmpty)?.let { put(SIGNATURE_KEY, it) }
        nodeType?.let { put(NODE_TYPE_KEY, it.name) }
        reason?.trim()?.takeIf(String::isNotEmpty)?.let { put(REASON_KEY, it) }
    }

    fun fromMetadata(metadata: Map<String, String>): SourceNavigationAnchor? {
        val filePath = metadata[FILE_PATH_KEY]?.trim()?.takeIf(String::isNotEmpty)
        val virtualFileUrl = metadata[VIRTUAL_FILE_URL_KEY]?.trim()?.takeIf(String::isNotEmpty)
        val signature = metadata[SIGNATURE_KEY]?.trim()?.takeIf(String::isNotEmpty)
        if (filePath == null && virtualFileUrl == null && signature == null) {
            return null
        }
        return SourceNavigationAnchor(
            filePath = filePath,
            virtualFileUrl = virtualFileUrl,
            line = metadata[START_LINE_KEY].positiveIntOrDefault(1),
            column = metadata[COLUMN_KEY].positiveIntOrDefault(1),
            signature = signature,
            nodeType = metadata[NODE_TYPE_KEY]?.trim()?.takeIf(String::isNotEmpty)?.let(::parseNodeType),
        )
    }

    fun canNavigate(node: GraphNode): Boolean {
        if (!node.location.isNullOrBlank()) {
            return true
        }
        val metadataAnchor = fromMetadata(node.metadata)
        if (metadataAnchor?.filePath != null || metadataAnchor?.virtualFileUrl != null) {
            return true
        }
        if (metadataAnchor?.signature != null && isSignatureNavigableType(metadataAnchor.nodeType ?: node.type)) {
            return true
        }
        return isSignatureNavigableType(node.type) && !node.signature.isNullOrBlank()
    }

    fun isSignatureNavigableType(type: NodeType?): Boolean =
        type == NodeType.METHOD ||
            type == NodeType.CLASS ||
            type == NodeType.INTERFACE ||
            type == NodeType.ENUM ||
            type == NodeType.ANNOTATION ||
            type == NodeType.RECORD ||
            type == NodeType.OBJECT ||
            type == NodeType.EXTERNAL_CLASS

    private fun String?.positiveIntOrDefault(defaultValue: Int): Int =
        this?.toIntOrNull()?.takeIf { it > 0 } ?: defaultValue

    private fun parseNodeType(value: String): NodeType? =
        runCatching { NodeType.valueOf(value) }.getOrNull()
}
