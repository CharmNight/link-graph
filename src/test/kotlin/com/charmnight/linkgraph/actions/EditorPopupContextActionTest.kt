package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.charmnight.linkgraph.services.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.OperationFeedbackLevel
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance
import java.util.concurrent.TimeUnit

class EditorPopupContextActionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerServiceInstance(GraphEditorStateService::class.java, GraphEditorStateService())
        project.registerServiceInstance(LinkGraphProjectTestOverrides::class.java, LinkGraphProjectTestOverrides())
        project.registerServiceInstance(LinkGraphProjectService::class.java, LinkGraphProjectService(project))
    }

    fun testEditorPopupUpdateStaysAvailableForUncommittedJavaDocumentOnBackgroundThread() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void pla<caret>ce(String value) {
                    }
                }
            """.trimIndent(),
        )

        val document = myFixture.editor.document
        val insertOffset = document.text.indexOf("}")
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(insertOffset, "\n        // dirty update")
        }
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        assertFalse(psiDocumentManager.isCommitted(document))

        val addEvent = editorPopupEvent()
        val openEvent = editorPopupEvent()

        AppExecutorUtil.getAppExecutorService().submit<Unit> {
            AddCurrentMethodToGraphAction().update(addEvent)
            OpenLinkGraphAction().update(openEvent)
        }.get(5, TimeUnit.SECONDS)

        assertFalse(psiDocumentManager.isCommitted(document))
        assertTrue(addEvent.presentation.isEnabledAndVisible)
        assertTrue(openEvent.presentation.isEnabledAndVisible)
    }

    fun testEditorPopupUpdateStaysAvailableForKotlinMethodDuringDumbMode() {
        myFixture.configureByText(
            "OrderService.kt",
            """
                package com.example

                class OrderService {
                    fun pla<caret>ce(value: String) {
                        println(value)
                    }
                }
            """.trimIndent(),
        )

        val addEvent = editorPopupEvent()
        val openEvent = editorPopupEvent()

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            AppExecutorUtil.getAppExecutorService().submit<Unit> {
                AddCurrentMethodToGraphAction().update(addEvent)
                OpenLinkGraphAction().update(openEvent)
            }.get(5, TimeUnit.SECONDS)
        }

        assertTrue(addEvent.presentation.isEnabledAndVisible)
        assertTrue(openEvent.presentation.isEnabledAndVisible)
        assertEquals("添加当前方法到链路图", addEvent.presentation.text)
        assertEquals("查看当前方法完整链路", openEvent.presentation.text)
    }

    fun testEditorPopupUpdateUsesProjectServiceSubjectLocatorPreview() {
        myFixture.configureByText(
            "notes.txt",
            """
                plain <caret>text
            """.trimIndent(),
        )
        val testOverrides = project.getService(com.charmnight.linkgraph.services.LinkGraphProjectTestOverrides::class.java)
        testOverrides.subjectLocator = object : SubjectLocator {
            override fun locate(
                project: com.intellij.openapi.project.Project,
                editor: com.intellij.openapi.editor.Editor?,
                commitDocument: Boolean,
            ): SubjectHandle? = null

            override fun previewKind(
                project: com.intellij.openapi.project.Project,
                editor: com.intellij.openapi.editor.Editor?,
                commitDocument: Boolean,
            ): SubjectPreviewKind = SubjectPreviewKind.RESOURCE_SUBJECT
        }

        val addEvent = editorPopupEvent()
        val openEvent = editorPopupEvent()

        AddCurrentMethodToGraphAction().update(addEvent)
        OpenLinkGraphAction().update(openEvent)

        assertTrue(addEvent.presentation.isEnabledAndVisible)
        assertTrue(openEvent.presentation.isEnabledAndVisible)
        assertEquals("添加当前节点到链路图", addEvent.presentation.text)
        assertEquals("查看当前节点关联图", openEvent.presentation.text)
    }

    fun testOpenActionQueuesKotlinMethodAnalysisUntilSmartMode() {
        myFixture.configureByText(
            "OrderService.kt",
            """
                package com.example

                class OrderService {
                    fun place(value: String) {
                        println(<caret>value)
                    }
                }
            """.trimIndent(),
        )

        val action = OpenLinkGraphAction()
        val event = editorPopupEvent()
        val token = DumbModeTestUtils.startEternalDumbModeTask(project)

        try {
            action.actionPerformed(event)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            val queuedSnapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            assertEquals(OperationFeedbackLevel.INFO, queuedSnapshot.operationFeedback?.level)
            assertEquals("项目正在索引，已在索引完成后继续分析当前编辑器上下文链路。", queuedSnapshot.operationFeedback?.message)
        } finally {
            DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(project, token)
        }

        waitForGraphNode(NodeType.METHOD, "OrderService.place")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertNotNull(snapshot.visibleGraph)
        assertTrue(snapshot.visibleGraph!!.nodes.any { node -> node.type == NodeType.METHOD && node.title == "OrderService.place" })
    }

    fun testAddActionVisibleAndAppendsConfigItemNodeFromPropertiesEditor() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
                package com.example;

                class OrderService {
                    void submit() {
                    }
                }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "application.properties",
            """
                order.submit.han<caret>dler=com.example.OrderService#submit
            """.trimIndent(),
        )

        val action = AddCurrentMethodToGraphAction()
        val event = editorPopupEvent()

        action.update(event)

        assertTrue(event.presentation.isEnabledAndVisible)

        action.actionPerformed(event)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.visibleGraph)
        assertTrue(
            snapshot.visibleGraph!!.nodes.any { node ->
                node.type == NodeType.CONFIG_ITEM && node.title == "order.submit.handler"
            },
        )
    }

    fun testOpenActionVisibleAndLoadsSqlNodeFromMyBatisXmlEditor() {
        myFixture.addFileToProject(
            "src/main/java/com/example/UserMapper.java",
            """
                package com.example;

                interface UserMapper {
                    User selectUser(Long id);
                }

                class User {
                }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            """
                <mapper namespace="com.example.UserMapper">
                  <select id="select<caret>User" resultType="com.example.User">
                    select * from user
                  </select>
                </mapper>
            """.trimIndent(),
        )

        val action = OpenLinkGraphAction()
        val event = editorPopupEvent()

        action.update(event)

        assertTrue(event.presentation.isEnabledAndVisible)

        action.actionPerformed(event)
        waitForGraphNode(NodeType.SQL, "selectUser")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.visibleGraph)
        assertTrue(
            snapshot.visibleGraph!!.nodes.any { node ->
                node.type == NodeType.SQL && node.metadata["statementId"] == "selectUser"
            },
        )
    }

    fun testOpenActionLoadsNodeGraphFromMarkdownMethodSignatureReference() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
                package com.example;

                class OrderService {
                    String submit(String value) {
                        return value.trim();
                    }
                }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                当前链路入口：com.example.OrderService.sub<caret>mit(java.lang.String):java.lang.String
            """.trimIndent(),
        )

        val action = OpenLinkGraphAction()
        val event = editorPopupEvent()

        action.update(event)

        assertTrue(event.presentation.isEnabledAndVisible)

        action.actionPerformed(event)
        waitForGraphNode(NodeType.DOC_PAGE, "order-flow.md")
        waitForGraphNode(NodeType.METHOD, "OrderService.submit")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("currentContext", snapshot.lastGraphSource)
        assertNotNull(snapshot.visibleGraph)
        assertTrue(snapshot.visibleGraph!!.nodes.any { node -> node.type == NodeType.DOC_PAGE && node.title == "order-flow.md" })
        assertTrue(snapshot.visibleGraph!!.nodes.any { node -> node.type == NodeType.METHOD && node.title == "OrderService.submit" })
    }

    fun testOpenActionResolvesExactOverloadedMethodFromMarkdownMethodSignatureReference() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
                package com.example;

                class OrderService {
                    String submit(String value) {
                        return normalize(value);
                    }

                    String submit(Long value) {
                        return store(value);
                    }

                    String normalize(String value) {
                        return value.trim();
                    }

                    String store(Long value) {
                        return String.valueOf(value);
                    }
                }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                当前链路入口：com.example.OrderService.sub<caret>mit(java.lang.Long):java.lang.String
            """.trimIndent(),
        )

        val action = OpenLinkGraphAction()
        val event = editorPopupEvent()

        action.update(event)

        assertTrue(event.presentation.isEnabledAndVisible)

        action.actionPerformed(event)
        waitForGraphNode(NodeType.DOC_PAGE, "order-flow.md")
        waitForGraphNode(NodeType.METHOD, "OrderService.submit")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("currentContext", snapshot.lastGraphSource)
        assertEquals(AnalysisDisplayMode.RESOURCE_RELATION_VIEW, snapshot.analysisDisplayMode)
        assertEquals("com.example.OrderService.submit(java.lang.Long):java.lang.String", snapshot.selectedMethodSignature)
        assertNotNull(snapshot.visibleGraph)
        val methodNodes = snapshot.visibleGraph!!.nodes.filter { node -> node.type == NodeType.METHOD }
        assertTrue(methodNodes.any { node -> node.signature == "com.example.OrderService.submit(java.lang.Long):java.lang.String" })
        assertFalse(methodNodes.any { node -> node.signature == "com.example.OrderService.submit(java.lang.String):java.lang.String" })
        assertFalse(methodNodes.any { node -> node.title == "OrderService.normalize" })
        assertFalse(methodNodes.any { node -> node.title == "OrderService.store" })
        assertTrue(snapshot.trustedNavigationNodes.values.any { node -> node.title == "OrderService.store" })
    }

    fun testOpenActionFallsBackToNodeGraphWhenMarkdownMethodReferenceIsAmbiguous() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
                package com.example;

                class OrderService {
                    String submit(String value) {
                        return normalize(value);
                    }

                    String submit(Long value) {
                        return store(value);
                    }

                    String normalize(String value) {
                        return value.trim();
                    }

                    String store(Long value) {
                        return String.valueOf(value);
                    }
                }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                当前链路入口：com.example.OrderService.sub<caret>mit
            """.trimIndent(),
        )

        val action = OpenLinkGraphAction()
        val event = editorPopupEvent()

        action.update(event)

        assertTrue(event.presentation.isEnabledAndVisible)

        action.actionPerformed(event)
        waitForGraphNode(NodeType.DOC_PAGE, "order-flow.md")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("currentContext", snapshot.lastGraphSource)
        assertNotNull(snapshot.visibleGraph)
        assertEquals(listOf(NodeType.DOC_PAGE), snapshot.visibleGraph!!.nodes.map { node -> node.type })
        assertEquals(OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
        assertTrue(snapshot.operationFeedback?.message.orEmpty().contains("候选"))
        val docNode = snapshot.visibleGraph!!.nodes.single()
        assertEquals("AMBIGUOUS", docNode.metadata["linkGraph.anchorResolutionState"])
        assertTrue(docNode.metadata["linkGraph.anchorResolutionHint"].orEmpty().contains("候选"))
        assertTrue(docNode.metadata["linkGraph.anchorCandidates"].orEmpty().contains("com.example.OrderService.submit(java.lang.String):java.lang.String"))
        assertTrue(docNode.metadata["linkGraph.anchorCandidates"].orEmpty().contains("com.example.OrderService.submit(java.lang.Long):java.lang.String"))
    }

    private fun waitForGraphNode(type: NodeType, marker: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (snapshot.visibleGraph?.nodes?.any { node ->
                    node.type == type && (
                        node.title.contains(marker) ||
                            node.metadata.values.any { value -> value.contains(marker) }
                        )
                } == true
            ) {
                return
            }
            Thread.sleep(50)
        }
        fail("Timed out waiting for graph node: $type / $marker")
    }

    private fun editorPopupEvent(): AnActionEvent {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .build()
        return AnActionEvent(
            null,
            dataContext,
            ActionPlaces.EDITOR_POPUP,
            com.intellij.openapi.actionSystem.Presentation(),
            ActionManager.getInstance(),
            0,
        )
    }
}
