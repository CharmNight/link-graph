package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphEvidence
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiMethod

/**
 * 解析 MyBatis Mapper 方法与 XML SQL 语句之间的关系。
 */
class MyBatisResolver(
    /** 保存 Java 辅助解析器，用于生成方法稳定键。 */
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    /** 匹配 mapper 根节点上的 namespace。 */
    private val namespaceRegex = Regex("""<mapper\b[^>]*namespace\s*=\s*"([^"]+)"""")

    /**
     * 根据 Mapper 方法查找对应 SQL 语句节点和映射边。
     */
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        // 只有接口 Mapper 方法才参与 XML 语句映射。
        val ownerClass = method.containingClass ?: return ResolverOutput()
        val namespace = ownerClass.qualifiedName ?: return ResolverOutput()
        if (!ownerClass.isInterface) {
            return ResolverOutput()
        }

        // 在项目 XML 中查找 namespace 和 statementId 同时命中的语句。
        val statementMatch = context.allXmlFiles()
            .asSequence()
            .mapNotNull { file -> findStatement(file.text, namespace, method.name)?.let { it to file } }
            .firstOrNull()
            ?: return ResolverOutput()

        // 命中后构造 SQL 节点，并把 namespace、statementId 等信息写入元数据和证据。
        val (statement, file) = statementMatch
        val sqlNode = GraphNode(
            id = GraphNode.stableId(NodeType.SQL, "$namespace.${statement.id}", ownerContext = statement.type),
            type = NodeType.SQL,
            title = "SQL ${ownerClass.name}.${statement.id}",
            location = ResolverSupport.locationOf(file, statement.marker),
            sourceKind = "MYBATIS_XML_STATEMENT",
            evidence = listOf(
                GraphEvidence(source = file.virtualFile?.path ?: file.name, detail = "namespace=$namespace"),
                GraphEvidence(source = file.virtualFile?.path ?: file.name, detail = "statementId=${statement.id}"),
            ),
            metadata = mapOf(
                "namespace" to namespace,
                "statementId" to statement.id,
                "statementType" to statement.type,
            ),
        )
        val mapperNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        // 从 Mapper 方法连向 SQL 节点，表达 `maps_to_sql` 关系。
        val edge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.MAPS_TO_SQL, mapperNodeId, sqlNode.id),
            type = EdgeType.MAPS_TO_SQL,
            fromNodeId = mapperNodeId,
            toNodeId = sqlNode.id,
            evidence = sqlNode.evidence,
        )
        return ResolverOutput(
            nodes = listOf(sqlNode),
            edges = listOf(edge),
        )
    }

    /**
     * 在 XML 文本中查找指定 namespace 和 statementId 的 SQL 语句。
     */
    private fun findStatement(text: String, namespace: String, statementId: String): SqlStatementMatch? {
        val discoveredNamespace = namespaceRegex.find(text)?.groupValues?.getOrNull(1) ?: return null
        if (discoveredNamespace != namespace) {
            return null
        }

        // 仅识别常见的 select/insert/update/delete 语句节点。
        val statementRegex = Regex(
            """<(select|insert|update|delete)\b[^>]*id\s*=\s*"${Regex.escape(statementId)}"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val statement = statementRegex.find(text) ?: return null
        return SqlStatementMatch(
            id = statementId,
            type = statement.groupValues[1].lowercase(),
            marker = statement.value,
        )
    }

    /**
     * 表示命中的 SQL 语句信息。
     */
    private data class SqlStatementMatch(
        /** 保存语句标识。 */
        val id: String,
        /** 保存语句类型。 */
        val type: String,
        /** 保存原始匹配片段。 */
        val marker: String,
    )
}
