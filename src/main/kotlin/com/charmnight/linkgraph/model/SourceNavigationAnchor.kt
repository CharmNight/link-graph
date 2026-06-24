package com.charmnight.linkgraph.model

/**
 * 描述指向源码具体位置的导航锚点。
 *
 * 用于在图节点与 IDE 编辑器之间建立可跳转关系，
 * 既支持基于文件路径或 VirtualFile URL 的精准定位，
 * 也支持基于符号签名 + 节点类型的模糊定位（由上层再行解析）。
 */
data class SourceNavigationAnchor(
    /** 文件系统路径，优先级低于 [virtualFileUrl]。 */
    val filePath: String? = null,
    /** IDE 内部使用的 VirtualFile URL，可识别项目外或 JAR 内文件。 */
    val virtualFileUrl: String? = null,
    /** 起始行号，1 基。 */
    val line: Int = 1,
    /** 起始列号，1 基。 */
    val column: Int = 1,
    /** 可定位的符号签名，配合节点类型用于按符号查找。 */
    val signature: String? = null,
    /** 与签名匹配的节点类型，用于决定是否走符号解析路径。 */
    val nodeType: NodeType? = null,
)

/**
 * 源码导航锚点的工厂与解析工具集合。
 *
 * 主要职责：
 * - 把节点元数据中的导航信息打包成字符串映射，便于随节点一起序列化与缓存。
 * - 反向从元数据还原出 [SourceNavigationAnchor]，用于在 UI 上判断是否可跳转。
 * - 提供判断节点是否支持源码导航的辅助函数。
 */
object SourceNavigationAnchors {
    /** 元数据键：节点 ID。 */
    const val NODE_ID_KEY = "source.navigation.nodeId"
    /** 元数据键：文件系统路径。 */
    const val FILE_PATH_KEY = "source.navigation.filePath"
    /** 元数据键：VirtualFile URL。 */
    const val VIRTUAL_FILE_URL_KEY = "source.navigation.virtualFileUrl"
    /** 元数据键：起始行号。 */
    const val START_LINE_KEY = "source.navigation.startLine"
    /** 元数据键：结束行号。 */
    const val END_LINE_KEY = "source.navigation.endLine"
    /** 元数据键：列号。 */
    const val COLUMN_KEY = "source.navigation.column"
    /** 元数据键：符号签名。 */
    const val SIGNATURE_KEY = "source.navigation.signature"
    /** 元数据键：节点类型名。 */
    const val NODE_TYPE_KEY = "source.navigation.nodeType"
    /** 元数据键：导航触发原因，用于诊断与日志。 */
    const val REASON_KEY = "source.navigation.reason"

    /**
     * 把导航相关字段拼装成字符串映射，过滤掉空值与非正数行/列。
     */
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

    /**
     * 从节点元数据中还原导航锚点。
     * 文件路径、VirtualFile URL 和签名全部缺失时返回 null，表示不可导航。
     */
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

    /**
     * 判断给定节点是否具备可用的源码导航信息。
     * 优先看 location 字段；其次看元数据中是否带有文件路径；最后看类型是否支持按签名解析。
     */
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

    /**
     * 判断给定节点类型是否适合基于签名做源码导航。
     * 仅支持可以稳定解析出声明位置的类型化代码元素。
     */
    fun isSignatureNavigableType(type: NodeType?): Boolean =
        type == NodeType.METHOD ||
            type == NodeType.CLASS ||
            type == NodeType.INTERFACE ||
            type == NodeType.ENUM ||
            type == NodeType.ANNOTATION ||
            type == NodeType.RECORD ||
            type == NodeType.OBJECT ||
            type == NodeType.EXTERNAL_CLASS

    /**
     * 把字符串解析为正整数，非正数或非法时回退到默认值。
     */
    private fun String?.positiveIntOrDefault(defaultValue: Int): Int =
        this?.toIntOrNull()?.takeIf { it > 0 } ?: defaultValue

    /**
     * 安全解析节点类型枚举，未知名称返回 null 而不是抛错。
     */
    private fun parseNodeType(value: String): NodeType? =
        runCatching { NodeType.valueOf(value) }.getOrNull()
}
