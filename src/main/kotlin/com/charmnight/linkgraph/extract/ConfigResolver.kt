package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod

/**
 * 解析方法与配置项之间的绑定关系。
 */
class ConfigResolver(
    /** 保存 Java 辅助解析器，用于生成方法稳定键。 */
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    /** 匹配普通配置文件中的 `key=value` 或 `key: value` 形式。 */
    private val lineEntryRegex = Regex("""^\s*([A-Za-z0-9_.-]+)\s*[:=]\s*(.+?)\s*$""")
    /** 匹配 XML 配置中的 property 节点。 */
    private val xmlEntryRegex = Regex("""<property\b[^>]*name\s*=\s*"([^"]+)"[^>]*value\s*=\s*"([^"]+)"""")

    /**
     * 查找值直接引用当前方法的配置项，并生成配置节点和绑定边。
     */
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        // 方法必须能回溯到所属类和限定类名，否则无法构造引用标识。
        val ownerClass = method.containingClass ?: return ResolverOutput()
        val ownerName = ownerClass.qualifiedName ?: return ResolverOutput()
        // 当前实现只匹配 `全限定类名#方法名` 形式的配置值。
        val methodReference = "$ownerName#${method.name}"
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        // 用有序映射去重，避免同一配置项多次入图。
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()

        context.allConfigFiles().forEach { file ->
            parseEntries(file)
                // 仅保留值恰好指向当前方法的配置项。
                .filter { entry -> entry.value == methodReference }
                .forEach { entry ->
                    val path = file.virtualFile?.path ?: file.name
                    val node = GraphNode(
                        id = GraphNode.stableId(NodeType.CONFIG_ITEM, "$path:${entry.key}"),
                        type = NodeType.CONFIG_ITEM,
                        title = entry.key,
                        location = ResolverSupport.locationOf(file, entry.marker),
                        sourceKind = "CONFIG_ITEM",
                        metadata = mapOf(
                            "file" to path,
                            "key" to entry.key,
                            "value" to entry.value,
                        ),
                    )
                    nodes[node.id] = node
                    edges[GraphEdge.stableId(EdgeType.BINDS_CONFIG, node.id, methodNodeId)] = GraphEdge(
                        id = GraphEdge.stableId(EdgeType.BINDS_CONFIG, node.id, methodNodeId),
                        type = EdgeType.BINDS_CONFIG,
                        fromNodeId = node.id,
                        toNodeId = methodNodeId,
                        metadata = mapOf("key" to entry.key),
                    )
                }
        }

        return ResolverOutput(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
        )
    }

    /**
     * 从配置文件中提取键值条目。
     */
    private fun parseEntries(file: PsiFile): List<ConfigEntry> {
        val extension = file.virtualFile?.extension?.lowercase()
        return when (extension) {
            "yml", "yaml", "properties" -> file.text.lineSequence()
                .mapNotNull { line ->
                    // 文本配置逐行匹配键值对。
                    val match = lineEntryRegex.matchEntire(line) ?: return@mapNotNull null
                    ConfigEntry(
                        key = match.groupValues[1],
                        value = match.groupValues[2].trim(),
                        marker = line.trim(),
                    )
                }
                .toList()
            "xml" -> xmlEntryRegex.findAll(file.text)
                .map { match ->
                    // XML 配置直接从 property 标签属性中抽取键和值。
                    ConfigEntry(
                        key = match.groupValues[1],
                        value = match.groupValues[2],
                        marker = match.value,
                    )
                }
                .toList()
            else -> emptyList()
        }
    }

    /**
     * 表示单条配置项。
     */
    private data class ConfigEntry(
        /** 保存配置键。 */
        val key: String,
        /** 保存配置值。 */
        val value: String,
        /** 保存用于定位文件位置的原始片段。 */
        val marker: String,
    )
}
