package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.source.SourceOrigin
import com.charmnight.linkgraph.testing.assertArchitectureGraphViewDataContract
import com.charmnight.linkgraph.testing.assertClassDiagramViewDataContract
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ArchitectureWorkflowChainTest : BasePlatformTestCase() {
    fun testArchitectureAndClassDiagramWorkflowsBuildSharedIndexFromProjectSources() {
        addArchitectureFixture()
        val events = mutableListOf<GraphEditorApplicationEvent>()
        val indexSupport = ArchitectureIndexWorkflowSupport(project)
        val logger = Logger.getInstance(ArchitectureWorkflowChainTest::class.java)

        ArchitectureGraphWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestArchitectureGraph()

        val architectureEvent = waitForEvent<GraphEditorApplicationEvent.ArchitectureGraphLoaded>(events)
        val architectureView = architectureEvent.view
        assertArchitectureGraphViewDataContract(architectureView, "workflow.architecture")
        assertEquals(ApplicationFeedbackLevel.SUCCESS, events.filterIsInstance<GraphEditorApplicationEvent.Feedback>().last().level)
        assertTrue(architectureView.visibleGraph.nodes.any { node -> node.type == NodeType.PACKAGE })
        assertTrue(architectureView.visibleGraph.nodes.any { node -> node.type == NodeType.LAYER })
        assertTrue(architectureView.visibleGraph.nodes.any { node -> node.type == NodeType.SERVICE })
        assertTrue(architectureView.visibleGraph.nodes.any { node -> node.type == NodeType.RESOURCE })
        assertTrue(
            architectureView.fullGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.SPI_PROVIDES.name
            },
            "架构图必须从 ArchitectureGraphIndex 投影出 SPI provider 聚合关系。",
        )

        val sharedIndex = assertNotNull(indexSupport.currentIndex(), "架构图 workflow 应留下共享 ArchitectureGraphIndex。")
        assertTrue(sharedIndex.symbolIndex.findClass("com.example.spi.TaskProvider") != null)
        assertTrue(sharedIndex.symbolIndex.findField("com.example.service.TaskRunner.provider") != null)
        assertTrue(sharedIndex.relationIndex.byKind(JvmRelationKind.SERVICE_LOADER_LOADS).isNotEmpty())
        assertEquals(sharedIndex, project.architectureIndexRuntime().index())
        project.architectureIndexRuntime().symbolQuery().summary()
        project.architectureIndexRuntime().reviewQuery()
        assertEquals(sharedIndex, project.architectureIndexRuntime().currentIndex())
        assertTrue(
            project.architectureIndexRuntime()
                .sourceQuery()
                .readClassByQualifiedName("com.example.spi.TaskProvider")
                ?.origin == SourceOrigin.PROJECT_SOURCE,
        )

        ClassDiagramWorkflow(
            project = project,
            indexSupport = indexSupport,
            eventSink = events::add,
            logger = logger,
        ).requestClassDiagram("arch:service:com.example")

        val classDiagramEvent = waitForEvent<GraphEditorApplicationEvent.ClassDiagramLoaded>(events)
        val classDiagramView = classDiagramEvent.view
        assertClassDiagramViewDataContract(classDiagramView, "workflow.classDiagram")
        assertEquals(ApplicationFeedbackLevel.SUCCESS, events.filterIsInstance<GraphEditorApplicationEvent.Feedback>().last().level)
        assertTrue(classDiagramView.summary.fieldCount >= 1)
        assertTrue(classDiagramView.visibleGraph.nodes.any { node -> node.signature == "com.example.spi.TaskProvider" })
        assertTrue(classDiagramView.visibleGraph.nodes.any { node -> node.signature == "com.example.service.TaskRunner" })
        assertTrue(
            classDiagramView.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.IMPLEMENTS.name
            },
            "类图必须从同一个 ArchitectureGraphIndex 投影出 UML 类型结构关系。",
        )
        assertTrue(
            classDiagramView.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.SERVICE_LOADER_LOADS.name
            },
            "类图必须从同一个 ArchitectureGraphIndex 投影出 ServiceLoader 运行时集成关系。",
        )
        assertTrue(
            classDiagramView.visibleGraph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.SPI_PROVIDES.name
            },
            "类图必须从同一个 ArchitectureGraphIndex 投影出 SPI provider 关系。",
        )
    }

    private fun addArchitectureFixture() {
        myFixture.addFileToProject(
            "src/main/java/com/example/spi/TaskProvider.java",
            """
                package com.example.spi;

                public interface TaskProvider {
                    void provide();
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/spi/DefaultTaskProvider.java",
            """
                package com.example.spi;

                public class DefaultTaskProvider implements TaskProvider {
                    public void provide() {}
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/service/TaskRunner.java",
            """
                package com.example.service;

                import com.example.spi.TaskProvider;
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
            "src/main/resources/META-INF/services/com.example.spi.TaskProvider",
            "com.example.spi.DefaultTaskProvider\n",
        )
    }

    private inline fun <reified T : GraphEditorApplicationEvent> waitForEvent(
        events: List<GraphEditorApplicationEvent>,
    ): T {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            events.filterIsInstance<T>().lastOrNull()?.let { return it }
            val errorFeedback = events.filterIsInstance<GraphEditorApplicationEvent.Feedback>()
                .lastOrNull { feedback -> feedback.level == ApplicationFeedbackLevel.ERROR }
            if (errorFeedback != null) {
                fail("Workflow failed before ${T::class.simpleName}: ${errorFeedback.message}")
            }
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for ${T::class.simpleName}. Events: $events")
    }
}
