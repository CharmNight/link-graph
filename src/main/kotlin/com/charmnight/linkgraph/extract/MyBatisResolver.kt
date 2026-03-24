package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphEvidence
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiMethod

class MyBatisResolver(
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    private val namespaceRegex = Regex("""<mapper\b[^>]*namespace\s*=\s*"([^"]+)"""")

    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        val ownerClass = method.containingClass ?: return ResolverOutput()
        val namespace = ownerClass.qualifiedName ?: return ResolverOutput()
        if (!ownerClass.isInterface) {
            return ResolverOutput()
        }

        val statementMatch = context.allXmlFiles()
            .asSequence()
            .mapNotNull { file -> findStatement(file.text, namespace, method.name)?.let { it to file } }
            .firstOrNull()
            ?: return ResolverOutput()

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

    private fun findStatement(text: String, namespace: String, statementId: String): SqlStatementMatch? {
        val discoveredNamespace = namespaceRegex.find(text)?.groupValues?.getOrNull(1) ?: return null
        if (discoveredNamespace != namespace) {
            return null
        }

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

    private data class SqlStatementMatch(
        val id: String,
        val type: String,
        val marker: String,
    )
}
