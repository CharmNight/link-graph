package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ClassDiagramIT : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerServiceInstance(GraphEditorStateService::class.java, GraphEditorStateService())
        project.registerServiceInstance(LinkGraphProjectTestOverrides::class.java, LinkGraphProjectTestOverrides())
        project.registerServiceInstance(GraphEditorApplicationService::class.java, GraphEditorApplicationService(project))
        project.registerServiceInstance(GraphEditorCommandRouter::class.java, GraphEditorCommandRouter(project))
    }

    fun testBridgeDispatchRequestsClassDiagramFromSharedArchitectureIndex() {
        myFixture.addFileToProject(
            "src/main/java/com/example/classdiagram/TaskProvider.java",
            """
                package com.example.classdiagram;

                public interface TaskProvider {
                    void provide();
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/classdiagram/DefaultTaskProvider.java",
            """
                package com.example.classdiagram;

                public class DefaultTaskProvider implements TaskProvider {
                    public void provide() {}
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/classdiagram/TaskRunner.java",
            """
                package com.example.classdiagram;

                import java.util.ServiceLoader;

                public class TaskRunner {
                    public void run() {
                        ServiceLoader.load(TaskProvider.class).forEach(TaskProvider::provide);
                    }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/META-INF/services/com.example.classdiagram.TaskProvider",
            "com.example.classdiagram.DefaultTaskProvider\n",
        )

        GraphEditorBridge(project).dispatch(GraphEditorMessage.RequestClassDiagram())
        waitForClassDiagram()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val graph = snapshot.classDiagramView.visibleGraph
        assertEquals(GraphSceneId.WORKSPACE_CLASS_DIAGRAM, snapshot.currentSceneId)
        assertClassDiagramViewDataContract(snapshot.classDiagramView, "bridge.classDiagram")
        assertNotNull(graph.nodes.singleOrNull { node -> node.signature == "com.example.classdiagram.TaskProvider" })
        assertNotNull(graph.nodes.singleOrNull { node -> node.signature == "com.example.classdiagram.TaskRunner" })
        assertTrue(
            graph.edges.any { edge ->
                edge.metadata["jvm.relation.kind"] == JvmRelationKind.SERVICE_LOADER_LOADS.name
            },
            "ClassDiagramIT must prove the bridge path projects ServiceLoader relations from ArchitectureGraphIndex.",
        )
        assertEquals("SUCCESS", snapshot.operationFeedback?.level?.name)
    }

    private fun waitForClassDiagram() {
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (
                snapshot.currentSceneId == GraphSceneId.WORKSPACE_CLASS_DIAGRAM &&
                snapshot.classDiagramView.visibleGraph.nodes.isNotEmpty()
            ) {
                return
            }
            Thread.sleep(100)
        }
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        fail("Expected Class Diagram to be loaded, last feedback=${snapshot.operationFeedback}")
    }
}
