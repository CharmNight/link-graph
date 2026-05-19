package com.charmnight.linkgraph.architecture.view

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmModuleSymbol
import com.charmnight.linkgraph.jvm.index.JvmPackageSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.source.SourceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureViewProjectorTest {
    @Test
    fun architectureGraphBoundsVisibleGraphForInteractiveRendering() {
        val index = largeArchitectureIndex(packageCount = 18, classesPerPackage = 4)
        val projector = ArchitectureGraphProjector(
            viewportPolicy = GraphViewportPolicy(maxVisibleNodes = 10, maxVisibleEdges = 12),
        )

        val view = projector.project(index)

        assertTrue(view.fullGraph.nodes.size > view.visibleGraph.nodes.size)
        assertTrue(view.visibleGraph.nodes.size <= 10)
        assertTrue(view.visibleGraph.edges.size <= 12)
        assertTrue(view.summary.truncated)
        assertEquals(view.fullGraph.nodes.size - view.visibleGraph.nodes.size, view.summary.hiddenNodeCount)
        assertTrue(view.summary.hiddenEdgeCount >= view.fullGraph.edges.size - view.visibleGraph.edges.size)
    }

    @Test
    fun architectureGraphDefaultsToCompleteArchitectureAggregates() {
        val index = largeArchitectureIndex(packageCount = 3, classesPerPackage = 2, includeResource = true)
        val view = ArchitectureGraphProjector().project(index)

        assertTrue(view.visibleGraph.nodes.any { node -> node.type == NodeType.MODULE })
        assertTrue(view.visibleGraph.nodes.any { node -> node.type == NodeType.PACKAGE })
        assertTrue(view.visibleGraph.nodes.any { node -> node.type == NodeType.LAYER })
        assertTrue(view.visibleGraph.nodes.any { node -> node.type == NodeType.SERVICE })
        assertTrue(view.visibleGraph.nodes.any { node -> node.type == NodeType.RESOURCE })
    }

    @Test
    fun architectureGraphCarriesSourceSamplesForAggregateNodes() {
        val index = largeArchitectureIndex(packageCount = 2, classesPerPackage = 2, includeClassSource = true)
        val view = ArchitectureGraphProjector().project(index)

        val layerNode = view.fullGraph.nodes.first { node -> node.type == NodeType.LAYER }

        assertTrue((layerNode.metadata["architecture.sourceSample.count"]?.toIntOrNull() ?: 0) > 0)
        assertTrue(layerNode.metadata["architecture.sourceSample.0.nodeId"].orEmpty().startsWith("class:"))
        assertTrue(layerNode.metadata["architecture.sourceSample.0.filePath"].orEmpty().endsWith(".java"))
        assertEquals("1", layerNode.metadata["architecture.sourceSample.0.startLine"])
    }

    @Test
    fun architectureGraphPrefersSourceBackedAggregateAnchor() {
        val index = largeArchitectureIndex(packageCount = 2, classesPerPackage = 2, includeClassSource = true)
        val view = ArchitectureGraphProjector().project(index)

        val anchorNode = view.visibleGraph.nodes.first { node -> node.id == view.anchorNodeId }

        assertTrue(anchorNode.type in setOf(NodeType.LAYER, NodeType.SERVICE, NodeType.PACKAGE, NodeType.RESOURCE))
        assertTrue((anchorNode.metadata["architecture.sourceSample.count"]?.toIntOrNull() ?: 0) > 0)
    }

    @Test
    fun classDiagramBoundsVisibleGraphForInteractiveRendering() {
        val index = largeArchitectureIndex(packageCount = 10, classesPerPackage = 8)
        val projector = ClassDiagramProjector(
            viewportPolicy = GraphViewportPolicy(maxVisibleNodes = 14, maxVisibleEdges = 18),
        )

        val view = projector.project(index)

        assertTrue(view.visibleGraph.nodes.size <= 14)
        assertTrue(view.visibleGraph.edges.size <= 18)
        assertTrue(view.fullGraph.nodes.size <= 24)
        assertEquals(0, view.summary.hiddenNodeCount)
        assertEquals(0, view.summary.hiddenEdgeCount)
    }

    @Test
    fun classDiagramDefaultsToATypeNeighborhoodNotWholeProjectInventory() {
        val index = largeArchitectureIndex(packageCount = 10, classesPerPackage = 8)

        val view = ClassDiagramProjector().project(index)

        assertTrue(view.visibleGraph.nodes.isNotEmpty())
        assertTrue(view.visibleGraph.nodes.size <= 24)
        assertTrue(view.visibleGraph.nodes.size > 1, "Class diagram should not default to one isolated class card.")
        assertTrue(view.visibleGraph.edges.isNotEmpty(), "Class diagram should default to visible class relationships.")
        assertFalse(
            view.visibleGraph.nodes.any { node -> node.type == NodeType.MODULE || node.type == NodeType.PACKAGE },
            "Class diagram should show type relationships, not module/package containers.",
        )
        assertTrue(view.visibleGraph.nodes.all { node ->
            node.type in setOf(
                NodeType.CLASS,
                NodeType.INTERFACE,
                NodeType.ENUM,
                NodeType.ANNOTATION,
                NodeType.RECORD,
                NodeType.OBJECT,
            )
        })
    }

    @Test
    fun classDiagramProjectsUmlStructureRuntimeIntegrationAndOneHopExternalGraph() {
        val index = umlArchitectureIndex()

        val view = ClassDiagramProjector().project(index, "package:com.example.service")

        assertTrue(view.visibleGraph.nodes.any { node ->
            node.signature == "com.example.service.TaskRunner" &&
                node.metadata["uml.field.items"] == "provider: TaskProvider" &&
                node.metadata["uml.method.items"] == "run(): void" &&
                node.doc == "Runs queued tasks." &&
                node.metadata["uml.comment"] == "Runs queued tasks." &&
                node.metadata["jvm.class.abstract"] == "true"
        })
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.IMPLEMENTS.name
            },
            "UML class diagram should keep inheritance/implementation relations.",
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.USES_TYPE.name
            },
            "UML class diagram should keep static type dependency relations.",
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.CALLS.name
            },
            "Class diagram should include class-level call relations.",
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.SERVICE_LOADER_LOADS.name
            },
            "Class diagram should include ServiceLoader relations.",
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.SPI_PROVIDES.name
            },
            "Class diagram should include SPI provider relations.",
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.REFLECTS_TO.name
            },
            "Class diagram should include static reflection relations.",
        )
        assertTrue(
            view.visibleGraph.nodes.any { node ->
                node.signature == "com.external.ExternalReviewClient" &&
                    node.metadata["source.origin"] == SourceOrigin.LIBRARY_CLASS_JAR.name
            },
            "Class diagram should include external one-hop dependency classes.",
        )
        assertEquals(1, view.summary.fieldCount)
        assertEquals(view.visibleGraph.edges.size, view.fullGraph.edges.size)
        assertTrue(view.summary.spiProviderCount > 0)
        assertTrue(view.summary.reflectionRelationCount > 0)
    }

    private fun largeArchitectureIndex(
        packageCount: Int,
        classesPerPackage: Int,
        includeResource: Boolean = false,
        includeClassSource: Boolean = false,
    ): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val packages = (1..packageCount).associate { packageIndex ->
            val qualifiedName = "com.example.slice$packageIndex"
            qualifiedName to JvmPackageSymbol(
                id = "package:$qualifiedName",
                qualifiedName = qualifiedName,
                simpleName = "slice$packageIndex",
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        val classes = packages.values.flatMap { pkg ->
            (1..classesPerPackage).map { classIndex ->
                val qualifiedName = "${pkg.qualifiedName}.Service$classIndex"
                JvmClassSymbol(
                    id = "class:$qualifiedName",
                    qualifiedName = qualifiedName,
                    simpleName = "Service$classIndex",
                    packageName = pkg.qualifiedName,
                    moduleName = module.qualifiedName,
                    kind = JvmClassKind.CLASS,
                    source = if (includeClassSource) {
                        JvmSourceRef(
                            displayPath = "src/main/java/${qualifiedName.replace('.', '/')}.java",
                            virtualFileUrl = null,
                            startLine = 1,
                            endLine = 20,
                            decompiled = false,
                        )
                    } else {
                        null
                    },
                    origin = SourceOrigin.PROJECT_SOURCE,
                )
            }
        }
        val resource = if (includeResource) {
            JvmResourceSymbol(
                id = "resource:application.yml",
                path = "application.yml",
                kind = JvmResourceKind.YAML,
                source = null,
                origin = SourceOrigin.CONTENT_ROOT,
            )
        } else {
            null
        }
        val symbolIndex = JvmSymbolIndex(
            modulesByName = mapOf(module.qualifiedName to module),
            packagesByName = packages,
            classesByQualifiedName = classes.associateBy(JvmClassSymbol::qualifiedName),
            resourcesByPath = listOfNotNull(resource).associateBy(JvmResourceSymbol::path),
        )
        val packageRelations = packages.values.map { pkg ->
            JvmRelation(
                id = "rel:${module.id}->${pkg.id}",
                kind = JvmRelationKind.MODULE_CONTAINS_PACKAGE,
                fromSymbolId = module.id,
                toSymbolId = pkg.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
            )
        }
        val classRelations = classes.map { cls ->
            val pkg = packages.getValue(cls.packageName)
            JvmRelation(
                id = "rel:${pkg.id}->${cls.id}",
                kind = JvmRelationKind.PACKAGE_CONTAINS_CLASS,
                fromSymbolId = pkg.id,
                toSymbolId = cls.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
            )
        }
        val dependencyRelations = classes.zipWithNext().map { (from, to) ->
            JvmRelation(
                id = "rel:${from.id}->${to.id}",
                kind = JvmRelationKind.USES_TYPE,
                fromSymbolId = from.id,
                toSymbolId = to.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            )
        }
        val resourceRelations = resource?.let { config ->
            classes.firstOrNull()?.let { cls ->
                listOf(
                    JvmRelation(
                        id = "rel:${config.id}->${cls.id}",
                        kind = JvmRelationKind.REFLECTS_TO,
                        fromSymbolId = config.id,
                        toSymbolId = cls.id,
                        confidence = JvmRelationConfidence.RULE_INFERRED,
                        source = JvmRelationSource.RESOURCE_FILE,
                    ),
                )
            }
        }.orEmpty()
        return ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(packageRelations + classRelations + dependencyRelations + resourceRelations),
        )
    }

    private fun umlArchitectureIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val pkg = JvmPackageSymbol(
            id = "package:com.example.service",
            qualifiedName = "com.example.service",
            simpleName = "service",
            moduleName = module.qualifiedName,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val contract = JvmClassSymbol(
            id = "class:com.example.service.TaskProvider",
            qualifiedName = "com.example.service.TaskProvider",
            simpleName = "TaskProvider",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.INTERFACE,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val provider = JvmClassSymbol(
            id = "class:com.example.service.DefaultTaskProvider",
            qualifiedName = "com.example.service.DefaultTaskProvider",
            simpleName = "DefaultTaskProvider",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
            interfaceNames = listOf(contract.qualifiedName),
        )
        val runner = JvmClassSymbol(
            id = "class:com.example.service.TaskRunner",
            qualifiedName = "com.example.service.TaskRunner",
            simpleName = "TaskRunner",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            abstract = true,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
            docComment = "Runs queued tasks.",
        )
        val reflectionTarget = JvmClassSymbol(
            id = "class:com.example.service.TaskReflectionTarget",
            qualifiedName = "com.example.service.TaskReflectionTarget",
            simpleName = "TaskReflectionTarget",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val externalClient = JvmClassSymbol(
            id = "class:com.external.ExternalReviewClient",
            qualifiedName = "com.external.ExternalReviewClient",
            simpleName = "ExternalReviewClient",
            packageName = "com.external",
            moduleName = "library:external.jar",
            kind = JvmClassKind.CLASS,
            external = true,
            library = true,
            source = null,
            origin = SourceOrigin.LIBRARY_CLASS_JAR,
        )
        val field = JvmFieldSymbol(
            id = "field:runner-provider",
            qualifiedName = "com.example.service.TaskRunner.provider",
            simpleName = "provider",
            ownerClassName = runner.qualifiedName,
            typeName = contract.qualifiedName,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val method = JvmMethodSymbol(
            id = "method:runner-run",
            qualifiedName = "com.example.service.TaskRunner.run():void",
            simpleName = "run",
            ownerClassName = runner.qualifiedName,
            signature = "com.example.service.TaskRunner.run():void",
            parameterTypes = emptyList(),
            returnType = "void",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val symbolIndex = JvmSymbolIndex(
            modulesByName = mapOf(module.qualifiedName to module),
            packagesByName = mapOf(pkg.qualifiedName to pkg),
            classesByQualifiedName = listOf(contract, provider, runner, reflectionTarget, externalClient).associateBy(JvmClassSymbol::qualifiedName),
            fieldsByQualifiedName = mapOf(field.qualifiedName to field),
            methodsBySignature = mapOf(method.signature to method),
        )
        val relations = listOf(
            JvmRelation(
                id = "rel:provider->contract",
                kind = JvmRelationKind.IMPLEMENTS,
                fromSymbolId = provider.id,
                toSymbolId = contract.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:runner->contract",
                kind = JvmRelationKind.USES_TYPE,
                fromSymbolId = runner.id,
                toSymbolId = contract.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:runner-call-provider",
                kind = JvmRelationKind.CALLS,
                fromSymbolId = method.id,
                toSymbolId = provider.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:runner-service-loader",
                kind = JvmRelationKind.SERVICE_LOADER_LOADS,
                fromSymbolId = method.id,
                toSymbolId = contract.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:provider-spi-contract",
                kind = JvmRelationKind.SPI_PROVIDES,
                fromSymbolId = provider.id,
                toSymbolId = contract.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.RESOURCE_FILE,
            ),
            JvmRelation(
                id = "rel:runner-reflect-target",
                kind = JvmRelationKind.REFLECTS_TO,
                fromSymbolId = method.id,
                toSymbolId = reflectionTarget.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:runner-call-external",
                kind = JvmRelationKind.CALLS,
                fromSymbolId = method.id,
                toSymbolId = externalClient.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
        )
        return ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(relations),
        )
    }
}
