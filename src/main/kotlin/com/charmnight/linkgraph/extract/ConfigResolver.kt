package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod

class ConfigResolver(
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    private val lineEntryRegex = Regex("""^\s*([A-Za-z0-9_.-]+)\s*[:=]\s*(.+?)\s*$""")
    private val xmlEntryRegex = Regex("""<property\b[^>]*name\s*=\s*"([^"]+)"[^>]*value\s*=\s*"([^"]+)"""")

    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        val ownerClass = method.containingClass ?: return ResolverOutput()
        val ownerName = ownerClass.qualifiedName ?: return ResolverOutput()
        val methodReference = "$ownerName#${method.name}"
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()

        context.allConfigFiles().forEach { file ->
            parseEntries(file)
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

    private fun parseEntries(file: PsiFile): List<ConfigEntry> {
        val extension = file.virtualFile?.extension?.lowercase()
        return when (extension) {
            "yml", "yaml", "properties" -> file.text.lineSequence()
                .mapNotNull { line ->
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

    private data class ConfigEntry(
        val key: String,
        val value: String,
        val marker: String,
    )
}
