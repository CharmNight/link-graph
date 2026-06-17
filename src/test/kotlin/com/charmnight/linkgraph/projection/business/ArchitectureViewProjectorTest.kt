package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.architecture.ClassDiagramFastIndex
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphViewportOptions
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.indexed.requestArchitectureGraphRequest
import com.charmnight.linkgraph.application.indexed.requestClassDiagramRequest
import com.charmnight.linkgraph.application.indexed.requestPackageDependencyGraphRequest
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeReference
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeRole
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmModuleSymbol
import com.charmnight.linkgraph.jvm.index.JvmPackageSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationExtractor
import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationRole
import com.charmnight.linkgraph.model.GraphNode
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

        val view = projector.project(
            index,
            request = requestArchitectureGraphRequest().copy(
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 10, maxVisibleEdges = 12),
            ),
        )

        assertTrue(view.fullGraph.nodes.size > view.visibleGraph.nodes.size)
        assertTrue(view.visibleGraph.nodes.size <= 10)
        assertTrue(view.visibleGraph.edges.size <= 12)
        assertTrue(view.summary.truncated)
        assertEquals(view.fullGraph.nodes.size - view.visibleGraph.nodes.size, view.summary.hiddenNodeCount)
        assertTrue(view.summary.hiddenEdgeCount >= view.fullGraph.edges.size - view.visibleGraph.edges.size)
    }

    @Test
    fun architectureGraphDefaultProjectStructureUsesReadableWindowBudget() {
        val index = largeArchitectureIndex(packageCount = 18, classesPerPackage = 4, includeResource = true)

        val view = ArchitectureGraphProjector().project(index)

        assertTrue(view.fullGraph.nodes.size > view.visibleGraph.nodes.size)
        assertTrue(view.visibleGraph.nodes.size <= 12)
        assertTrue(view.visibleGraph.edges.size <= 18)
        assertTrue(view.fullGraph.nodes.none { node -> node.type == NodeType.MODULE })
        assertTrue(view.visibleGraph.nodes.any { node ->
            node.type in setOf(NodeType.COMPONENT, NodeType.SERVICE)
        })
        assertTrue(view.fullGraph.edges.any { edge ->
            edge.metadata["architecture.aggregate.level"] == "OVERVIEW" &&
                edge.metadata["jvm.relation.kind"] in setOf(
                    JvmRelationKind.CALLS.name,
                    JvmRelationKind.USES_TYPE.name,
                    JvmRelationKind.RESOURCE_BINDS.name,
                )
        })
        assertTrue(view.fullGraph.edges.none { edge ->
            edge.metadata["architecture.relation.kind"] == "PROJECT_STRUCTURE_PARENT" ||
                edge.metadata["architecture.synthetic"] == "true"
        })
        assertEquals(view.fullGraph.nodes.size - view.visibleGraph.nodes.size, view.summary.hiddenNodeCount)
    }

    @Test
    fun architectureGraphCountsOnlyRealOverflowAsCollapsedLayers() {
        val index = largeArchitectureIndex(packageCount = 18, classesPerPackage = 4)
        val projector = ArchitectureGraphProjector(
            viewportPolicy = GraphViewportPolicy(
                maxVisibleNodes = 10,
                maxVisibleEdges = 12,
                enableOverflowSummary = true,
            ),
        )

        val view = projector.project(index)

        assertTrue(view.summary.hiddenNodeCount > 0)
        assertEquals(view.summary.hiddenNodeCount, view.summary.indexed?.collapsedLayerCounts?.total())
        assertTrue(view.visibleGraph.nodes.any { node -> node.metadata["linkGraph.overflow.kind"] == "GRAPH_WINDOW" })
    }

    @Test
    fun architectureGraphDefaultsToProjectStructureWithRealOverviewRelations() {
        val index = largeArchitectureIndex(packageCount = 3, classesPerPackage = 2, includeResource = true)
        val view = ArchitectureGraphProjector().project(index)

        assertTrue(view.visibleGraph.nodes.none { node -> node.type == NodeType.MODULE })
        assertTrue(view.fullGraph.nodes.none { node -> node.type == NodeType.LAYER })
        assertTrue(view.visibleGraph.nodes.none { node -> node.type == NodeType.LAYER })
        assertTrue(view.visibleGraph.nodes.any { node -> node.type == NodeType.COMPONENT })
        assertTrue(view.fullGraph.edges.none { edge ->
            edge.metadata["architecture.relation.kind"] == "PROJECT_STRUCTURE_PARENT" ||
                edge.metadata["architecture.synthetic"] == "true" ||
                edge.metadata["indexed.relationKind"] == "PROJECT_STRUCTURE_PARENT" ||
                edge.metadata["jvm.relation.kind"] == null
        })
        assertTrue(view.visibleGraph.nodes.any { node ->
            node.type == NodeType.COMPONENT &&
                node.metadata["presentation.laneId"] == "application"
        })
        assertTrue(view.visibleGraph.nodes.any { node -> node.type == NodeType.RESOURCE })
        assertTrue(view.fullGraph.edges.none { edge ->
            edge.metadata["jvm.relation.kind"] == JvmRelationKind.MODULE_CONTAINS_PACKAGE.name
        })
        assertTrue(view.fullGraph.edges.any { edge ->
            edge.metadata["architecture.aggregate.level"] == "OVERVIEW" &&
                edge.metadata["jvm.relation.kind"] in setOf(
                    JvmRelationKind.CALLS.name,
                    JvmRelationKind.USES_TYPE.name,
                    JvmRelationKind.REFLECTS_TO.name,
                    JvmRelationKind.RESOURCE_BINDS.name,
                )
        })
        assertEquals(0, view.summary.inventoryOnlyNodeCount)
    }

    @Test
    fun projectStructureSuppressesBroadNamespaceAggregatesAndKeepsReadableComponents() {
        val index = kafkaLikeArchitectureIndex()
        val view = ArchitectureGraphProjector().project(
            index,
            request = requestArchitectureGraphRequest().copy(
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 16, maxVisibleEdges = 24),
            ),
        )
        val visibleQualifiedNames = view.visibleGraph.nodes.map { node ->
            node.metadata["architecture.qualifiedName"].orEmpty()
        }.toSet()
        val fullNodeByQualifiedName = view.fullGraph.nodes.associateBy { node ->
            node.metadata["architecture.qualifiedName"].orEmpty()
        }

        assertFalse("org" in visibleQualifiedNames, "The project structure should not lead with the top-level org namespace.")
        assertFalse("kafka" in visibleQualifiedNames, "The project structure should not lead with the top-level kafka namespace.")
        assertFalse("org" in fullNodeByQualifiedName.keys, "The project structure graph should not keep top-level org as a readable component.")
        assertFalse("kafka" in fullNodeByQualifiedName.keys, "The project structure graph should not keep top-level kafka as a readable component.")
        assertTrue("org.apache.kafka.server" in visibleQualifiedNames)
        assertTrue("org.apache.kafka.coordinator.group" in visibleQualifiedNames)
        assertTrue("org.apache.kafka.metadata" in visibleQualifiedNames)
        assertTrue("org.apache.kafka.tools" in visibleQualifiedNames)
        assertEquals("true", fullNodeByQualifiedName.getValue("org.apache.kafka.server").metadata["architecture.structureReadable"])
        assertEquals("org.apache.kafka.server", fullNodeByQualifiedName.getValue("org.apache.kafka.server").metadata["architecture.displayName"])
        assertEquals("server", fullNodeByQualifiedName.getValue("org.apache.kafka.server").metadata["architecture.displayBaseName"])
        assertEquals("kafka.server", fullNodeByQualifiedName.getValue("kafka.server").metadata["architecture.displayName"])
        assertEquals("应用层 · 组件", fullNodeByQualifiedName.getValue("org.apache.kafka.server").metadata["architecture.displaySubtitle"])
        assertTrue(view.visibleGraph.nodes.all { node ->
            node.type == NodeType.RESOURCE || node.metadata["architecture.structureReadable"] == "true"
        })
    }

    @Test
    fun projectStructureCarriesReadableNodeAndEdgeDisplayContracts() {
        val index = kafkaLikeArchitectureIndex()
        val view = ArchitectureGraphProjector().project(
            index,
            request = requestArchitectureGraphRequest().copy(
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 16, maxVisibleEdges = 24),
            ),
        )
        val serverNode = view.visibleGraph.nodes.first { node ->
            node.metadata["architecture.qualifiedName"] == "org.apache.kafka.server"
        }
        val coordinatorNode = view.visibleGraph.nodes.first { node ->
            node.metadata["architecture.qualifiedName"] == "org.apache.kafka.coordinator.group"
        }
        val serverToCoordinator = view.fullGraph.edges.first { edge ->
            edge.fromNodeId == serverNode.id && edge.toNodeId == coordinatorNode.id
        }

        assertEquals("org.apache.kafka.server", serverNode.metadata["architecture.displayName"])
        assertEquals("server", serverNode.metadata["architecture.displayBaseName"])
        assertEquals("应用层 · 组件", serverNode.metadata["architecture.displaySubtitle"])
        assertEquals("application", serverNode.metadata["presentation.laneId"])
        assertEquals("运行时调用", serverToCoordinator.metadata["architecture.displayRelation"])
        assertEquals("RUNTIME_CALL", serverToCoordinator.metadata["architecture.displayRelationKind"])
        assertTrue((serverToCoordinator.metadata["indexed.sourceCount"]?.toIntOrNull() ?: 0) >= 2)
    }

    @Test
    fun projectStructureDefaultWindowPrefersCoreRelationBackedComponentsOverSupportPackages() {
        val index = kafkaLikeArchitectureIndex()
        val view = ArchitectureGraphProjector().project(
            index,
            request = requestArchitectureGraphRequest().copy(
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 6, maxVisibleEdges = 12),
            ),
        )
        val visibleQualifiedNames = view.visibleGraph.nodes.map { node ->
            node.metadata["architecture.qualifiedName"].orEmpty()
        }.toSet()

        assertTrue("org.apache.kafka.server" in visibleQualifiedNames)
        assertTrue("org.apache.kafka.coordinator.group" in visibleQualifiedNames)
        assertTrue("org.apache.kafka.metadata" in visibleQualifiedNames)
        assertTrue("org.apache.kafka.tools" in visibleQualifiedNames)
        assertFalse("kafka.docker" in visibleQualifiedNames)
        assertFalse("kafka.examples" in visibleQualifiedNames)
        assertFalse("org.apache.kafka.common.test" in visibleQualifiedNames)
    }

    @Test
    fun projectStructureDisplayMetadataUsesEvidenceRatherThanDemoPackageNames() {
        val index = serverNamedArchitectureIndex()
        val view = ArchitectureGraphProjector().project(
            index,
            request = requestArchitectureGraphRequest().copy(
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 8, maxVisibleEdges = 8),
            ),
        )
        val serverNode = view.fullGraph.nodes.first { node ->
            node.metadata["architecture.qualifiedName"] == "com.acme.server"
        }

        assertEquals("com.acme.server", serverNode.metadata["architecture.displayName"])
        assertEquals("server", serverNode.metadata["architecture.displayBaseName"])
        assertEquals("应用层 · 组件", serverNode.metadata["architecture.displaySubtitle"])
        assertFalse(
            serverNode.metadata["architecture.displaySubtitle"].orEmpty().contains("Broker"),
            "Project structure subtitles must be derived from graph evidence, not from demo-specific package names.",
        )
    }

    @Test
    fun architectureOverviewDoesNotRenderDependencyDocumentationResources() {
        val index = architectureIndexWithDependencyDocumentationResources()
        val view = ArchitectureGraphProjector().project(index)
        val resourceDebug = view.fullGraph.nodes
            .filter { node -> node.type == NodeType.RESOURCE }
            .joinToString("\n") { node -> "${node.title} ${node.metadata}" }

        assertFalse(
            view.fullGraph.nodes.any { node ->
                node.type == NodeType.RESOURCE &&
                    (
                        node.title in setOf("CHANGELOG.md", "LICENSE.md") ||
                            node.metadata["resource.path"]?.contains("/node_modules/") == true ||
                            node.metadata["resource.group"] == "web"
                        )
            },
            resourceDebug,
        )
        assertTrue(
            view.fullGraph.nodes.any { node ->
                node.type == NodeType.RESOURCE &&
                    node.title == "Project Resources" &&
                    node.metadata["resource.group"] == "src"
            },
            resourceDebug,
        )
    }

    @Test
    fun architectureOverviewDoesNotLetExternalDependenciesOwnTheDefaultCanvas() {
        val index = packageBoundaryIndex()
        val view = ArchitectureGraphProjector().project(index)

        assertTrue(view.fullGraph.nodes.any { node -> node.type == NodeType.COMPONENT })
        assertTrue(view.fullGraph.nodes.none { node -> node.type == NodeType.LIBRARY })
        assertTrue(view.fullGraph.edges.none { edge -> edge.metadata["architecture.aggregate"] in setOf("LIBRARY", "JDK") })
        assertEquals(0, view.summary.libraryCount)
        assertEquals(0, view.summary.indexed?.visibleLayerCounts?.externalLibrary)
        assertEquals(0, view.summary.indexed?.visibleLayerCounts?.jdk)
        assertTrue(index.graph.nodes.any { node -> node.kind == ArchitectureNodeKind.LIBRARY })
        assertTrue(index.graph.nodes.any { node -> node.kind == ArchitectureNodeKind.JDK })
        assertTrue(view.summary.externalDependencyGroupCount > 0)
        assertTrue(view.summary.jdkGroupCount > 0)
    }

    @Test
    fun architectureOverviewCanOptInToExternalDependencyNodesWithoutTurningIntoDependencyGraph() {
        val index = packageBoundaryIndex()
        val request = IndexedGraphRequest(
            view = IndexedGraphView.ARCHITECTURE,
            includeExternalLibraries = true,
            includeJdk = true,
        )

        val view = ArchitectureGraphProjector().project(index, request)

        assertTrue(view.fullGraph.nodes.any { node ->
            node.type == NodeType.LIBRARY &&
                node.metadata["architecture.node.kind"] == ArchitectureNodeKind.LIBRARY.name
        })
        assertTrue(view.fullGraph.nodes.any { node ->
            node.type == NodeType.LIBRARY &&
                node.metadata["architecture.node.kind"] == ArchitectureNodeKind.JDK.name
        })
        assertTrue(view.fullGraph.edges.any { edge -> edge.metadata["architecture.aggregate"] == "LIBRARY" })
        assertTrue(view.fullGraph.edges.any { edge -> edge.metadata["architecture.aggregate"] == "JDK" })
        assertTrue(view.fullGraph.edges.none { edge ->
            edge.metadata["architecture.relation.kind"] == "PROJECT_STRUCTURE_PARENT" ||
                edge.metadata["architecture.synthetic"] == "true"
        })
    }

    @Test
    fun packageDependencyScopeKeepsExactPackageRelations() {
        val index = largeArchitectureIndex(packageCount = 3, classesPerPackage = 2, includeResource = true)
        val view = ArchitectureGraphProjector().project(index, requestPackageDependencyGraphRequest())

        assertTrue(view.visibleGraph.nodes.any { node -> node.type == NodeType.PACKAGE })
        assertEquals(0, view.visibleGraph.nodes.count { node -> node.type == NodeType.COMPONENT })
        assertTrue(view.fullGraph.edges.all { edge -> edge.metadata["architecture.aggregate.level"] == "PACKAGE" })
        assertTrue(view.fullGraph.edges.any { edge -> edge.metadata["architecture.aggregate"] == "PACKAGE" })
    }

    @Test
    fun packageDependencyScopeKeepsExternalJdkAndResourceBoundaryRelations() {
        val index = packageBoundaryIndex()
        val view = ArchitectureGraphProjector().project(index, requestPackageDependencyGraphRequest())

        assertTrue(view.fullGraph.nodes.any { node -> node.type == NodeType.PACKAGE })
        assertTrue(view.fullGraph.nodes.any { node ->
            node.type == NodeType.LIBRARY &&
                node.metadata["architecture.node.kind"] == ArchitectureNodeKind.LIBRARY.name
        })
        assertTrue(view.fullGraph.nodes.any { node ->
            node.type == NodeType.LIBRARY &&
                node.metadata["architecture.node.kind"] == ArchitectureNodeKind.JDK.name
        })
        assertTrue(view.fullGraph.nodes.any { node -> node.type == NodeType.RESOURCE })
        assertTrue(view.fullGraph.edges.any { edge ->
            edge.metadata["architecture.aggregate.level"] == "PACKAGE" &&
                edge.metadata["architecture.aggregate"] == "LIBRARY"
        })
        assertTrue(view.fullGraph.edges.any { edge ->
            edge.metadata["architecture.aggregate.level"] == "PACKAGE" &&
                edge.metadata["architecture.aggregate"] == "JDK"
        })
        assertTrue(view.fullGraph.edges.any { edge ->
            edge.metadata["architecture.aggregate.level"] == "PACKAGE" &&
                edge.metadata["architecture.aggregate"] == "RESOURCE"
        })
        assertTrue(view.fullGraph.nodes.any { node ->
            node.type == NodeType.RESOURCE &&
                node.metadata["presentation.laneId"] == "resource" &&
                node.metadata["presentation.role"] == "RESOURCE"
        })
        assertTrue(view.fullGraph.nodes.any { node ->
            node.type == NodeType.LIBRARY &&
                node.metadata["architecture.node.kind"] == ArchitectureNodeKind.LIBRARY.name &&
                node.metadata["presentation.laneId"] == "external" &&
                node.metadata["presentation.role"] == "EXTERNAL"
        })
        assertTrue(view.fullGraph.nodes.any { node ->
            node.type == NodeType.LIBRARY &&
                node.metadata["architecture.node.kind"] == ArchitectureNodeKind.JDK.name &&
                node.metadata["presentation.laneId"] == "external" &&
                node.metadata["presentation.role"] == "EXTERNAL"
        })
    }

    @Test
    fun architectureGraphPresentationUsesBackendDisplayLayers() {
        val view = ArchitectureGraphProjector().project(
            index = layeredArchitectureIndex(),
            request = requestPackageDependencyGraphRequest().copy(
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 16, maxVisibleEdges = 24),
            ),
        )

        assertEquals(
            listOf("entry", "application", "domain", "data", "resource", "external"),
            view.presentation.lanes.map { it.id },
        )
        assertEquals("入口层", view.presentation.lanes.first { it.id == "entry" }.label)
        assertEquals("组件", view.presentation.controls.primaryScope)
        assertEquals(listOf("组件", "包", "类"), view.presentation.controls.availableScopes)
        assertVisibleNodesHavePresentationMetadata(view.visibleGraph.nodes)

        fun laneIdForPackage(qualifiedName: String): String? =
            view.fullGraph.nodes.first { node -> node.metadata["architecture.qualifiedName"] == qualifiedName }
                .metadata["presentation.laneId"]

        assertEquals("entry", laneIdForPackage("com.example.order.controller"))
        assertEquals("application", laneIdForPackage("com.example.order.service"))
        assertEquals("domain", laneIdForPackage("com.example.order.domain"))
        assertEquals("data", laneIdForPackage("com.example.order.repository"))
        assertEquals("data", laneIdForPackage("com.example.order.infra.config"))
    }

    @Test
    fun architectureGraphPresentationHiddenBucketsReflectHiddenNodes() {
        val view = ArchitectureGraphProjector().project(
            index = layeredArchitectureIndex(),
            request = requestPackageDependencyGraphRequest().copy(
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 2, maxVisibleEdges = 1),
            ),
        )
        val hiddenNodeIds = view.fullGraph.nodes.map { it.id }.toSet() - view.visibleGraph.nodes.map { it.id }.toSet()

        assertTrue(hiddenNodeIds.isNotEmpty())
        assertEquals(hiddenNodeIds, view.presentation.hiddenBuckets.flatMap { it.nodeIds }.toSet())
        assertTrue(view.presentation.hiddenBuckets.all { bucket -> bucket.count == bucket.nodeIds.size })
    }

    @Test
    fun architectureGraphDoesNotPromoteTechnicalPackagesExternalLibrariesOrJdkToServices() {
        val index = mixedBoundaryIndex()
        val serviceNodes = index.graph.nodes.filter { node -> node.kind == ArchitectureNodeKind.SERVICE }

        assertFalse(serviceNodes.any { node -> node.qualifiedName == "com.example.order" })
        assertFalse(serviceNodes.any { node -> node.qualifiedName == "com.example.review" })
        assertFalse(serviceNodes.any { node -> node.qualifiedName.contains("intellij") || node.qualifiedName.startsWith("java") })

        val view = ArchitectureGraphProjector().project(index)

        assertTrue(index.graph.nodes.any { node ->
            node.kind == ArchitectureNodeKind.LIBRARY &&
                node.metadata["architecture.boundary.kind"] == "EXTERNAL_LIBRARY_GROUP"
        })
        assertTrue(index.graph.nodes.any { node ->
            node.kind == ArchitectureNodeKind.JDK &&
                node.metadata["architecture.boundary.kind"] == "JDK_GROUP"
        })
        assertTrue(view.fullGraph.nodes.none { node -> node.type == NodeType.LIBRARY })
        assertTrue(view.visibleGraph.nodes.none { node ->
            node.type == NodeType.SERVICE &&
                node.metadata["indexed.layerKind"] in setOf("EXTERNAL_LIBRARY", "JDK")
        })
        assertEquals(0, view.summary.inventoryOnlyNodeCount)
        assertTrue(view.fullGraph.nodes.none { node -> node.title == "git" })
        assertTrue(index.graph.edges.any { edge ->
            "rel:order->library" in edge.sourceRelationIds &&
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.CALLS.name
        })
        assertTrue(index.graph.edges.any { edge ->
            "rel:order->jdk" in edge.sourceRelationIds &&
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.USES_TYPE.name
        })
    }

    @Test
    fun explicitMultiLayerEvidencePromotesBusinessBoundaryToService() {
        val index = evidencedServiceBoundaryIndex()
        val serviceNodes = index.graph.nodes.filter { node -> node.kind == ArchitectureNodeKind.SERVICE }
        val view = ArchitectureGraphProjector().project(index)

        assertTrue(serviceNodes.any { node -> node.qualifiedName == "com.example.order" })
        assertTrue(serviceNodes.any { node ->
            node.qualifiedName == "com.example.order" &&
                node.metadata["architecture.inferred"] == "true" &&
                node.metadata["architecture.inference.reason"] == "PROJECT_SERVICE_BOUNDARY"
        })
        assertTrue(view.fullGraph.nodes.any { node ->
            node.type == NodeType.SERVICE &&
                node.metadata["architecture.qualifiedName"] == "com.example.order" &&
                node.metadata["architecture.inferred"] == "true"
        })
    }

    @Test
    fun organizationRootWithTechnicalLayersDoesNotBecomeServiceBoundary() {
        val index = organizationRootLayerIndex()
        val serviceNodes = index.graph.nodes.filter { node -> node.kind == ArchitectureNodeKind.SERVICE }
        val view = ArchitectureGraphProjector().project(index)

        assertFalse(serviceNodes.any { node -> node.qualifiedName == "com.example.app" })
        assertFalse(index.graph.nodes.any { node ->
            node.kind == ArchitectureNodeKind.COMPONENT &&
                node.qualifiedName == "com.example.app"
        })
        assertTrue(index.graph.nodes.any { node ->
            node.kind == ArchitectureNodeKind.COMPONENT &&
                node.qualifiedName == "com.example.app.service"
        })
        assertTrue(index.graph.nodes.any { node ->
            node.kind == ArchitectureNodeKind.COMPONENT &&
                node.qualifiedName == "com.example.app.repository"
        })
        assertTrue(view.fullGraph.nodes.none { node ->
            node.metadata["architecture.qualifiedName"] == "com.example.app"
        })
        assertTrue(view.fullGraph.nodes.none { node ->
            node.type == NodeType.SERVICE &&
            node.metadata["architecture.qualifiedName"] == "com.example.app"
        })
    }

    @Test
    fun multiRootProductNamespaceDoesNotBecomeServiceBoundary() {
        val index = multiRootProductNamespaceIndex()
        val serviceNodes = index.graph.nodes.filter { node -> node.kind == ArchitectureNodeKind.SERVICE }
        val view = ArchitectureGraphProjector().project(
            index,
            request = requestArchitectureGraphRequest().copy(
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 8, maxVisibleEdges = 12),
            ),
        )

        assertFalse(serviceNodes.any { node -> node.qualifiedName == "org.apache.kafka" })
        assertTrue(index.graph.nodes.any { node ->
            node.kind == ArchitectureNodeKind.COMPONENT &&
                node.qualifiedName == "org.apache.kafka.controller"
        })
        assertTrue(view.fullGraph.nodes.none { node ->
            node.type == NodeType.SERVICE &&
                node.metadata["architecture.qualifiedName"] == "org.apache.kafka"
        })
    }

    @Test
    fun architectureGraphDoesNotAnchorOnBroadRootComponentWhenSpecificNodesExist() {
        val index = organizationRootLayerIndex()
        val view = ArchitectureGraphProjector().project(index)
        val anchorNode = view.visibleGraph.nodes.first { node -> node.id == view.anchorNodeId }

        assertFalse(
            anchorNode.type == NodeType.COMPONENT &&
                anchorNode.metadata["architecture.qualifiedName"] == "com.example.app",
        )
    }

    @Test
    fun architectureGraphCarriesIndexedAuthenticityMetadataAndLayerSummary() {
        val index = largeArchitectureIndex(packageCount = 3, classesPerPackage = 2, includeResource = true)
        val view = ArchitectureGraphProjector().project(index)

        assertTrue(view.visibleGraph.nodes.isNotEmpty())
        assertTrue(view.visibleGraph.nodes.all { node -> node.metadata["indexed.layerKind"] != null })
        assertTrue(view.visibleGraph.nodes.all { node -> node.metadata["indexed.nodeRole"] != null })
        assertTrue(view.visibleGraph.nodes.all { node -> node.metadata["indexed.sourceKind"] != null })
        assertTrue(view.visibleGraph.edges.all { edge -> edge.metadata["indexed.relationKind"] != null })
        assertTrue(view.visibleGraph.edges.all { edge -> edge.metadata["indexed.relationLayer"] != null })
        assertTrue(view.visibleGraph.edges.all { edge -> edge.metadata["indexed.sourceCount"] != null })
        assertEquals(view.visibleGraph.nodes.size, view.summary.indexed?.visibleLayerCounts?.total())
        assertTrue((view.summary.indexed?.projectLayerCounts?.projectSource ?: 0) > 0)
        assertTrue((view.summary.indexed?.projectLayerCounts?.resource ?: 0) > 0)
        assertEquals(0, view.summary.indexed?.collapsedLayerCounts?.total())
        assertTrue(view.visibleGraph.nodes.all { node ->
            node.metadata["indexed.collapsedCount"] == "0"
        })
        assertTrue(view.visibleGraph.nodes.any { node ->
            (node.metadata["indexed.memberClassCount"]?.toIntOrNull() ?: 0) > 0
        })
    }

    @Test
    fun projectStructureEdgesCarryReadableRelationSemanticsAndSourceEvidence() {
        val index = relationSemanticArchitectureIndex()
        val view = ArchitectureGraphProjector().project(index)

        val overviewEdges = view.fullGraph.edges.filter { edge ->
            edge.metadata["architecture.aggregate.level"] == "OVERVIEW"
        }

        assertTrue(overviewEdges.isNotEmpty())
        assertTrue(overviewEdges.all { edge -> edge.metadata["architecture.displayRelationKind"] != null })
        assertTrue(overviewEdges.all { edge -> edge.metadata["architecture.displayRelation"] != null })
        assertTrue(overviewEdges.all { edge -> edge.metadata["architecture.sourceRelationIds"].orEmpty().isNotBlank() })
        assertTrue(overviewEdges.all { edge -> edge.metadata["indexed.sourceRelationIds"].orEmpty().isNotBlank() })
        assertTrue(overviewEdges.any { edge ->
            edge.metadata["jvm.relation.kind"] == JvmRelationKind.CALLS.name &&
                edge.metadata["architecture.displayRelationKind"] == "RUNTIME_CALL"
        })
        assertTrue(overviewEdges.any { edge ->
            edge.metadata["jvm.relation.kind"] == JvmRelationKind.USES_TYPE.name &&
                edge.metadata["architecture.displayRelationKind"] == "TYPE_DEPENDENCY"
        })
        assertTrue(overviewEdges.any { edge ->
            edge.metadata["jvm.relation.kind"] == JvmRelationKind.RESOURCE_BINDS.name &&
                edge.metadata["architecture.displayRelationKind"] == "RESOURCE_BINDING"
        })
        assertTrue(view.summary.projectStructureRelationGroups.isNotEmpty())
        assertTrue(view.summary.projectStructureRelationGroups.all { group -> group.sourceRelationIds.isNotEmpty() })
        assertTrue(view.summary.projectStructureRelationGroups.any { group ->
            group.displayRelationKind == "RUNTIME_CALL" &&
                JvmRelationKind.CALLS.name in group.relationKinds
        })
    }

    @Test
    fun architectureGraphCarriesSourceSamplesForAggregateNodes() {
        val index = largeArchitectureIndex(packageCount = 2, classesPerPackage = 2, includeClassSource = true)
        val view = ArchitectureGraphProjector().project(index)

        val aggregateNode = view.fullGraph.nodes.first { node -> node.type == NodeType.COMPONENT }

        assertTrue((aggregateNode.metadata["architecture.sourceSample.count"]?.toIntOrNull() ?: 0) > 0)
        assertTrue(aggregateNode.metadata["architecture.sourceSample.0.nodeId"].orEmpty().startsWith("class:"))
        assertTrue(aggregateNode.metadata["architecture.sourceSample.0.filePath"].orEmpty().endsWith(".java"))
        assertEquals("1", aggregateNode.metadata["architecture.sourceSample.0.startLine"])
        assertEquals(aggregateNode.metadata["architecture.sourceSample.0.nodeId"], aggregateNode.metadata["source.navigation.nodeId"])
        assertEquals(aggregateNode.metadata["architecture.sourceSample.0.filePath"], aggregateNode.metadata["source.navigation.filePath"])
        assertEquals("1", aggregateNode.metadata["source.navigation.startLine"])
    }

    @Test
    fun architectureGraphPrefersSourceBackedAggregateAnchor() {
        val index = largeArchitectureIndex(packageCount = 2, classesPerPackage = 2, includeClassSource = true)
        val view = ArchitectureGraphProjector().project(index)

        val anchorNode = view.visibleGraph.nodes.first { node -> node.id == view.anchorNodeId }

        assertTrue(anchorNode.type in setOf(NodeType.LAYER, NodeType.SERVICE, NodeType.COMPONENT, NodeType.RESOURCE))
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
        assertEquals(view.fullGraph.nodes.size - view.visibleGraph.nodes.size, view.summary.hiddenNodeCount)
        assertTrue(view.summary.hiddenEdgeCount >= view.fullGraph.edges.size - view.visibleGraph.edges.size)
    }

    @Test
    fun classDiagramDefaultsToATypeNeighborhoodNotWholeProjectInventory() {
        val index = largeArchitectureIndex(packageCount = 10, classesPerPackage = 8)

        val view = ClassDiagramProjector().project(index)

        assertTrue(view.visibleGraph.nodes.isNotEmpty())
        assertTrue(view.visibleGraph.nodes.size <= 24)
        assertEquals(view.fullGraph.nodes.size, view.summary.scopeTypeCount)
        assertTrue(view.summary.projectTypeCount > view.summary.scopeTypeCount)
        assertTrue(view.summary.projectClassCount >= view.summary.classCount)
        assertEquals("CLASS_DIAGRAM", view.summary.indexed?.view)
        assertEquals(view.visibleGraph.nodes.size, view.summary.indexed?.visibleNodeCount)
        assertEquals(view.summary.scopeTypeCount, view.summary.indexed?.scopedNodeCount)
        assertEquals(view.summary.neighborhoodCandidateTypeCount, view.summary.indexed?.candidateNodeCount)
        assertEquals(view.summary.hiddenNodeCount, view.summary.indexed?.hiddenNodeCount)
        assertEquals(view.summary.hiddenEdgeCount, view.summary.indexed?.hiddenEdgeCount)
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
    fun classDiagramVisibleGraphIsBackendReadableProjectionNotFrontendOnlyFiltering() {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val pkg = JvmPackageSymbol(
            id = "package:com.example.readable",
            qualifiedName = "com.example.readable",
            simpleName = "readable",
            moduleName = module.qualifiedName,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        fun cls(simpleName: String, kind: JvmClassKind = JvmClassKind.CLASS): JvmClassSymbol =
            JvmClassSymbol(
                id = "class:${pkg.qualifiedName}.$simpleName",
                qualifiedName = "${pkg.qualifiedName}.$simpleName",
                simpleName = simpleName,
                packageName = pkg.qualifiedName,
                moduleName = module.qualifiedName,
                kind = kind,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        val anchor = cls("OrderService")
        val contract = cls("PaymentPort", JvmClassKind.INTERFACE)
        val repository = cls("OrderRepository")
        val result = cls("OrderResult", JvmClassKind.RECORD)
        val localOnly = cls("OrderServiceTest")
        val secondary = cls("SecondaryMapper")
        val symbolIndex = JvmSymbolIndex(
            modulesByName = mapOf(module.qualifiedName to module),
            packagesByName = mapOf(pkg.qualifiedName to pkg),
            classesByQualifiedName = listOf(anchor, contract, repository, result, localOnly, secondary)
                .associateBy(JvmClassSymbol::qualifiedName),
        )
        fun relation(
            id: String,
            from: JvmClassSymbol,
            to: JvmClassSymbol,
            role: ClassDiagramRelationRole,
            usedInBody: Boolean = false,
            heldByField: Boolean = false,
        ): JvmRelation =
            JvmRelation(
                id = id,
                kind = if (role == ClassDiagramRelationRole.METHOD_CALL) JvmRelationKind.CALLS else JvmRelationKind.USES_TYPE,
                fromSymbolId = from.id,
                toSymbolId = to.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
                metadata = ClassDiagramRelationExtractor.metadataFor(
                    role = role,
                    evidence = "${role.label} ${to.qualifiedName}",
                    memberName = id,
                    usedInBody = usedInBody,
                    heldByField = heldByField,
                ),
            )
        val index = ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(
                listOf(
                    relation("rel:implements", anchor, contract, ClassDiagramRelationRole.IMPLEMENTS),
                    relation("rel:field", anchor, repository, ClassDiagramRelationRole.FIELD, heldByField = true),
                    relation("rel:return", anchor, result, ClassDiagramRelationRole.METHOD_RETURN),
                    relation("rel:local", localOnly, anchor, ClassDiagramRelationRole.LOCAL_TYPE, usedInBody = true),
                    relation("rel:secondary", repository, secondary, ClassDiagramRelationRole.METHOD_PARAMETER),
                ),
            ),
        )

        val view = ClassDiagramProjector().project(index, anchor.id)

        assertEquals(
            setOf(anchor.id, contract.id, repository.id, result.id),
            view.visibleGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertEquals(
            setOf("rel:implements", "rel:field", "rel:return"),
            view.visibleGraph.edges.map { edge -> edge.id }.toSet(),
        )
        assertEquals(view.visibleGraph.nodes.size, view.summary.indexed?.visibleNodeCount)
        assertTrue(view.summary.hiddenNodeCount >= 1)
        assertTrue(view.presentation.hiddenBuckets.flatMap { bucket -> bucket.nodeIds }.contains(localOnly.id))
    }

    @Test
    fun classDiagramProjectsUmlStructureRuntimeIntegrationAndOneHopExternalGraph() {
        val index = umlArchitectureIndex()

        val view = ClassDiagramProjector().project(index, "package:com.example.service")
        val fullSignatures = view.fullGraph.nodes.mapNotNull { node -> node.signature }.toSet()

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
                edge.metadata["uml.relation.kind"] == "REALIZATION" &&
                    edge.metadata["jvm.relation.kind"] == JvmRelationKind.IMPLEMENTS.name &&
                    edge.metadata["classDiagram.relation.role"] == ClassDiagramRelationRole.IMPLEMENTS.name
            },
            "UML class diagram should keep inheritance/implementation relations.",
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.metadata["uml.relation.kind"] == "ASSOCIATION" &&
                    edge.metadata["jvm.relation.kind"] == JvmRelationKind.USES_TYPE.name &&
                    edge.metadata["classDiagram.relation.role"] == ClassDiagramRelationRole.FIELD.name
            },
            "UML class diagram should keep field-backed association relations.",
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.metadata["uml.relation.kind"] == "DEPENDENCY" &&
                    edge.metadata["jvm.relation.kind"] == JvmRelationKind.CALLS.name &&
                    edge.metadata["classDiagram.relation.role"] == ClassDiagramRelationRole.METHOD_CALL.name
            },
            "UML class diagram should keep method body call relations when backend relation metadata marks them as class-diagram calls.",
        )
        assertFalse(
            view.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] in setOf(
                    JvmRelationKind.SERVICE_LOADER_LOADS.name,
                    JvmRelationKind.SPI_PROVIDES.name,
                    JvmRelationKind.REFLECTS_TO.name,
                )
            },
            "UML class diagram should not mix SPI, ServiceLoader, or reflection relations into class structure.",
        )
        assertTrue("com.external.ExternalReviewClient" in fullSignatures, "Class diagram should include backend-marked method call targets.")
        assertTrue("java.time.Clock" in fullSignatures, "Class diagram should include JDK one-hop dependency classes.")
        assertEquals(1, view.summary.indexed?.externalNodeCount)
        assertEquals(1, view.summary.indexed?.jdkNodeCount)
        assertEquals(1, view.fullGraph.nodes.count { node -> node.metadata["indexed.layerKind"] == "EXTERNAL_LIBRARY" })
        assertEquals(1, view.fullGraph.nodes.count { node -> node.metadata["indexed.layerKind"] == "JDK" })
        assertEquals(1, view.summary.indexed?.scopedLayerCounts?.externalLibrary)
        assertEquals(1, view.summary.indexed?.scopedLayerCounts?.jdk)
        assertEquals(1, view.summary.fieldCount)
        assertTrue(view.fullGraph.edges.size >= view.visibleGraph.edges.size)
        assertTrue(view.summary.hiddenEdgeCount > 0)
        assertEquals(0, view.summary.spiProviderCount)
        assertEquals(0, view.summary.reflectionRelationCount)
    }

    @Test
    fun classDiagramPresentationUsesZonesAndNodeRoles() {
        val view = ClassDiagramProjector().project(
            index = umlArchitectureIndex(),
            scopeNodeId = "class:com.example.service.TaskRunner",
        )

        assertEquals(
            listOf("abstraction", "caller", "anchor", "collaborator", "output"),
            view.presentation.lanes.map { it.id },
        )
        assertEquals("class:com.example.service.TaskRunner", view.presentation.target.nodeId)
        assertEquals("TaskRunner", view.presentation.target.title)
        assertEquals("", view.presentation.controls.primaryScope)
        assertEquals(emptyList(), view.presentation.controls.availableScopes)
        assertVisibleNodesHavePresentationMetadata(view.visibleGraph.nodes)

        val nodesById = view.visibleGraph.nodes.associateBy { it.id }
        assertEquals("ANCHOR", nodesById.getValue("class:com.example.service.TaskRunner").metadata["presentation.role"])
        assertEquals("anchor", nodesById.getValue("class:com.example.service.TaskRunner").metadata["presentation.laneId"])
        assertEquals("INTERFACE", nodesById.getValue("class:com.example.service.TaskProvider").metadata["presentation.role"])
        assertEquals("abstraction", nodesById.getValue("class:com.example.service.TaskProvider").metadata["presentation.laneId"])
    }

    @Test
    fun classDiagramPresentationHiddenBucketsReflectHiddenNodes() {
        val view = ClassDiagramProjector().project(
            index = umlArchitectureIndex(),
            scopeNodeId = "class:com.example.service.TaskRunner",
            request = requestClassDiagramRequest("class:com.example.service.TaskRunner").copy(
                viewport = IndexedGraphViewportOptions(maxVisibleNodes = 2, maxVisibleEdges = 1),
            ),
        )
        val hiddenNodeIds = view.fullGraph.nodes.map { it.id }.toSet() - view.visibleGraph.nodes.map { it.id }.toSet()

        assertTrue(hiddenNodeIds.isNotEmpty())
        assertEquals(hiddenNodeIds, view.presentation.hiddenBuckets.flatMap { it.nodeIds }.toSet())
        assertTrue(view.presentation.hiddenBuckets.all { bucket -> bucket.count == bucket.nodeIds.size })
    }

    @Test
    fun classDiagramFastIndexResolvesSamePackageSimpleFieldTypesAsAssociations() {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val pkg = JvmPackageSymbol(
            id = "package:com.example.classdiagram",
            qualifiedName = "com.example.classdiagram",
            simpleName = "classdiagram",
            moduleName = module.qualifiedName,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val contract = JvmClassSymbol(
            id = "class:com.example.classdiagram.TaskProvider",
            qualifiedName = "com.example.classdiagram.TaskProvider",
            simpleName = "TaskProvider",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.INTERFACE,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val runner = JvmClassSymbol(
            id = "class:com.example.classdiagram.TaskRunner",
            qualifiedName = "com.example.classdiagram.TaskRunner",
            simpleName = "TaskRunner",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val field = JvmFieldSymbol(
            id = "field:runner-provider",
            qualifiedName = "com.example.classdiagram.TaskRunner.provider",
            simpleName = "provider",
            ownerClassName = runner.qualifiedName,
            typeName = "TaskProvider",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val symbolIndex = JvmSymbolIndex(
            modulesByName = mapOf(module.qualifiedName to module),
            packagesByName = mapOf(pkg.qualifiedName to pkg),
            classesByQualifiedName = listOf(contract, runner).associateBy(JvmClassSymbol::qualifiedName),
            fieldsByQualifiedName = mapOf(field.qualifiedName to field),
        )

        val index = ClassDiagramFastIndex.fromSymbols(symbolIndex)
        val view = ClassDiagramProjector().project(index, runner.id)

        assertTrue(
            index.relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.USES_TYPE &&
                    relation.fromSymbolId == runner.id &&
                    relation.toSymbolId == contract.id
            },
            "Same-package simple field types must be resolved before class diagram projection.",
        )
        assertTrue(
            view.fullGraph.edges.any { edge ->
                edge.fromNodeId == runner.id &&
                    edge.toNodeId == contract.id &&
                    edge.metadata["uml.relation.kind"] == "ASSOCIATION"
            },
            "Field-backed type usage should be rendered as a UML association.",
        )
    }

    @Test
    fun classDiagramRendersFunctionReturnFieldTypesAsDependencies() {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val pkg = JvmPackageSymbol(
            id = "package:com.example.classdiagram",
            qualifiedName = "com.example.classdiagram",
            simpleName = "classdiagram",
            moduleName = module.qualifiedName,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val store = JvmClassSymbol(
            id = "class:com.example.classdiagram.ArtifactStore",
            qualifiedName = "com.example.classdiagram.ArtifactStore",
            simpleName = "ArtifactStore",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val writer = JvmClassSymbol(
            id = "class:com.example.classdiagram.ConfirmedDraftArtifactWriter",
            qualifiedName = "com.example.classdiagram.ConfirmedDraftArtifactWriter",
            simpleName = "ConfirmedDraftArtifactWriter",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val providerField = JvmFieldSymbol(
            id = "field:writer-store-provider",
            qualifiedName = "com.example.classdiagram.ConfirmedDraftArtifactWriter.artifactStoreProvider",
            simpleName = "artifactStoreProvider",
            ownerClassName = writer.qualifiedName,
            typeName = "kotlin.jvm.functions.Function0<com.example.classdiagram.ArtifactStore>",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
            typeReferences = listOf(
                JvmFieldTypeReference("com.example.classdiagram.ArtifactStore", JvmFieldTypeRole.FUNCTION_RETURN),
            ),
        )
        val symbolIndex = JvmSymbolIndex(
            modulesByName = mapOf(module.qualifiedName to module),
            packagesByName = mapOf(pkg.qualifiedName to pkg),
            classesByQualifiedName = listOf(store, writer).associateBy(JvmClassSymbol::qualifiedName),
            fieldsByQualifiedName = mapOf(providerField.qualifiedName to providerField),
        )

        val index = ClassDiagramFastIndex.fromSymbols(symbolIndex)
        val view = ClassDiagramProjector().project(index, writer.id)

        assertTrue(
            index.relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.USES_TYPE &&
                    relation.fromSymbolId == writer.id &&
                    relation.toSymbolId == store.id
            },
            "Function-return field target should remain a type-usage relation.",
        )
        assertTrue(
            view.fullGraph.edges.any { edge ->
                edge.fromNodeId == writer.id &&
                    edge.toNodeId == store.id &&
                    edge.metadata["uml.relation.kind"] == "DEPENDENCY"
            },
            "A provider/function field return type is not directly held by the class and must render as dependency.",
        )
        assertFalse(
            view.fullGraph.edges.any { edge ->
                edge.fromNodeId == writer.id &&
                    edge.toNodeId == store.id &&
                    edge.metadata["uml.relation.kind"] == "ASSOCIATION"
            },
            "Function-return field target must not be promoted to association by broad type-name matching.",
        )
    }

    @Test
    fun classDiagramRendersCollectionElementFieldTypesAsAssociations() {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val pkg = JvmPackageSymbol(
            id = "package:com.example.classdiagram",
            qualifiedName = "com.example.classdiagram",
            simpleName = "classdiagram",
            moduleName = module.qualifiedName,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val entry = JvmClassSymbol(
            id = "class:com.example.classdiagram.DraftWorkbenchEntry",
            qualifiedName = "com.example.classdiagram.DraftWorkbenchEntry",
            simpleName = "DraftWorkbenchEntry",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val writer = JvmClassSymbol(
            id = "class:com.example.classdiagram.EntryBatchWriter",
            qualifiedName = "com.example.classdiagram.EntryBatchWriter",
            simpleName = "EntryBatchWriter",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val entriesField = JvmFieldSymbol(
            id = "field:writer-entries",
            qualifiedName = "com.example.classdiagram.EntryBatchWriter.entries",
            simpleName = "entries",
            ownerClassName = writer.qualifiedName,
            typeName = "java.util.List<com.example.classdiagram.DraftWorkbenchEntry>",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
            typeReferences = listOf(
                JvmFieldTypeReference("java.util.List", JvmFieldTypeRole.DIRECT_VALUE),
                JvmFieldTypeReference("com.example.classdiagram.DraftWorkbenchEntry", JvmFieldTypeRole.COLLECTION_ELEMENT),
            ),
        )
        val symbolIndex = JvmSymbolIndex(
            modulesByName = mapOf(module.qualifiedName to module),
            packagesByName = mapOf(pkg.qualifiedName to pkg),
            classesByQualifiedName = listOf(entry, writer).associateBy(JvmClassSymbol::qualifiedName),
            fieldsByQualifiedName = mapOf(entriesField.qualifiedName to entriesField),
        )

        val view = ClassDiagramProjector().project(ClassDiagramFastIndex.fromSymbols(symbolIndex), writer.id)

        assertTrue(
            view.fullGraph.edges.any { edge ->
                edge.fromNodeId == writer.id &&
                    edge.toNodeId == entry.id &&
                    edge.metadata["uml.relation.kind"] == "ASSOCIATION"
            },
            "A class that stores a collection of target instances still owns an association to the element type.",
        )
    }

    private fun layeredArchitectureIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val packageNames = listOf(
            "com.example.order.controller",
            "com.example.order.service",
            "com.example.order.domain",
            "com.example.order.repository",
            "com.example.order.infra.config",
        )
        val packages = packageNames.associateWith { packageName ->
            JvmPackageSymbol(
                id = "package:$packageName",
                qualifiedName = packageName,
                simpleName = packageName.substringAfterLast('.'),
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        fun cls(packageName: String, simpleName: String, stereotype: JvmStereotype = JvmStereotype.UNKNOWN): JvmClassSymbol {
            val qualifiedName = "$packageName.$simpleName"
            return JvmClassSymbol(
                id = "class:$qualifiedName",
                qualifiedName = qualifiedName,
                simpleName = simpleName,
                packageName = packageName,
                moduleName = module.qualifiedName,
                kind = JvmClassKind.CLASS,
                stereotype = stereotype,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        val controller = cls("com.example.order.controller", "OrderController", JvmStereotype.CONTROLLER)
        val service = cls("com.example.order.service", "OrderService", JvmStereotype.SERVICE)
        val domain = cls("com.example.order.domain", "Order", JvmStereotype.UNKNOWN)
        val repository = cls("com.example.order.repository", "OrderRepository", JvmStereotype.REPOSITORY)
        val config = cls("com.example.order.infra.config", "OrderPersistenceConfig", JvmStereotype.CONFIGURATION)
        val classes = listOf(controller, service, domain, repository, config)
        return ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                modulesByName = mapOf(module.qualifiedName to module),
                packagesByName = packages,
                classesByQualifiedName = classes.associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(
                listOf(
                    JvmRelation(
                        id = "rel:controller->service",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = controller.id,
                        toSymbolId = service.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:service->domain",
                        kind = JvmRelationKind.USES_TYPE,
                        fromSymbolId = service.id,
                        toSymbolId = domain.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:service->repository",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = service.id,
                        toSymbolId = repository.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:service->config",
                        kind = JvmRelationKind.USES_TYPE,
                        fromSymbolId = service.id,
                        toSymbolId = config.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                ),
            ),
        )
    }

    private fun assertVisibleNodesHavePresentationMetadata(nodes: List<GraphNode>) {
        assertTrue(nodes.all { node -> node.metadata["presentation.role"] != null })
        assertTrue(nodes.all { node -> node.metadata["presentation.laneId"] != null })
        assertTrue(nodes.all { node -> node.metadata["presentation.priority"] != null })
        assertTrue(nodes.all { node -> node.metadata["presentation.compact"] != null })
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

    private fun relationSemanticArchitectureIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val packages = listOf(
            JvmPackageSymbol(
                id = "package:orders.api",
                qualifiedName = "orders.api",
                simpleName = "api",
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            ),
            JvmPackageSymbol(
                id = "package:billing.service",
                qualifiedName = "billing.service",
                simpleName = "service",
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            ),
            JvmPackageSymbol(
                id = "package:catalog.domain",
                qualifiedName = "catalog.domain",
                simpleName = "domain",
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            ),
        )
        val api = JvmClassSymbol(
            id = "class:orders.api.OrderController",
            qualifiedName = "orders.api.OrderController",
            simpleName = "OrderController",
            packageName = "orders.api",
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            stereotype = JvmStereotype.CONTROLLER,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val service = JvmClassSymbol(
            id = "class:billing.service.OrderService",
            qualifiedName = "billing.service.OrderService",
            simpleName = "OrderService",
            packageName = "billing.service",
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            stereotype = JvmStereotype.SERVICE,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val domain = JvmClassSymbol(
            id = "class:catalog.domain.Order",
            qualifiedName = "catalog.domain.Order",
            simpleName = "Order",
            packageName = "catalog.domain",
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val resource = JvmResourceSymbol(
            id = "resource:src/main/resources/application.yml",
            path = "src/main/resources/application.yml",
            kind = JvmResourceKind.YAML,
            source = null,
            origin = SourceOrigin.CONTENT_ROOT,
        )
        val symbolIndex = JvmSymbolIndex(
            modulesByName = mapOf(module.qualifiedName to module),
            packagesByName = packages.associateBy(JvmPackageSymbol::qualifiedName),
            classesByQualifiedName = listOf(api, service, domain).associateBy(JvmClassSymbol::qualifiedName),
            resourcesByPath = mapOf(resource.path to resource),
        )
        val relations = listOf(
            JvmRelation(
                id = "rel:${module.id}->${packages[0].id}",
                kind = JvmRelationKind.MODULE_CONTAINS_PACKAGE,
                fromSymbolId = module.id,
                toSymbolId = packages[0].id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
            ),
            JvmRelation(
                id = "rel:${module.id}->${packages[1].id}",
                kind = JvmRelationKind.MODULE_CONTAINS_PACKAGE,
                fromSymbolId = module.id,
                toSymbolId = packages[1].id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
            ),
            JvmRelation(
                id = "rel:${module.id}->${packages[2].id}",
                kind = JvmRelationKind.MODULE_CONTAINS_PACKAGE,
                fromSymbolId = module.id,
                toSymbolId = packages[2].id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
            ),
            JvmRelation(
                id = "rel:${packages[0].id}->${api.id}",
                kind = JvmRelationKind.PACKAGE_CONTAINS_CLASS,
                fromSymbolId = packages[0].id,
                toSymbolId = api.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
            ),
            JvmRelation(
                id = "rel:${packages[1].id}->${service.id}",
                kind = JvmRelationKind.PACKAGE_CONTAINS_CLASS,
                fromSymbolId = packages[1].id,
                toSymbolId = service.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
            ),
            JvmRelation(
                id = "rel:${packages[2].id}->${domain.id}",
                kind = JvmRelationKind.PACKAGE_CONTAINS_CLASS,
                fromSymbolId = packages[2].id,
                toSymbolId = domain.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
            ),
            JvmRelation(
                id = "rel:api-call-service",
                kind = JvmRelationKind.CALLS,
                fromSymbolId = api.id,
                toSymbolId = service.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:service-uses-domain",
                kind = JvmRelationKind.USES_TYPE,
                fromSymbolId = service.id,
                toSymbolId = domain.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:resource-binds-service",
                kind = JvmRelationKind.RESOURCE_BINDS,
                fromSymbolId = resource.id,
                toSymbolId = service.id,
                confidence = JvmRelationConfidence.RULE_INFERRED,
                source = JvmRelationSource.RESOURCE_FILE,
            ),
        )
        return ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(relations),
        )
    }

    private fun kafkaLikeArchitectureIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:kafka",
            qualifiedName = "kafka",
            simpleName = "kafka",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val packageNames = listOf(
            "kafka.docker",
            "kafka.examples",
            "org.apache.kafka.server",
            "org.apache.kafka.server.config",
            "org.apache.kafka.coordinator.group",
            "org.apache.kafka.common",
            "org.apache.kafka.common.test",
            "org.apache.kafka.metadata",
            "org.apache.kafka.tools",
            "kafka.server",
        )
        val packages = packageNames.associateWith { packageName ->
            JvmPackageSymbol(
                id = "package:$packageName",
                qualifiedName = packageName,
                simpleName = packageName.substringAfterLast('.'),
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        fun cls(packageName: String, simpleName: String, stereotype: JvmStereotype = JvmStereotype.UNKNOWN): JvmClassSymbol =
            JvmClassSymbol(
                id = "class:$packageName.$simpleName",
                qualifiedName = "$packageName.$simpleName",
                simpleName = simpleName,
                packageName = packageName,
                moduleName = module.qualifiedName,
                kind = JvmClassKind.CLASS,
                stereotype = stereotype,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        val classes = listOf(
            cls("org.apache.kafka.server", "BrokerServer", JvmStereotype.SERVICE),
            cls("org.apache.kafka.server", "KafkaRequestHandler"),
            cls("org.apache.kafka.server", "ServerStartup"),
            cls("org.apache.kafka.server.config", "ServerConfig", JvmStereotype.CONFIGURATION),
            cls("org.apache.kafka.coordinator.group", "GroupCoordinator", JvmStereotype.SERVICE),
            cls("org.apache.kafka.coordinator.group", "GroupMetadataManager"),
            cls("org.apache.kafka.common", "Uuid"),
            cls("org.apache.kafka.common", "KafkaFuture"),
            cls("org.apache.kafka.common.test", "ClusterTestExtensions"),
            cls("org.apache.kafka.common.test", "TestUtils"),
            cls("org.apache.kafka.metadata", "MetadataManager"),
            cls("org.apache.kafka.metadata", "TopicMetadata"),
            cls("org.apache.kafka.tools", "KafkaTool", JvmStereotype.CONTROLLER),
            cls("org.apache.kafka.tools", "TopicCommand"),
            cls("kafka.server", "KafkaServer", JvmStereotype.SERVICE),
            cls("kafka.server", "ReplicaManager"),
            cls("kafka.docker", "DockerImage"),
            cls("kafka.docker", "DockerSandbox"),
            cls("kafka.examples", "ProducerExample"),
            cls("kafka.examples", "ConsumerExample"),
        )
        val symbolIndex = JvmSymbolIndex(
            modulesByName = mapOf(module.qualifiedName to module),
            packagesByName = packages,
            classesByQualifiedName = classes.associateBy(JvmClassSymbol::qualifiedName),
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
        val classRelations = classes.map { item ->
            JvmRelation(
                id = "rel:package:${item.packageName}->${item.id}",
                kind = JvmRelationKind.PACKAGE_CONTAINS_CLASS,
                fromSymbolId = packages.getValue(item.packageName).id,
                toSymbolId = item.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
            )
        }
        val byName = classes.associateBy(JvmClassSymbol::qualifiedName)
        val semanticRelations = listOf(
            JvmRelation(
                id = "rel:tools-call-server",
                kind = JvmRelationKind.CALLS,
                fromSymbolId = byName.getValue("org.apache.kafka.tools.KafkaTool").id,
                toSymbolId = byName.getValue("org.apache.kafka.server.BrokerServer").id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:tools-call-kafka-server",
                kind = JvmRelationKind.CALLS,
                fromSymbolId = byName.getValue("org.apache.kafka.tools.TopicCommand").id,
                toSymbolId = byName.getValue("kafka.server.KafkaServer").id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:server-call-group",
                kind = JvmRelationKind.CALLS,
                fromSymbolId = byName.getValue("org.apache.kafka.server.BrokerServer").id,
                toSymbolId = byName.getValue("org.apache.kafka.coordinator.group.GroupCoordinator").id,
                confidence = JvmRelationConfidence.PROVEN,
                count = 2,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:server-uses-metadata",
                kind = JvmRelationKind.USES_TYPE,
                fromSymbolId = byName.getValue("org.apache.kafka.server.KafkaRequestHandler").id,
                toSymbolId = byName.getValue("org.apache.kafka.metadata.MetadataManager").id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:group-uses-metadata",
                kind = JvmRelationKind.USES_TYPE,
                fromSymbolId = byName.getValue("org.apache.kafka.coordinator.group.GroupMetadataManager").id,
                toSymbolId = byName.getValue("org.apache.kafka.metadata.TopicMetadata").id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:kafka-server-call-server",
                kind = JvmRelationKind.CALLS,
                fromSymbolId = byName.getValue("kafka.server.KafkaServer").id,
                toSymbolId = byName.getValue("org.apache.kafka.server.BrokerServer").id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:docker-uses-common",
                kind = JvmRelationKind.USES_TYPE,
                fromSymbolId = byName.getValue("kafka.docker.DockerSandbox").id,
                toSymbolId = byName.getValue("org.apache.kafka.common.Uuid").id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:examples-call-server",
                kind = JvmRelationKind.CALLS,
                fromSymbolId = byName.getValue("kafka.examples.ProducerExample").id,
                toSymbolId = byName.getValue("org.apache.kafka.server.BrokerServer").id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
            JvmRelation(
                id = "rel:common-test-uses-server",
                kind = JvmRelationKind.USES_TYPE,
                fromSymbolId = byName.getValue("org.apache.kafka.common.test.TestUtils").id,
                toSymbolId = byName.getValue("org.apache.kafka.server.KafkaRequestHandler").id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
            ),
        )
        return ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(packageRelations + classRelations + semanticRelations),
        )
    }

    private fun serverNamedArchitectureIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:acme",
            qualifiedName = "acme",
            simpleName = "acme",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val packageNames = listOf(
            "com.acme.server",
            "com.acme.client",
        )
        val packages = packageNames.associateWith { packageName ->
            JvmPackageSymbol(
                id = "package:$packageName",
                qualifiedName = packageName,
                simpleName = packageName.substringAfterLast('.'),
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        fun cls(packageName: String, simpleName: String): JvmClassSymbol =
            JvmClassSymbol(
                id = "class:$packageName.$simpleName",
                qualifiedName = "$packageName.$simpleName",
                simpleName = simpleName,
                packageName = packageName,
                moduleName = module.qualifiedName,
                kind = JvmClassKind.CLASS,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        val server = cls("com.acme.server", "ServerRuntime")
        val client = cls("com.acme.client", "ClientFacade")
        val classes = listOf(server, client)
        val symbolIndex = JvmSymbolIndex(
            modulesByName = mapOf(module.qualifiedName to module),
            packagesByName = packages,
            classesByQualifiedName = classes.associateBy(JvmClassSymbol::qualifiedName),
        )
        return ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(
                listOf(
                    JvmRelation(
                        id = "rel:server-client",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = server.id,
                        toSymbolId = client.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                ),
            ),
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
        val jdkType = JvmClassSymbol(
            id = "class:java.time.Clock",
            qualifiedName = "java.time.Clock",
            simpleName = "Clock",
            packageName = "java.time",
            moduleName = "jdk",
            kind = JvmClassKind.CLASS,
            external = true,
            jdk = true,
            source = null,
            origin = SourceOrigin.JDK_CLASS,
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
            classesByQualifiedName = listOf(contract, provider, runner, reflectionTarget, externalClient, jdkType).associateBy(JvmClassSymbol::qualifiedName),
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
                metadata = ClassDiagramRelationExtractor.metadataFor(
                    role = ClassDiagramRelationRole.IMPLEMENTS,
                    evidence = "implements ${contract.qualifiedName}",
                    memberName = provider.simpleName,
                    usedInBody = false,
                ),
            ),
            JvmRelation(
                id = "rel:runner->contract",
                kind = JvmRelationKind.USES_TYPE,
                fromSymbolId = runner.id,
                toSymbolId = contract.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
                metadata = ClassDiagramRelationExtractor.metadataFor(
                    role = ClassDiagramRelationRole.FIELD,
                    evidence = "field provider: ${contract.qualifiedName}",
                    memberName = field.simpleName,
                    usedInBody = false,
                    heldByField = true,
                ),
            ),
            JvmRelation(
                id = "rel:runner-call-provider",
                kind = JvmRelationKind.CALLS,
                fromSymbolId = method.id,
                toSymbolId = provider.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
                metadata = ClassDiagramRelationExtractor.metadataFor(
                    role = ClassDiagramRelationRole.METHOD_CALL,
                    evidence = "calls ${provider.qualifiedName}",
                    memberName = "run",
                    usedInBody = true,
                ),
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
                metadata = ClassDiagramRelationExtractor.metadataFor(
                    role = ClassDiagramRelationRole.METHOD_CALL,
                    evidence = "calls ${externalClient.qualifiedName}",
                    memberName = "run",
                    usedInBody = true,
                ),
            ),
            JvmRelation(
                id = "rel:runner-uses-jdk",
                kind = JvmRelationKind.USES_TYPE,
                fromSymbolId = runner.id,
                toSymbolId = jdkType.id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
                metadata = ClassDiagramRelationExtractor.metadataFor(
                    role = ClassDiagramRelationRole.METHOD_RETURN,
                    evidence = "return run: ${jdkType.qualifiedName}",
                    memberName = "run",
                    usedInBody = false,
                ),
            ),
        )
        return ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(relations),
        )
    }

    private fun mixedBoundaryIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val packages = listOf(
            "com.example.order.controller",
            "com.example.review.git",
        ).associateWith { packageName ->
            JvmPackageSymbol(
                id = "package:$packageName",
                qualifiedName = packageName,
                simpleName = packageName.substringAfterLast('.'),
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        val orderController = JvmClassSymbol(
            id = "class:com.example.order.controller.OrderController",
            qualifiedName = "com.example.order.controller.OrderController",
            simpleName = "OrderController",
            packageName = "com.example.order.controller",
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val technicalPackageClass = JvmClassSymbol(
            id = "class:com.example.review.git.GitMapper",
            qualifiedName = "com.example.review.git.GitMapper",
            simpleName = "GitMapper",
            packageName = "com.example.review.git",
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val intellijClass = JvmClassSymbol(
            id = "class:com.intellij.openapi.application.ApplicationManager",
            qualifiedName = "com.intellij.openapi.application.ApplicationManager",
            simpleName = "ApplicationManager",
            packageName = "com.intellij.openapi.application",
            moduleName = "library:intellij-platform.jar",
            kind = JvmClassKind.CLASS,
            external = true,
            library = true,
            source = null,
            origin = SourceOrigin.LIBRARY_CLASS_JAR,
        )
        val jdkClass = JvmClassSymbol(
            id = "class:java.util.logging.Logger",
            qualifiedName = "java.util.logging.Logger",
            simpleName = "Logger",
            packageName = "java.util.logging",
            moduleName = "jdk",
            kind = JvmClassKind.CLASS,
            external = true,
            jdk = true,
            source = null,
            origin = SourceOrigin.JDK_CLASS,
        )
        val symbols = listOf(orderController, technicalPackageClass, intellijClass, jdkClass)
        return ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                modulesByName = mapOf(module.qualifiedName to module),
                packagesByName = packages,
                classesByQualifiedName = symbols.associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(
                listOf(
                    JvmRelation(
                        id = "rel:order->library",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = orderController.id,
                        toSymbolId = intellijClass.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:order->jdk",
                        kind = JvmRelationKind.USES_TYPE,
                        fromSymbolId = orderController.id,
                        toSymbolId = jdkClass.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                ),
            ),
        )
    }

    private fun packageBoundaryIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val pkg = JvmPackageSymbol(
            id = "package:com.example.order",
            qualifiedName = "com.example.order",
            simpleName = "order",
            moduleName = module.qualifiedName,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val orderService = JvmClassSymbol(
            id = "class:com.example.order.OrderService",
            qualifiedName = "com.example.order.OrderService",
            simpleName = "OrderService",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val externalClient = JvmClassSymbol(
            id = "class:com.vendor.Client",
            qualifiedName = "com.vendor.Client",
            simpleName = "Client",
            packageName = "com.vendor",
            moduleName = "library:vendor.jar",
            kind = JvmClassKind.CLASS,
            external = true,
            library = true,
            source = null,
            origin = SourceOrigin.LIBRARY_CLASS_JAR,
        )
        val clock = JvmClassSymbol(
            id = "class:java.time.Clock",
            qualifiedName = "java.time.Clock",
            simpleName = "Clock",
            packageName = "java.time",
            moduleName = "jdk",
            kind = JvmClassKind.CLASS,
            external = true,
            jdk = true,
            source = null,
            origin = SourceOrigin.JDK_CLASS,
        )
        val resource = JvmResourceSymbol(
            id = "resource:application.yml",
            path = "application.yml",
            kind = JvmResourceKind.YAML,
            source = null,
            origin = SourceOrigin.CONTENT_ROOT,
        )
        return ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                modulesByName = mapOf(module.qualifiedName to module),
                packagesByName = mapOf(pkg.qualifiedName to pkg),
                classesByQualifiedName = listOf(orderService, externalClient, clock).associateBy(JvmClassSymbol::qualifiedName),
                resourcesByPath = mapOf(resource.path to resource),
            ),
            relationIndex = JvmRelationIndex(
                listOf(
                    JvmRelation(
                        id = "rel:order->external",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = orderService.id,
                        toSymbolId = externalClient.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:order->jdk",
                        kind = JvmRelationKind.USES_TYPE,
                        fromSymbolId = orderService.id,
                        toSymbolId = clock.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:resource->order",
                        kind = JvmRelationKind.RESOURCE_BINDS,
                        fromSymbolId = resource.id,
                        toSymbolId = orderService.id,
                        confidence = JvmRelationConfidence.RULE_INFERRED,
                        source = JvmRelationSource.RESOURCE_FILE,
                    ),
                ),
            ),
        )
    }

    private fun architectureIndexWithDependencyDocumentationResources(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val pkg = JvmPackageSymbol(
            id = "package:com.example.order",
            qualifiedName = "com.example.order",
            simpleName = "order",
            moduleName = module.qualifiedName,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val orderService = JvmClassSymbol(
            id = "class:com.example.order.OrderService",
            qualifiedName = "com.example.order.OrderService",
            simpleName = "OrderService",
            packageName = pkg.qualifiedName,
            moduleName = module.qualifiedName,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val resources = listOf(
            JvmResourceSymbol(
                id = "resource:src/main/resources/application.yml",
                path = "src/main/resources/application.yml",
                kind = JvmResourceKind.YAML,
                source = null,
                origin = SourceOrigin.CONTENT_ROOT,
            ),
            JvmResourceSymbol(
                id = "resource:web/node_modules/pkg/CHANGELOG.md",
                path = "web/node_modules/pkg/CHANGELOG.md",
                kind = JvmResourceKind.MARKDOWN,
                source = null,
                origin = SourceOrigin.CONTENT_ROOT,
            ),
            JvmResourceSymbol(
                id = "resource:web/node_modules/pkg/LICENSE.md",
                path = "web/node_modules/pkg/LICENSE.md",
                kind = JvmResourceKind.MARKDOWN,
                source = null,
                origin = SourceOrigin.CONTENT_ROOT,
            ),
        )
        return ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                modulesByName = mapOf(module.qualifiedName to module),
                packagesByName = mapOf(pkg.qualifiedName to pkg),
                classesByQualifiedName = mapOf(orderService.qualifiedName to orderService),
                resourcesByPath = resources.associateBy(JvmResourceSymbol::path),
            ),
            relationIndex = JvmRelationIndex(emptyList()),
        )
    }

    private fun evidencedServiceBoundaryIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val packageNames = listOf(
            "com.example.order.controller",
            "com.example.order.service",
            "com.example.order.repository",
            "com.example.shared.config",
        )
        val packages = packageNames.associateWith { packageName ->
            JvmPackageSymbol(
                id = "package:$packageName",
                qualifiedName = packageName,
                simpleName = packageName.substringAfterLast('.'),
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        fun cls(packageName: String, simpleName: String): JvmClassSymbol {
            val qualifiedName = "$packageName.$simpleName"
            return JvmClassSymbol(
                id = "class:$qualifiedName",
                qualifiedName = qualifiedName,
                simpleName = simpleName,
                packageName = packageName,
                moduleName = module.qualifiedName,
                kind = JvmClassKind.CLASS,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        val orderController = cls("com.example.order.controller", "OrderController")
        val orderService = cls("com.example.order.service", "OrderService")
        val orderRepository = cls("com.example.order.repository", "OrderRepository")
        val sharedConfig = cls("com.example.shared.config", "SharedConfig")
        return ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                modulesByName = mapOf(module.qualifiedName to module),
                packagesByName = packages,
                classesByQualifiedName = listOf(orderController, orderService, orderRepository, sharedConfig)
                    .associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(
                listOf(
                    JvmRelation(
                        id = "rel:controller->service",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = orderController.id,
                        toSymbolId = orderService.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:service->repository",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = orderService.id,
                        toSymbolId = orderRepository.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:service->config",
                        kind = JvmRelationKind.USES_TYPE,
                        fromSymbolId = orderService.id,
                        toSymbolId = sharedConfig.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                ),
            ),
        )
    }

    private fun organizationRootLayerIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:app",
            qualifiedName = "app",
            simpleName = "app",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val packageNames = listOf(
            "com.example.app.controller",
            "com.example.app.service",
            "com.example.app.repository",
        )
        val packages = packageNames.associateWith { packageName ->
            JvmPackageSymbol(
                id = "package:$packageName",
                qualifiedName = packageName,
                simpleName = packageName.substringAfterLast('.'),
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        fun cls(packageName: String, simpleName: String): JvmClassSymbol {
            val qualifiedName = "$packageName.$simpleName"
            return JvmClassSymbol(
                id = "class:$qualifiedName",
                qualifiedName = qualifiedName,
                simpleName = simpleName,
                packageName = packageName,
                moduleName = module.qualifiedName,
                kind = JvmClassKind.CLASS,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        val controller = cls("com.example.app.controller", "AppController")
        val service = cls("com.example.app.service", "AppService")
        val repository = cls("com.example.app.repository", "AppRepository")
        return ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                modulesByName = mapOf(module.qualifiedName to module),
                packagesByName = packages,
                classesByQualifiedName = listOf(controller, service, repository).associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(
                listOf(
                    JvmRelation(
                        id = "rel:controller->service",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = controller.id,
                        toSymbolId = service.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:service->repository",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = service.id,
                        toSymbolId = repository.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                ),
            ),
        )
    }

    private fun multiRootProductNamespaceIndex(): ArchitectureGraphIndex {
        val module = JvmModuleSymbol(
            id = "module:kafka",
            qualifiedName = "kafka",
            simpleName = "kafka",
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val packageNames = listOf(
            "org.apache.kafka.controller",
            "org.apache.kafka.server",
            "org.apache.kafka.metadata",
            "kafka.server",
        )
        val packages = packageNames.associateWith { packageName ->
            JvmPackageSymbol(
                id = "package:$packageName",
                qualifiedName = packageName,
                simpleName = packageName.substringAfterLast('.'),
                moduleName = module.qualifiedName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        fun cls(packageName: String, simpleName: String, stereotype: JvmStereotype = JvmStereotype.UNKNOWN): JvmClassSymbol {
            val qualifiedName = "$packageName.$simpleName"
            return JvmClassSymbol(
                id = "class:$qualifiedName",
                qualifiedName = qualifiedName,
                simpleName = simpleName,
                packageName = packageName,
                moduleName = module.qualifiedName,
                kind = JvmClassKind.CLASS,
                stereotype = stereotype,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        val controller = cls("org.apache.kafka.controller", "ControllerServer", JvmStereotype.CONTROLLER)
        val broker = cls("org.apache.kafka.server", "BrokerServer", JvmStereotype.SERVICE)
        val metadata = cls("org.apache.kafka.metadata", "MetadataManager", JvmStereotype.REPOSITORY)
        val scalaServer = cls("kafka.server", "KafkaServer", JvmStereotype.SERVICE)
        return ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                modulesByName = mapOf(module.qualifiedName to module),
                packagesByName = packages,
                classesByQualifiedName = listOf(controller, broker, metadata, scalaServer).associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(
                listOf(
                    JvmRelation(
                        id = "rel:controller->broker",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = controller.id,
                        toSymbolId = broker.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:broker->metadata",
                        kind = JvmRelationKind.USES_TYPE,
                        fromSymbolId = broker.id,
                        toSymbolId = metadata.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                    JvmRelation(
                        id = "rel:scala-server->broker",
                        kind = JvmRelationKind.CALLS,
                        fromSymbolId = scalaServer.id,
                        toSymbolId = broker.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                ),
            ),
        )
    }
}
