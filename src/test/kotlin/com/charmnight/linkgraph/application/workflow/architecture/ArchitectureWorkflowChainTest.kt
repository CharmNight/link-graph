package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.indexed.IndexedGraphRefreshPolicy
import com.charmnight.linkgraph.application.indexed.requestArchitectureGraphRequest
import com.charmnight.linkgraph.application.indexed.requestClassDiagramRequest
import com.charmnight.linkgraph.application.model.AsyncRequestPhase
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.source.SourceOrigin
import com.charmnight.linkgraph.testing.assertArchitectureGraphViewDataContract
import com.charmnight.linkgraph.testing.assertClassDiagramViewDataContract
import com.charmnight.linkgraph.testing.testSnapshot
import com.charmnight.linkgraph.ui.toWorkflowEditorSnapshot
import com.intellij.openapi.diagnostic.Logger
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

        val classDiagramEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(events)
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

    fun testColdClassDiagramKeepsCanvasLoadingUntilCompleteRelations() {
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

        val completeEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "COMPLETE" },
        )
        assertTrue(
            events.filterIsInstance<GraphEditorApplicationEvent.ClassDiagramLoaded>().none { event ->
                event.view.summary.relationCompleteness == "STRUCTURE_ONLY"
            },
            "类图冷启动不能把 STRUCTURE_ONLY 半成品作为正式类图同步到前端。",
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

        val completeEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(
            events = events,
            predicate = { event -> event.view.summary.relationCompleteness == "COMPLETE" },
        )
        assertTrue(
            events.filterIsInstance<GraphEditorApplicationEvent.ClassDiagramLoaded>().none { event ->
                event.view.summary.relationCompleteness == "STRUCTURE_ONLY"
            },
            "未指定范围时也不能把结构预览当作类图完成态发布。",
        )
        val completeView = completeEvent.view
        assertClassDiagramViewDataContract(completeView, "workflow.classDiagram.currentEditorAnchor")
        assertTrue(
            completeView.anchorNodeId?.contains("debuggraphdefinition") == true,
            "未指定类图范围时应优先锚定当前编辑器所在类。",
        )
        assertEquals(completeView.anchorNodeId, completeView.summary.indexed?.anchorNodeId)
        assertEquals("DebugGraphDefinition", completeView.summary.indexed?.anchorTitle)
        assertEquals("com.example.debug.DebugGraphDefinition", completeView.summary.indexed?.anchorQualifiedName)
        assertTrue(
            completeView.visibleGraph.nodes.any { node -> node.signature == "com.example.debug.DebugGraphDefinition" },
            "类图应展示当前编辑器类 DebugGraphDefinition。",
        )
        assertTrue(
            completeView.visibleGraph.nodes.none { node -> node.signature == "com.example.app.DraftPatchUndo" },
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
                import java.util.ServiceLoader;

                public class TaskRunner {
                    private TaskProvider provider;

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
}
