package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService
import com.charmnight.linkgraph.architecture.query.RelationDirection
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.review.ReviewGraphQueryService
import com.intellij.openapi.project.Project

class ArchitectureIndexToolFacade(
    private val indexProvider: (Project) -> ArchitectureGraphIndex = { project ->
        project.architectureIndexRuntime().index()
    },
) {
    fun buildIndex(project: Project): ArchitectureGraphIndex {
        return indexProvider(project)
    }

    fun query(project: Project): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(buildIndex(project))

    fun review(project: Project): ReviewGraphQueryService =
        project.architectureIndexRuntime().reviewQuery(index = buildIndex(project))

    fun symbolPayload(symbol: JvmSymbol): Map<String, Any?> =
        mapOf(
            "id" to symbol.id,
            "qualifiedName" to symbol.qualifiedName,
            "simpleName" to symbol.simpleName,
            "origin" to symbol.origin.name,
            "filePath" to symbol.source?.displayPath,
            "virtualFileUrl" to symbol.source?.virtualFileUrl,
            "decompiled" to (symbol.source?.decompiled ?: false),
        )

    fun relationPayload(relation: JvmRelation, index: ArchitectureGraphIndex): Map<String, Any?> =
        mapOf(
            "id" to relation.id,
            "kind" to relation.kind.name,
            "fromSymbolId" to relation.fromSymbolId,
            "fromQualifiedName" to index.findSymbol(relation.fromSymbolId)?.qualifiedName,
            "toSymbolId" to relation.toSymbolId,
            "toQualifiedName" to index.findSymbol(relation.toSymbolId)?.qualifiedName,
            "confidence" to relation.confidence.name,
            "source" to relation.source.name,
            "count" to relation.count,
            "metadata" to relation.metadata,
            "evidenceRefs" to relation.samples.map { sample ->
                mapOf(
                    "filePath" to sample.filePath,
                    "virtualFileUrl" to sample.virtualFileUrl,
                    "startLine" to sample.startLine,
                    "endLine" to sample.endLine,
                    "decompiled" to sample.decompiled,
                    "claim" to sample.claim,
                )
            },
        )

    fun relationKind(value: String?): JvmRelationKind? =
        value?.trim()?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { JvmRelationKind.valueOf(raw.uppercase()) }.getOrNull()
        }

    fun direction(value: String?): RelationDirection =
        value?.trim()?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { RelationDirection.valueOf(raw.uppercase()) }.getOrNull()
        } ?: RelationDirection.BOTH

}
