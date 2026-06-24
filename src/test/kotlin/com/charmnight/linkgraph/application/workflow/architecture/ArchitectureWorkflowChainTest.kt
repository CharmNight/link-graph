package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.indexed.IndexedClassUsageOptions
import com.charmnight.linkgraph.application.indexed.IndexedGraphRefreshPolicy
import com.charmnight.linkgraph.application.indexed.IndexedGraphRelationDetail
import com.charmnight.linkgraph.application.indexed.requestArchitectureGraphRequest
import com.charmnight.linkgraph.application.indexed.requestClassDiagramRequest
import com.charmnight.linkgraph.application.indexed.requestClassUsageOverlayRequest
import com.charmnight.linkgraph.application.model.AsyncRequestPhase
import com.charmnight.linkgraph.architecture.ArchitectureGraph
import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ClassDiagramFastIndex
import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexSliceFragment
import com.charmnight.linkgraph.architecture.memory.ProjectFileFingerprint
import com.charmnight.linkgraph.architecture.memory.ProjectSlice
import com.charmnight.linkgraph.architecture.memory.ProjectSliceKind
import com.charmnight.linkgraph.architecture.architectureIndexService
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeRole
import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderFile
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderIndex
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.effectiveTypeReferences
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.source.SourceOrigin
import com.charmnight.linkgraph.testing.assertArchitectureGraphViewDataContract
import com.charmnight.linkgraph.testing.assertClassDiagramViewDataContract
import com.charmnight.linkgraph.testing.testSnapshot
import com.charmnight.linkgraph.ui.toWorkflowEditorSnapshot
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class ArchitectureWorkflowChainTest : BasePlatformTestCase() {
    fun testArchitectureAndClassDiagramWorkflowsBuildSharedIndexFromProjectSources() {
        addArchitectureFixture()
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val indexSupport = ArchitectureIndexWorkflowSupport(project)
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)

        val architectureRequest = requestArchitectureGraphRequest()
        ArchitectureGraphWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(architectureRequest)

        val architectureEvent = waitForEvent<GraphEditorApplicationEvent.ArchitectureGraphLoaded>(events)
        val architectureView = architectureEvent.view
        assertArchitectureGraphViewDataContract(architectureView, "workflow.architecture")
        assertTrue(events.filterIsInstance<GraphEditorApplicationEvent.IndexedGraphRequestStarted>().any { event ->
            event.view.name == "ARCHITECTURE" && event.requestState.phase == AsyncRequestPhase.RUNNING
        })
        assertEquals(AsyncRequestPhase.SUCCEEDED, architectureEvent.requestState.phase)
        assertTrue(events.none { event -> event is GraphEditorApplicationEvent.Feedback })
        assertTrue(architectureView.fullGraph.nodes.any { node -> node.type == NodeType.COMPONENT })
        assertTrue(architectureView.visibleGraph.nodes.none { node -> node.type == NodeType.MODULE })
        assertTrue(architectureView.visibleGraph.nodes.none { node -> node.type == NodeType.PACKAGE })
        assertTrue(architectureView.summary.inventoryOnlyNodeCount >= 0)
        assertTrue(
            architectureView.fullGraph.edges.any { edge ->
                edge.metadata["architecture.aggregate.level"] == "OVERVIEW" &&
                    edge.metadata["jvm.relation.kind"] in setOf(
                        JvmRelationKind.CALLS.name,
                        JvmRelationKind.USES_TYPE.name,
                        JvmRelationKind.RESOURCE_BINDS.name,
                    )
            },
            "项目结构图必须展示从索引聚合出的真实项目关系，不能用 synthetic 归属边冒充关系。",
        )
        assertTrue(
            architectureView.fullGraph.edges.none { edge ->
                edge.metadata["architecture.relation.kind"] == "PROJECT_STRUCTURE_PARENT" ||
                    edge.metadata["architecture.synthetic"] == "true" ||
                    edge.metadata["jvm.relation.kind"] == null
            },
            "项目结构图不能继续输出 synthetic 归属边。",
        )
        assertTrue(
            architectureView.fullGraph.edges.none { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.SPI_PROVIDES.name
            },
            "项目结构图不应把 SPI 运行时集成关系混入默认主视图。",
        )

        val sharedIndex = assertNotNull(indexSupport.currentIndex(), "架构图 workflow 应留下共享 ArchitectureGraphIndex。")
        assertTrue(
            sharedIndex.graph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.SPI_PROVIDES.name
            },
            "项目结构概览索引仍需保留可由符号和资源清单证明的 SPI provider 聚合关系。",
        )
        assertTrue(sharedIndex.symbolIndex.findClass("com.example.spi.service.TaskProvider") != null)
        assertTrue(sharedIndex.symbolIndex.findField("com.example.service.TaskRunner.provider") != null)
        assertTrue(sharedIndex.relationIndex.byKind(JvmRelationKind.SERVICE_LOADER_LOADS).isEmpty())
        val completeIndex = project.architectureIndexRuntime().index(indexSupport.resolutionBudget(architectureRequest))
        assertTrue(completeIndex.relationIndex.byKind(JvmRelationKind.SERVICE_LOADER_LOADS).isNotEmpty())
        project.architectureIndexRuntime().symbolQuery().summary()
        project.architectureIndexRuntime().reviewQuery()
        assertEquals(completeIndex, project.architectureIndexRuntime().currentIndex())
        assertTrue(
            project.architectureIndexRuntime()
                .sourceQuery()
                .readClassByQualifiedName("com.example.spi.service.TaskProvider")
                ?.origin == SourceOrigin.PROJECT_SOURCE,
        )

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(requestClassDiagramRequest("arch:component:com.example.service"))

        val classDiagramEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "COMPLETE" },
        )
        val classDiagramView = classDiagramEvent.view
        assertClassDiagramViewDataContract(classDiagramView, "workflow.classDiagram")
        assertTrue(events.filterIsInstance<GraphEditorApplicationEvent.IndexedGraphRequestStarted>().any { event ->
            event.view.name == "CLASS_DIAGRAM" && event.requestState.phase == AsyncRequestPhase.RUNNING
        })
        assertEquals(AsyncRequestPhase.SUCCEEDED, classDiagramEvent.requestState.phase)
        assertTrue(events.none { event -> event is GraphEditorApplicationEvent.Feedback })
        assertTrue(classDiagramView.summary.fieldCount >= 1)
        assertTrue(classDiagramView.visibleGraph.nodes.any { node -> node.signature == "com.example.spi.service.TaskProvider" })
        assertTrue(classDiagramView.visibleGraph.nodes.any { node -> node.signature == "com.example.service.TaskRunner" })
        assertTrue(
            classDiagramView.visibleGraph.edges.any { edge ->
                edge.metadata["uml.relation.kind"] == "REALIZATION" &&
                    edge.metadata["jvm.relation.kind"] == JvmRelationKind.IMPLEMENTS.name
            },
            "类图必须从同一个 ArchitectureGraphIndex 投影出 UML 类型结构关系。",
        )
        assertTrue(
            classDiagramView.visibleGraph.edges.none { edge ->
                edge.metadata["jvm.relation.kind"] in setOf(
                    JvmRelationKind.SERVICE_LOADER_LOADS.name,
                    JvmRelationKind.SPI_PROVIDES.name,
                )
            },
            "类图不应混入 ServiceLoader/SPI 运行时集成关系。",
        )
    }

    fun testColdClassDiagramPublishesStructurePreviewWithoutAutoCompleteRelations() {
        addArchitectureFixture()
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val indexSupport = ArchitectureIndexWorkflowSupport(project)
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(requestClassDiagramRequest("arch:component:com.example.service"))

        val structureEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "STRUCTURE_ONLY" },
        )
        val structureView = structureEvent.view
        assertClassDiagramViewDataContract(structureView, "workflow.classDiagram.structure")
        assertEquals(AsyncRequestPhase.SUCCEEDED, structureEvent.requestState.phase)
        assertTrue(structureView.visibleGraph.nodes.isNotEmpty(), "结构预览不能继续让前端停留在空画布。")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        Thread.sleep(300)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(
            events.filterIsInstance<GraphEditorApplicationEvent.ClassDiagramLoaded>()
                .none { event -> event.view.summary.relationCompleteness == "COMPLETE" },
            "普通冷启动类图请求不得自动触发全项目完整关系补齐。",
        )
    }

    fun testExplicitCompleteClassDiagramRequestPublishesStructurePreviewBeforeCompleteRelations() {
        addArchitectureFixture()
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val indexSupport = ArchitectureIndexWorkflowSupport(project)
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(
            requestClassDiagramRequest("arch:component:com.example.service")
                .copy(relationDetail = IndexedGraphRelationDetail.COMPLETE),
        )

        val structureEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "STRUCTURE_ONLY" },
        )
        val completeEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "COMPLETE" },
        )
        val classDiagramEvents = events.filterIsInstance<GraphEditorApplicationEvent.ClassDiagramLoaded>()
        val structureIndex = classDiagramEvents.indexOfFirst { event -> event === structureEvent }
        val completeIndex = classDiagramEvents.indexOfFirst { event -> event === completeEvent }
        assertTrue(
            structureIndex in 0 until completeIndex,
            "显式 COMPLETE 类图请求必须先同步 STRUCTURE_ONLY 结构预览，再补齐 COMPLETE 关系。",
        )
        val completeView = completeEvent.view
        assertClassDiagramViewDataContract(completeView, "workflow.classDiagram.complete")
        assertEquals(AsyncRequestPhase.SUCCEEDED, completeEvent.requestState.phase)
        assertTrue(
            completeView.visibleGraph.edges.none { edge ->
                edge.metadata["jvm.relation.kind"] in setOf(
                    JvmRelationKind.SERVICE_LOADER_LOADS.name,
                    JvmRelationKind.SPI_PROVIDES.name,
                )
            },
            "完整类图也只展示 UML 类结构，不补入运行时集成关系。",
        )
    }

    fun testClassDiagramUsageRequestProjectsReferenceSearchResultsIntoInteractiveView() {
        addClassUsageFixture()
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val indexSupport = ArchitectureIndexWorkflowSupport(project)
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)
        val targetNodeId = stableJvmId("class", "com.example.usage.OrderService")

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(
            requestClassDiagramRequest(targetNodeId).copy(
                usage = IndexedClassUsageOptions(
                    enabled = true,
                    targetNodeId = targetNodeId,
                    maxUsageGroups = 10,
                    maxUsageEntries = 20,
                    includeImports = false,
                ),
            ),
        )

        val usageEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event ->
                event.view.usage != null
            },
        )
        val view = usageEvent.view
        assertClassDiagramViewDataContract(view, "workflow.classDiagram.usage")
        assertEquals(targetNodeId, view.usage?.target?.nodeId)
        assertEquals("com.example.usage.OrderService", view.usage?.target?.qualifiedName)
        assertTrue(
            view.usage?.groups?.any { group -> group.qualifiedName == "com.example.usage.OrderController" } == true,
            view.usage?.groups.orEmpty().joinToString("\n") { group -> "${group.qualifiedName}: ${group.usages.map { it.kind }}" },
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.toNodeId == targetNodeId &&
                    edge.metadata["classDiagram.relation.role"] == "CLASS_USAGE"
            },
            "使用处结果必须以 CLASS_USAGE 边呈现到 usage 视图。",
        )
        val structuralRoles = setOf("FIELD", "METHOD_RETURN", "METHOD_PARAMETER", "METHOD_CALL", "CONSTRUCTOR_PARAMETER", "LOCAL_TYPE", "THROWS", "EXTENDS", "IMPLEMENTS")
        assertTrue(
            view.visibleGraph.edges.none { edge ->
                edge.metadata["classDiagram.relation.role"] in structuralRoles
            },
            "usage 视图必须切换掉结构关系边，不能与 CLASS_USAGE 并存。",
        )
        val targetNode = assertNotNull(
            view.visibleGraph.nodes.firstOrNull { node -> node.id == targetNodeId },
            "usage 视图必须保留目标类节点。",
        )
        assertEquals("ANCHOR", targetNode.metadata["presentation.role"])
        assertTrue(
            view.usage?.groups.orEmpty()
                .flatMap { group -> group.usages }
                .none { usage -> usage.kind == com.charmnight.linkgraph.usage.ClassUsageKind.IMPORT },
            "默认 usage 请求不应把 import 当作图上使用处。",
        )
    }

    fun testClassDiagramUsageRequestDoesNotTriggerCompleteRelationBuild() {
        addClassUsageFixture()
        val targetNodeId = stableJvmId("class", "com.example.usage.OrderService")
        val indexSupport = CountingCompleteClassDiagramIndexSupport(classUsageStructureIndex())
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(
            requestClassUsageOverlayRequest(targetNodeId).copy(
                usage = IndexedClassUsageOptions(
                    enabled = true,
                    targetNodeId = targetNodeId,
                    maxUsageGroups = 10,
                    maxUsageEntries = 20,
                    includeImports = false,
                ),
            ),
        )

        val usageEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event ->
                event.view.summary.relationCompleteness == "STRUCTURE_ONLY" &&
                    event.view.usage?.target?.nodeId == targetNodeId
            },
        )
        assertClassDiagramViewDataContract(usageEvent.view, "workflow.classDiagram.usage.structureOnly")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        Thread.sleep(250)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(
            0,
            indexSupport.completeBuildAttempts,
            "查找类使用处不应触发完整类图补齐，否则大项目会被类图构建拖慢。",
        )
        assertTrue(
            events.none { event -> event is GraphEditorApplicationEvent.IndexedGraphRequestFailed },
            "查找类使用处不应因为跳过完整类图补齐而产生失败事件。",
        )
    }

    fun testClassDiagramUsageRequestUsesExplicitTargetOutsideCurrentStructureIndex() {
        addClassUsageFixture()
        addClassUsageTargetOutsideStructureFixture()
        val scopeNodeId = stableJvmId("class", "com.example.usage.OrderService")
        val targetQualifiedName = "com.example.external.ExternalUsageTarget"
        val targetNodeId = stableJvmId("class", targetQualifiedName)
        val indexSupport = CountingCompleteClassDiagramIndexSupport(classUsageStructureIndex())
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(
            requestClassDiagramRequest(scopeNodeId).copy(
                usage = IndexedClassUsageOptions(
                    enabled = true,
                    targetNodeId = targetNodeId,
                    targetQualifiedName = targetQualifiedName,
                    maxUsageGroups = 10,
                    maxUsageEntries = 20,
                    includeImports = false,
                ),
            ),
        )

        val usageEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event ->
                event.view.usage != null
            },
        )
        val usage = assertNotNull(
            usageEvent.view.usage,
            "显式 usage 请求必须返回查询目标的使用处。",
        )
        assertEquals(targetNodeId, usage.target.nodeId)
        assertEquals(targetQualifiedName, usage.target.qualifiedName)
    }

    fun testScopedBodyRelationRequestBuildsCurrentVisibleScopeWithoutCompleteBuild() {
        val symbolIndex = simpleClassDiagramSymbolIndex()
        val structureIndex = ClassDiagramFastIndex.fromSymbols(symbolIndex)
        val indexSupport = ScopedClassDiagramIndexSupport(structureIndex)
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)
        val scopeNodeId = stableJvmId("class", "com.example.BeanInstantiationException")

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(
            requestClassDiagramRequest(scopeNodeId)
                .copy(relationDetail = IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS),
        )

        val structureEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "STRUCTURE_ONLY" },
        )
        val scopedEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "SCOPED_BODY_RELATIONS" },
        )

        assertTrue(structureEvent.view.visibleGraph.nodes.isNotEmpty(), "scoped 补齐前必须先发布结构预览。")
        assertClassDiagramViewDataContract(scopedEvent.view, "workflow.classDiagram.scopedBodyRelations")
        assertEquals(1, indexSupport.scopedBuildAttempts)
        assertEquals(0, indexSupport.completeBuildAttempts)
        assertTrue(
            indexSupport.lastScopedSourceClassIds.contains(scopeNodeId),
            "scoped 方法体补齐必须限制在当前结构图可见类范围内。",
        )
    }

    fun testClassDiagramUsageRequestWithQualifiedNameSkipsStructureIndexBuild() {
        addClassUsageFixture()
        val targetQualifiedName = "com.example.usage.OrderService"
        val targetNodeId = stableJvmId("class", targetQualifiedName)
        val indexSupport = FailingClassDiagramIndexSupport()
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(
            requestClassUsageOverlayRequest(targetNodeId).copy(
                usage = IndexedClassUsageOptions(
                    enabled = true,
                    targetNodeId = targetNodeId,
                    targetQualifiedName = targetQualifiedName,
                    maxUsageGroups = 10,
                    maxUsageEntries = 20,
                    includeImports = false,
                ),
            ),
        )

        val usageEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event ->
                event.view.usage?.target?.qualifiedName == targetQualifiedName
            },
        )
        assertClassDiagramViewDataContract(usageEvent.view, "workflow.classDiagram.usage.standalone")
        assertEquals(0, indexSupport.structureBuildAttempts)
        assertEquals(0, indexSupport.completeBuildAttempts)
        assertTrue(
            usageEvent.view.visibleGraph.edges.any { edge ->
                edge.toNodeId == targetNodeId &&
                    edge.metadata["classDiagram.relation.role"] == "CLASS_USAGE"
            },
            "带 qualifiedName 的 usage 请求应直接生成使用处图，不应等待类图结构索引。",
        )
    }

    fun testClassDiagramUsageRequestWithOnlyNodeIdSkipsStructureIndexBuildWhenPsiCanResolveTarget() {
        addClassUsageFixture()
        val targetQualifiedName = "com.example.usage.OrderService"
        val targetNodeId = stableJvmId("class", targetQualifiedName)
        val indexSupport = FailingClassDiagramIndexSupport()
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(
            requestClassUsageOverlayRequest(targetNodeId).copy(
                usage = IndexedClassUsageOptions(
                    enabled = true,
                    targetNodeId = targetNodeId,
                    maxUsageGroups = 10,
                    maxUsageEntries = 20,
                    includeImports = false,
                ),
            ),
        )

        val usageEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event ->
                event.view.usage?.target?.qualifiedName == targetQualifiedName
            },
        )
        assertClassDiagramViewDataContract(usageEvent.view, "workflow.classDiagram.usage.nodeIdStandalone")
        assertEquals(0, indexSupport.structureBuildAttempts)
        assertEquals(0, indexSupport.completeBuildAttempts)
    }

    fun testClassDiagramKeepsStructurePreviewWhenCompleteRelationBuildIsCancelled() {
        val symbolIndex = simpleClassDiagramSymbolIndex()
        val indexSupport = CancellingCompleteClassDiagramIndexSupport(
            structureIndex = ClassDiagramFastIndex.fromSymbols(symbolIndex),
        )
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)
        val scopeNodeId = stableJvmId("class", "com.example.BeanInstantiationException")

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestIndexedGraph(
            requestClassDiagramRequest(scopeNodeId)
                .copy(relationDetail = IndexedGraphRelationDetail.COMPLETE),
        )

        val structureEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "STRUCTURE_ONLY" },
        )
        PlatformTestUtil.waitWithEventsDispatching(
            "等待完整关系补齐取消被 workflow 消化",
            { indexSupport.completeBuildAttempts > 0 },
            5000,
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(structureEvent.view.visibleGraph.nodes.isNotEmpty(), "结构预览应保留可见类图。")
        assertTrue(
            events.none { event -> event is GraphEditorApplicationEvent.IndexedGraphRequestFailed },
            "完整关系补齐被取消时不能覆盖已成功发布的结构预览。",
        )
    }

    fun testForceRebuildArchitectureRequestReplacesSharedIndex() {
        addArchitectureFixture()
        val indexSupport = ArchitectureIndexWorkflowSupport(project)
        val request = requestArchitectureGraphRequest()

        val firstIndex = indexSupport.buildIndex(request)
        val reusedIndex = indexSupport.buildIndex(request)
        assertSame(firstIndex, reusedIndex)

        val rebuiltIndex = indexSupport.buildIndex(
            request.copy(refreshPolicy = IndexedGraphRefreshPolicy.ForceRebuild),
        )
        assertNotSame(firstIndex, rebuiltIndex)
        assertSame(rebuiltIndex, indexSupport.currentIndex())
        assertSame(rebuiltIndex, project.architectureIndexRuntime().currentIndex())

        val reusedAfterRebuild = indexSupport.buildIndex(request)
        assertSame(rebuiltIndex, reusedAfterRebuild)

        val events = mutableListOf<GraphEditorApplicationEvent>()
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)
        val workflow = ArchitectureGraphWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        )

        workflow.requestIndexedGraph(
            request.copy(refreshPolicy = IndexedGraphRefreshPolicy.ForceRebuild),
        )
        waitForEvent<GraphEditorApplicationEvent.ArchitectureGraphLoaded>(
            events = events,
            predicate = { event -> event.view.summary.indexed?.cacheState == "FORCE_REBUILD" },
        )
        assertNotNull(indexSupport.currentIndex())
        assertSame(indexSupport.currentIndex(), project.architectureIndexRuntime().currentIndex())
    }

    fun testArchitectureOverviewCacheDoesNotPretendToBeFullIndex() {
        addArchitectureFixture()
        val indexSupport = ArchitectureIndexWorkflowSupport(project)
        val architectureRequest = requestArchitectureGraphRequest()
        val classDiagramRequest = requestClassDiagramRequest("arch:component:com.example.service")

        val overviewIndex = indexSupport.buildIndex(architectureRequest)

        assertSame(overviewIndex, indexSupport.currentIndex())
        assertTrue(!indexSupport.hasFullIndex(classDiagramRequest))
        assertTrue(overviewIndex.relationIndex.byKind(JvmRelationKind.SERVICE_LOADER_LOADS).isEmpty())

        val fullIndex = indexSupport.buildIndex(classDiagramRequest)

        assertSame(fullIndex, indexSupport.currentIndex())
        assertTrue(indexSupport.hasFullIndex(classDiagramRequest))
        assertTrue(fullIndex.relationIndex.byKind(JvmRelationKind.SERVICE_LOADER_LOADS).isNotEmpty())
    }

    fun testFullIndexCanBeRestoredFromPersistentSliceCacheAfterHotCacheIsDropped() {
        addArchitectureFixture()
        val budget = JvmResolutionBudget(includeExternalLibraries = false)

        val firstIndex = project.architectureIndexRuntime().index(budget = budget, forceRebuild = true)
        assertTrue(firstIndex.symbolIndex.findField("com.example.service.TaskRunner.provider") != null)
        assertTrue(
            firstIndex.symbolIndex.serviceProviderIndex.filesByInterfaceName.isNotEmpty(),
            "Baseline full build must discover SPI provider files.",
        )
        assertTrue(
            firstIndex.symbolIndex.findClass("com.example.service.DefaultTaskProvider")
                ?.interfaceNames
                ?.contains("com.example.spi.service.TaskProvider") == true,
            "Baseline full build must keep provider implements metadata.",
        )
        assertTrue(
            firstIndex.symbolIndex.findField("com.example.service.TaskRunner.providers")
                ?.effectiveTypeReferences()
                ?.any { reference ->
                    reference.typeName == "com.example.spi.service.TaskProvider" &&
                        reference.role == JvmFieldTypeRole.COLLECTION_ELEMENT
                } == true,
            "Baseline full build must preserve generic field element type references.",
        )
        assertTrue(firstIndex.relationIndex.byKind(JvmRelationKind.SERVICE_LOADER_LOADS).isNotEmpty())

        project.architectureIndexService().clearHotCacheForTesting()

        val restoredIndex = project.architectureIndexRuntime().index(budget = budget)
        val memory = project.architectureIndexService().memorySnapshot()

        assertTrue(memory.persistentCacheHits > 0)
        assertEquals(0, memory.persistentCacheMisses)
        assertEquals(1.0, memory.cacheHitRate)
        assertEquals("PERSISTENT_FULL_HIT", memory.indexSource)
        assertPersistentRestoredIndexKeepsFullJvmSemantics(restoredIndex)
    }

    fun testStaleSliceRebuildReusesPersistentFragmentsForUnchangedSlices() {
        addArchitectureFixture()
        val budget = JvmResolutionBudget(includeExternalLibraries = false)

        project.architectureIndexRuntime().index(budget = budget, forceRebuild = true)
        val spiInterfacePath = "com/example/spi/TaskProvider.java"
        val spiInterfaceSlice = requireNotNull(
            project.architectureIndexService().memorySnapshot().manifest?.slices?.firstOrNull { slice ->
                slice.files.any { file -> file.relativePath.endsWith(spiInterfacePath) }
            },
        ) {
            "Fixture must create a persistent slice for $spiInterfacePath."
        }
        assertTrue(
            spiInterfaceSlice.packagePrefix != "com.example.service",
            "Partial rebuild must leave com.example.service restored from persistent fragments.",
        )
        val changedPath = requireNotNull(
            spiInterfaceSlice.files.firstOrNull { file -> file.relativePath.endsWith(spiInterfacePath) },
        ) {
            "Changed path must come from the SPI interface slice."
        }

        project.architectureIndexService().invalidate("VFS_CHANGE", listOf(changedPath.relativePath))

        val rebuiltIndex = project.architectureIndexRuntime().index(budget = budget)
        val memory = project.architectureIndexService().memorySnapshot()

        assertEquals("PERSISTENT_PARTIAL", memory.indexSource)
        assertTrue(memory.persistentCacheHits > 0)
        assertTrue(memory.persistentCacheMisses > 0)
        assertTrue(rebuiltIndex.symbolIndex.findField("com.example.service.TaskRunner.provider") != null)
        assertPersistentRestoredIndexKeepsFullJvmSemantics(rebuiltIndex)
    }

    fun testRuntimeFragmentsPreserveJarEntryDisplayPathsForPersistence() {
        val jarPath = "/tmp/external.jar"
        val classEntryPath = "$jarPath!/com/external/ExternalPlugin.class"
        val resourceEntryPath = "$jarPath!/META-INF/services/com.external.Plugin"
        val classSource = JvmSourceRef(
            displayPath = classEntryPath,
            virtualFileUrl = "jar://$classEntryPath",
            startLine = 1,
            endLine = 12,
            decompiled = true,
        )
        val resourceSource = JvmSourceRef(
            displayPath = resourceEntryPath,
            virtualFileUrl = "jar://$resourceEntryPath",
            startLine = 1,
            endLine = 1,
            decompiled = false,
        )
        val providerClass = JvmClassSymbol(
            id = "class:external-plugin",
            qualifiedName = "com.external.ExternalPlugin",
            simpleName = "ExternalPlugin",
            packageName = "com.external",
            moduleName = null,
            kind = JvmClassKind.CLASS,
            library = true,
            source = classSource,
            origin = SourceOrigin.USER_ATTACHED_CLASS_JAR,
        )
        val providerResource = JvmResourceSymbol(
            id = "resource:external-spi",
            path = resourceEntryPath,
            kind = JvmResourceKind.SPI_SERVICE_FILE,
            source = resourceSource,
            origin = SourceOrigin.USER_ATTACHED_CLASS_JAR,
        )
        val symbolIndex = JvmSymbolIndex(
            classesByQualifiedName = mapOf(providerClass.qualifiedName to providerClass),
            resourcesByPath = mapOf(providerResource.path to providerResource),
            serviceProviderIndex = JvmServiceProviderIndex(
                mapOf(
                    "com.external.Plugin" to listOf(
                        JvmServiceProviderFile(
                            serviceInterfaceName = "com.external.Plugin",
                            providerClassNames = listOf(providerClass.qualifiedName),
                            resource = providerResource,
                            origin = SourceOrigin.USER_ATTACHED_CLASS_JAR,
                        ),
                    ),
                ),
            ),
        )
        val index = ArchitectureGraphIndex(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(),
            graph = ArchitectureGraph(),
        )
        val slice = ProjectSlice(
            id = "slice:attached-jar",
            moduleName = null,
            contentRoot = "attached-jars",
            sourceSet = "attached-jars",
            packagePrefix = null,
            kind = ProjectSliceKind.ATTACHED_JAR.name,
            files = listOf(
                ProjectFileFingerprint(
                    relativePath = jarPath,
                    size = 10,
                    modifiedAtMillis = 20,
                    contentSha256 = "external-sha",
                ),
            ),
        )

        val fragmentMethod = project.architectureIndexRuntime().javaClass.getDeclaredMethod(
            "fragmentForSlice",
            ProjectSlice::class.java,
            ArchitectureGraphIndex::class.java,
        )
        fragmentMethod.isAccessible = true
        val fragment = fragmentMethod.invoke(project.architectureIndexRuntime(), slice, index) as ArchitectureIndexSliceFragment

        assertEquals(classEntryPath, fragment.symbols.single().sourcePath)
        assertEquals(resourceEntryPath, fragment.resources.single().path)
        assertEquals(resourceEntryPath, fragment.serviceProviders.single().resourcePath)
    }

    fun testRuntimeFragmentsPersistCrossSliceRelationOnlyOnce() {
        val orderSource = JvmSourceRef(
            displayPath = "src/main/java/com/example/orders/OrderService.java",
            virtualFileUrl = "file:///repo/src/main/java/com/example/orders/OrderService.java",
            startLine = 1,
            endLine = 10,
            decompiled = false,
        )
        val customerSource = JvmSourceRef(
            displayPath = "src/main/java/com/example/customers/CustomerService.java",
            virtualFileUrl = "file:///repo/src/main/java/com/example/customers/CustomerService.java",
            startLine = 1,
            endLine = 10,
            decompiled = false,
        )
        val orderClass = JvmClassSymbol(
            id = "class:OrderService",
            qualifiedName = "com.example.orders.OrderService",
            simpleName = "OrderService",
            packageName = "com.example.orders",
            moduleName = null,
            kind = JvmClassKind.CLASS,
            source = orderSource,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val customerClass = JvmClassSymbol(
            id = "class:CustomerService",
            qualifiedName = "com.example.customers.CustomerService",
            simpleName = "CustomerService",
            packageName = "com.example.customers",
            moduleName = null,
            kind = JvmClassKind.CLASS,
            source = customerSource,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val relation = JvmRelation(
            id = "rel:orders-to-customers",
            kind = JvmRelationKind.USES_TYPE,
            fromSymbolId = orderClass.id,
            toSymbolId = customerClass.id,
            confidence = JvmRelationConfidence.PROVEN,
            source = JvmRelationSource.PSI,
        )
        val index = ArchitectureGraphIndex(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = mapOf(
                    orderClass.qualifiedName to orderClass,
                    customerClass.qualifiedName to customerClass,
                ),
            ),
            relationIndex = JvmRelationIndex(listOf(relation)),
            graph = ArchitectureGraph(),
        )
        val orderSlice = ProjectSlice(
            id = "slice:orders",
            moduleName = null,
            contentRoot = "/repo",
            sourceSet = "main",
            packagePrefix = "com.example.orders",
            kind = ProjectSliceKind.JVM_SOURCE.name,
            files = listOf(ProjectFileFingerprint("src/main/java/com/example/orders/OrderService.java", 10, 20, "orders-sha")),
        )
        val customerSlice = ProjectSlice(
            id = "slice:customers",
            moduleName = null,
            contentRoot = "/repo",
            sourceSet = "main",
            packagePrefix = "com.example.customers",
            kind = ProjectSliceKind.JVM_SOURCE.name,
            files = listOf(ProjectFileFingerprint("src/main/java/com/example/customers/CustomerService.java", 10, 20, "customers-sha")),
        )
        val fragmentMethod = project.architectureIndexRuntime().javaClass.getDeclaredMethod(
            "fragmentForSlice",
            ProjectSlice::class.java,
            ArchitectureGraphIndex::class.java,
        )
        fragmentMethod.isAccessible = true

        val fragments = listOf(orderSlice, customerSlice).map { slice ->
            fragmentMethod.invoke(project.architectureIndexRuntime(), slice, index) as ArchitectureIndexSliceFragment
        }

        assertEquals(
            1,
            fragments.sumOf { fragment -> fragment.relations.count { persisted -> persisted.id == relation.id } },
            "A cross-slice relation must have exactly one persistent owner slice.",
        )
    }

    fun testPersistentSliceCacheIsSkippedWhenBudgetExpandsJdkSymbols() {
        addArchitectureFixture()
        val budget = JvmResolutionBudget(includeJdk = true)
        val serviceInterfaceName = "java.nio.file.spi.FileSystemProvider"

        val firstIndex = project.architectureIndexRuntime().index(
            budget = budget,
            forceRebuild = true,
        )
        val firstProviders = firstIndex.symbolIndex.serviceProviderIndex.providersFor(serviceInterfaceName)
        assertTrue(
            firstProviders.isNotEmpty(),
            "Baseline JDK-expanded full build must include JDK SPI providers.",
        )

        project.architectureIndexService().clearHotCacheForTesting()

        val rebuiltIndex = project.architectureIndexRuntime().index(budget = budget)
        val memory = project.architectureIndexService().memorySnapshot()

        assertEquals(
            "Persistent slice cache must not restore partial project slices for JDK-expanded budgets.",
            "FULL_REBUILD",
            memory.indexSource,
        )
        assertEquals(
            "JDK SPI providers must remain equivalent after the hot cache is dropped.",
            firstProviders.flatMap { file -> file.providerClassNames }.toSet(),
            rebuiltIndex.symbolIndex.serviceProviderIndex.providersFor(serviceInterfaceName)
                .flatMap { file -> file.providerClassNames }
                .toSet(),
        )
    }

    fun testUnscopedClassDiagramAnchorsCurrentEditorClass() {
        addClassDiagramAnchorFixture()
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val indexSupport = ArchitectureIndexWorkflowSupport(project)
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            snapshotProvider = EditorSnapshotProvider { testSnapshot().toWorkflowEditorSnapshot() },
            logger = logger,
        ).requestIndexedGraph(requestClassDiagramRequest())

        val structureEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "STRUCTURE_ONLY" },
        )
        assertTrue(
            structureEvent.view.visibleGraph.nodes.any { node ->
                node.signature == "com.example.debug.DebugGraphDefinition"
            },
            "结构预览也应优先锚定当前编辑器类，避免前端长时间空白。",
        )
        val structureView = structureEvent.view
        assertClassDiagramViewDataContract(structureView, "workflow.classDiagram.currentEditorAnchor")
        assertTrue(
            structureView.anchorNodeId?.contains("debuggraphdefinition") == true,
            "未指定类图范围时应优先锚定当前编辑器所在类。",
        )
        assertEquals(structureView.anchorNodeId, structureView.summary.indexed?.anchorNodeId)
        assertEquals("DebugGraphDefinition", structureView.summary.indexed?.anchorTitle)
        assertEquals("com.example.debug.DebugGraphDefinition", structureView.summary.indexed?.anchorQualifiedName)
        assertTrue(
            structureView.visibleGraph.nodes.any { node -> node.signature == "com.example.debug.DebugGraphDefinition" },
            "类图应展示当前编辑器类 DebugGraphDefinition。",
        )
        assertTrue(
            structureView.visibleGraph.nodes.none { node -> node.signature == "com.example.app.DraftPatchUndo" },
            "类图不应在当前编辑器类存在时跳到无关项目默认类。",
        )
    }

    private fun addArchitectureFixture() {
        myFixture.addFileToProject(
            "src/main/java/com/example/spi/TaskProvider.java",
            """
                package com.example.spi.service;

                public interface TaskProvider {
                    void provide();
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/service/DefaultTaskProvider.java",
            """
                package com.example.service;

                import com.example.spi.service.TaskProvider;

                public class DefaultTaskProvider implements TaskProvider {
                    public void provide() {}
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/service/TaskRunner.java",
            """
                package com.example.service;

                import com.example.spi.service.TaskProvider;
                import java.util.List;
                import java.util.ServiceLoader;

                public class TaskRunner {
                    private TaskProvider provider;
                    private List<TaskProvider> providers;

                    public void run() {
                        for (TaskProvider candidate : ServiceLoader.load(TaskProvider.class)) {
                            provider = candidate;
                            candidate.provide();
                        }
                    }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/META-INF/services/com.example.spi.service.TaskProvider",
            "com.example.service.DefaultTaskProvider\n",
        )
    }

    private fun addClassDiagramAnchorFixture() {
        myFixture.addFileToProject(
            "src/main/java/com/example/app/DraftPatchUndo.java",
            """
                package com.example.app;

                public class DraftPatchUndo {
                    private String graphBeforeApply;
                }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "DebugGraphDefinition.java",
            """
                package com.example.debug;

                public class DebugGraphDefinition {
                    private String anchorSignature;
                    public String summary() {
                        return anchorSignature;
                    }
                }
            """.trimIndent(),
        )
    }

    private fun addClassUsageFixture() {
        myFixture.addFileToProject(
            "src/main/java/com/example/usage/OrderService.java",
            """
                package com.example.usage;

                public class OrderService {
                    public void submit() {}
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/usage/OrderController.java",
            """
                package com.example.usage;

                import com.example.usage.OrderService;

                public class OrderController {
                    private OrderService service;

                    public OrderController(OrderService service) {
                        this.service = service;
                    }

                    public void submit(OrderService overrideService) {
                        new OrderService().submit();
                    }
                }
            """.trimIndent(),
        )
    }

    private fun addClassUsageTargetOutsideStructureFixture() {
        myFixture.addFileToProject(
            "src/main/java/com/example/external/ExternalUsageTarget.java",
            """
                package com.example.external;

                public class ExternalUsageTarget {
                    public void touch() {}
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/external/ExternalUsageConsumer.java",
            """
                package com.example.external;

                public class ExternalUsageConsumer {
                    private ExternalUsageTarget target;

                    public void run(ExternalUsageTarget overrideTarget) {
                        new ExternalUsageTarget().touch();
                    }
                }
            """.trimIndent(),
        )
    }

    private fun assertPersistentRestoredIndexKeepsFullJvmSemantics(index: ArchitectureGraphIndex) {
        assertTrue(
            index.relationIndex.byKind(JvmRelationKind.IMPLEMENTS).isNotEmpty(),
            "Persistent restore must keep IMPLEMENTS relations.",
        )
        assertTrue(
            index.relationIndex.byKind(JvmRelationKind.SPI_PROVIDES).isNotEmpty(),
            "Persistent restore must keep SPI provider relations.",
        )
        assertTrue(
            index.relationIndex.byKind(JvmRelationKind.SERVICE_LOADER_LOADS).isNotEmpty(),
            "Persistent restore must keep ServiceLoader relations.",
        )

        val provider = assertNotNull(
            index.symbolIndex.findClass("com.example.service.DefaultTaskProvider"),
            "Restored provider class must exist.",
        )
        assertTrue(
            provider.interfaceNames.contains("com.example.spi.service.TaskProvider"),
            "Persistent restore must keep provider implements metadata.",
        )

        val field = assertNotNull(
            index.symbolIndex.findField("com.example.service.TaskRunner.providers"),
            "Restored providers field must exist.",
        )
        assertTrue(
            field.effectiveTypeReferences().any { reference ->
                reference.typeName == "com.example.spi.service.TaskProvider" &&
                    reference.role == JvmFieldTypeRole.COLLECTION_ELEMENT
            },
            "Restored field must keep rich generic type references.",
        )

        val providerFiles = index.symbolIndex.serviceProviderIndex.providersFor("com.example.spi.service.TaskProvider")
        assertTrue(providerFiles.isNotEmpty(), "Restored index must keep serviceProviderIndex.")
        assertTrue(
            providerFiles.any { file ->
                file.providerClassNames.contains("com.example.service.DefaultTaskProvider") &&
                    file.resource.path.endsWith("META-INF/services/com.example.spi.service.TaskProvider")
            },
            "Restored serviceProviderIndex must keep SPI interface, provider class, and resource path.",
        )
    }

    private inline fun <reified T : GraphEditorApplicationEvent> waitForEvent(
        events: List<GraphEditorApplicationEvent>,
        noinline predicate: (T) -> Boolean = { true },
    ): T {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            events.filterIsInstance<T>().lastOrNull(predicate)?.let { return it }
            val indexedFailure = events.filterIsInstance<GraphEditorApplicationEvent.IndexedGraphRequestFailed>()
                .lastOrNull()
            if (indexedFailure != null) {
                fail("Workflow failed before ${T::class.simpleName}: ${indexedFailure.statusMessage}")
            }
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for ${T::class.simpleName}. Events: $events")
    }

    private fun simpleClassDiagramSymbolIndex(): JvmSymbolIndex {
        val fatal = JvmClassSymbol(
            id = stableJvmId("class", "com.example.FatalBeanException"),
            qualifiedName = "com.example.FatalBeanException",
            simpleName = "FatalBeanException",
            packageName = "com.example",
            moduleName = null,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val bean = JvmClassSymbol(
            id = stableJvmId("class", "com.example.BeanInstantiationException"),
            qualifiedName = "com.example.BeanInstantiationException",
            simpleName = "BeanInstantiationException",
            packageName = "com.example",
            moduleName = null,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
            superClassName = fatal.qualifiedName,
        )
        return JvmSymbolIndex(
            classesByQualifiedName = listOf(fatal, bean).associateBy(JvmClassSymbol::qualifiedName),
        )
    }

    private fun classUsageStructureIndex(): ArchitectureGraphIndex {
        val service = JvmClassSymbol(
            id = stableJvmId("class", "com.example.usage.OrderService"),
            qualifiedName = "com.example.usage.OrderService",
            simpleName = "OrderService",
            packageName = "com.example.usage",
            moduleName = null,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        val controller = JvmClassSymbol(
            id = stableJvmId("class", "com.example.usage.OrderController"),
            qualifiedName = "com.example.usage.OrderController",
            simpleName = "OrderController",
            packageName = "com.example.usage",
            moduleName = null,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
        return ClassDiagramFastIndex.fromSymbols(
            JvmSymbolIndex(
                classesByQualifiedName = listOf(service, controller).associateBy(JvmClassSymbol::qualifiedName),
            ),
        )
    }

    private class CancellingCompleteClassDiagramIndexSupport(
        private val structureIndex: ArchitectureGraphIndex,
    ) : ClassDiagramIndexSupport {
        var completeBuildAttempts: Int = 0
            private set

        override fun currentIndex(): ArchitectureGraphIndex? = null

        override fun freshness(): IndexedGraphFreshness = IndexedGraphFreshness()

        override fun buildIndex(request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest): ArchitectureGraphIndex =
            error("The cancellation regression must use the structure-only path first.")

        override fun buildIndex(
            request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
            symbolIndexHint: JvmSymbolIndex?,
        ): ArchitectureGraphIndex {
            completeBuildAttempts += 1
            throw ProcessCanceledException()
        }

        override fun buildClassDiagramStructureIndex(
            request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
        ): ArchitectureGraphIndex = structureIndex

        override fun hasFullIndex(request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest): Boolean = false
    }

    private class CountingCompleteClassDiagramIndexSupport(
        private val structureIndex: ArchitectureGraphIndex,
    ) : ClassDiagramIndexSupport {
        var completeBuildAttempts: Int = 0
            private set

        override fun currentIndex(): ArchitectureGraphIndex? = null

        override fun freshness(): IndexedGraphFreshness = IndexedGraphFreshness()

        override fun buildIndex(request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest): ArchitectureGraphIndex =
            error("Usage requests should use the structure index first.")

        override fun buildIndex(
            request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
            symbolIndexHint: JvmSymbolIndex?,
        ): ArchitectureGraphIndex {
            completeBuildAttempts += 1
            return structureIndex
        }

        override fun buildClassDiagramStructureIndex(
            request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
        ): ArchitectureGraphIndex = structureIndex

        override fun hasFullIndex(request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest): Boolean = false
    }

    private class ScopedClassDiagramIndexSupport(
        private val structureIndex: ArchitectureGraphIndex,
    ) : ClassDiagramIndexSupport {
        var scopedBuildAttempts: Int = 0
            private set
        var completeBuildAttempts: Int = 0
            private set
        var lastScopedSourceClassIds: Set<String> = emptySet()
            private set

        override fun currentIndex(): ArchitectureGraphIndex? = null

        override fun freshness(): IndexedGraphFreshness = IndexedGraphFreshness()

        override fun buildIndex(request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest): ArchitectureGraphIndex =
            error("Scoped body relation requests should use the structure index first.")

        override fun buildIndex(
            request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
            symbolIndexHint: JvmSymbolIndex?,
        ): ArchitectureGraphIndex {
            completeBuildAttempts += 1
            return structureIndex
        }

        override fun buildScopedClassDiagramIndex(
            request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
            symbolIndexHint: JvmSymbolIndex?,
            sourceClassIds: Set<String>,
        ): ArchitectureGraphIndex {
            scopedBuildAttempts += 1
            lastScopedSourceClassIds = sourceClassIds
            return structureIndex
        }

        override fun buildClassDiagramStructureIndex(
            request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
        ): ArchitectureGraphIndex = structureIndex

        override fun hasFullIndex(request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest): Boolean = false
    }

    private class FailingClassDiagramIndexSupport : ClassDiagramIndexSupport {
        var structureBuildAttempts: Int = 0
            private set
        var completeBuildAttempts: Int = 0
            private set

        override fun currentIndex(): ArchitectureGraphIndex? = null

        override fun freshness(): IndexedGraphFreshness = IndexedGraphFreshness()

        override fun buildIndex(request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest): ArchitectureGraphIndex {
            completeBuildAttempts += 1
            error("Standalone usage requests must not build the complete class diagram index.")
        }

        override fun buildIndex(
            request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
            symbolIndexHint: JvmSymbolIndex?,
        ): ArchitectureGraphIndex {
            completeBuildAttempts += 1
            error("Standalone usage requests must not build the complete class diagram index.")
        }

        override fun buildClassDiagramStructureIndex(
            request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
        ): ArchitectureGraphIndex {
            structureBuildAttempts += 1
            error("Standalone usage requests must not build the class diagram structure index.")
        }

        override fun hasFullIndex(request: com.charmnight.linkgraph.application.indexed.IndexedGraphRequest): Boolean = false
    }
}
