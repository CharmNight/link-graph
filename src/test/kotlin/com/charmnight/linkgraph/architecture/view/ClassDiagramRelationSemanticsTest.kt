package com.charmnight.linkgraph.architecture.view

import com.charmnight.linkgraph.architecture.ClassDiagramFastIndex
import com.charmnight.linkgraph.application.indexed.IndexedClassDiagramOptions
import com.charmnight.linkgraph.application.indexed.IndexedGraphViewportOptions
import com.charmnight.linkgraph.application.indexed.requestClassDiagramRequest
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmModuleSymbol
import com.charmnight.linkgraph.jvm.index.JvmPackageSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.source.SourceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassDiagramRelationSemanticsTest {
    @Test
    fun classDiagramFastIndexProjectsExtendsImplementsThroughClassDiagramMetadata() {
        val fixture = SymbolFixture("com.example.hierarchy")
        val base = fixture.classSymbol("BaseService")
        val contract = fixture.classSymbol("RunnableService", JvmClassKind.INTERFACE)
        val implementation = fixture.classSymbol(
            "OrderService",
            superClassName = base.qualifiedName,
            interfaceNames = listOf(contract.qualifiedName),
        )
        val symbolIndex = fixture.index(base, contract, implementation)

        val view = ClassDiagramProjector().project(ClassDiagramFastIndex.fromSymbols(symbolIndex), implementation.id)
        val edges = view.fullGraph.edges.associateBy { edge -> edge.toNodeId }

        assertEquals("EXTENDS", edges.getValue(base.id).metadata["classDiagram.relation.role"])
        assertEquals("extends", edges.getValue(base.id).metadata["classDiagram.relation.label"])
        assertEquals("IMPLEMENTS", edges.getValue(contract.id).metadata["classDiagram.relation.role"])
        assertEquals("implements", edges.getValue(contract.id).metadata["classDiagram.relation.label"])
    }

    @Test
    fun fieldDependenciesWinOverUnusedMethodParametersInClassDiagramWindow() {
        val fixture = SymbolFixture("com.example.priority")
        val anchor = fixture.classSymbol("Validator")
        val unusedParameter = fixture.classSymbol("AUnusedParameter")
        val fieldDependency = fixture.classSymbol("ZCoreFieldDependency")
        val field = fixture.field(anchor, "coreDependency", fieldDependency.qualifiedName)
        val method = fixture.method(
            owner = anchor,
            simpleName = "validate",
            parameterTypes = listOf(unusedParameter.qualifiedName),
            returnType = "void",
        )
        val symbolIndex = fixture.index(
            anchor,
            unusedParameter,
            fieldDependency,
            fields = listOf(field),
            methods = listOf(method),
        )

        val view = ClassDiagramProjector().project(
            index = ClassDiagramFastIndex.fromSymbols(symbolIndex),
            scopeNodeId = anchor.id,
            request = requestClassDiagramRequest(anchor.id).copy(
                classDiagram = IndexedClassDiagramOptions(neighborhoodLimit = 2),
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 2, maxVisibleEdges = 2),
            ),
        )
        val visibleTitles = view.visibleGraph.nodes.map { node -> node.title }.toSet()

        assertTrue(fieldDependency.simpleName in visibleTitles, visibleTitles.toString())
        assertFalse(unusedParameter.simpleName in visibleTitles, visibleTitles.toString())
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.toNodeId == fieldDependency.id &&
                    edge.metadata["classDiagram.relation.role"] == "FIELD" &&
                    (edge.metadata["classDiagram.relation.weight"]?.toIntOrNull() ?: 0) >
                    (edge.metadata["classDiagram.relation.unusedParameterWeight"]?.toIntOrNull() ?: 0)
            },
        )
    }

    @Test
    fun classDiagramProjectorAggregatesParallelVisibleRelationsWithCanonicalSourceEdges() {
        val fixture = SymbolFixture("com.example.aggregate")
        val anchor = fixture.classSymbol("OrderService")
        val repository = fixture.classSymbol("OrderRepository")
        val field = fixture.field(anchor, "repository", repository.qualifiedName)
        val method = fixture.method(
            owner = anchor,
            simpleName = "load",
            parameterTypes = emptyList(),
            returnType = repository.qualifiedName,
        )
        val symbolIndex = fixture.index(
            anchor,
            repository,
            fields = listOf(field),
            methods = listOf(method),
        )

        val view = ClassDiagramProjector().project(
            index = ClassDiagramFastIndex.fromSymbols(symbolIndex),
            scopeNodeId = anchor.id,
        )
        val fullSourceEdgeIds = view.fullGraph.edges
            .filter { edge -> edge.fromNodeId == anchor.id && edge.toNodeId == repository.id }
            .mapTo(linkedSetOf()) { edge -> edge.id }
        val visibleEdges = view.visibleGraph.edges.filter { edge ->
            edge.fromNodeId == anchor.id && edge.toNodeId == repository.id
        }

        assertEquals(2, fullSourceEdgeIds.size)
        assertEquals(1, visibleEdges.size)
        val aggregateEdge = visibleEdges.single()
        assertEquals(JvmRelationKind.USES_TYPE.name, aggregateEdge.metadata["jvm.relation.kind"])
        assertEquals("FIELD", aggregateEdge.metadata["classDiagram.relation.role"])
        assertEquals("ASSOCIATION", aggregateEdge.metadata["uml.relation.kind"])
        assertEquals("field repository +1", aggregateEdge.label)
        assertEquals("field repository", aggregateEdge.metadata["uml.relation.aggregate.primaryLabel"])
        assertEquals("return load", aggregateEdge.metadata["uml.relation.aggregate.secondaryLabels"])
        assertEquals("ASSOCIATION,DEPENDENCY", aggregateEdge.metadata["uml.relation.aggregate.kinds"])
        assertEquals("2", aggregateEdge.metadata["uml.relation.aggregate.count"])
        assertEquals(
            fullSourceEdgeIds,
            aggregateEdge.metadata.getValue("uml.relation.aggregate.edgeIds")
                .split(',')
                .mapTo(linkedSetOf()) { edgeId -> edgeId.trim() },
        )
        val edgeMapping = view.projectionIndex.edgeMapping(aggregateEdge.id)
        assertEquals(GraphProjectionMappingKind.INDEXED_READONLY, edgeMapping?.mappingKind)
        assertEquals(fullSourceEdgeIds, edgeMapping?.canonicalEdgeIds?.toSet())
        assertEquals(1, view.summary.relationCount)
        assertEquals(0, view.summary.hiddenEdgeCount)
    }

    private class SymbolFixture(private val packageName: String) {
        private val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        private val pkg = JvmPackageSymbol(
            id = "package:$packageName",
            qualifiedName = packageName,
            simpleName = packageName.substringAfterLast('.'),
            moduleName = module.qualifiedName,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )

        fun classSymbol(
            simpleName: String,
            kind: JvmClassKind = JvmClassKind.CLASS,
            superClassName: String? = null,
            interfaceNames: List<String> = emptyList(),
        ): JvmClassSymbol {
            val qualifiedName = "$packageName.$simpleName"
            return JvmClassSymbol(
                id = "class:$qualifiedName",
                qualifiedName = qualifiedName,
                simpleName = simpleName,
                packageName = packageName,
                moduleName = module.qualifiedName,
                kind = kind,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
                superClassName = superClassName,
                interfaceNames = interfaceNames,
            )
        }

        fun field(owner: JvmClassSymbol, simpleName: String, typeName: String): JvmFieldSymbol =
            JvmFieldSymbol(
                id = "field:${owner.qualifiedName}.$simpleName",
                qualifiedName = "${owner.qualifiedName}.$simpleName",
                simpleName = simpleName,
                ownerClassName = owner.qualifiedName,
                typeName = typeName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )

        fun method(
            owner: JvmClassSymbol,
            simpleName: String,
            parameterTypes: List<String>,
            returnType: String?,
        ): JvmMethodSymbol {
            val signature = "${owner.qualifiedName}.$simpleName(${parameterTypes.joinToString(",")}):${returnType ?: "void"}"
            return JvmMethodSymbol(
                id = "method:$signature",
                qualifiedName = signature,
                simpleName = simpleName,
                ownerClassName = owner.qualifiedName,
                signature = signature,
                parameterTypes = parameterTypes,
                returnType = returnType,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }

        fun index(
            vararg classes: JvmClassSymbol,
            fields: List<JvmFieldSymbol> = emptyList(),
            methods: List<JvmMethodSymbol> = emptyList(),
        ): JvmSymbolIndex =
            JvmSymbolIndex(
                modulesByName = mapOf(module.qualifiedName to module),
                packagesByName = mapOf(pkg.qualifiedName to pkg),
                classesByQualifiedName = classes.associateBy(JvmClassSymbol::qualifiedName),
                fieldsByQualifiedName = fields.associateBy(JvmFieldSymbol::qualifiedName),
                methodsBySignature = methods.associateBy(JvmMethodSymbol::signature),
            )
    }
}
