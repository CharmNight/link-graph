package com.charmnight.linkgraph.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphBrowserBridgeRegistrarTest {
    private val projectRoot: Path = Path.of("").toAbsolutePath()

    @Test
    fun openSettingsBridgeHandlerReturnsBeforeShowingModalSettingsDialog() {
        val source = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserBridgeRegistrar.kt"),
        )
        val compactSource = source.replace(Regex("\\s+"), " ")

        assertTrue(
            compactSource.contains(
                """requestOpenSettingsQuery.addSafeHandler("打开设置") { dispatchBridgeAsync("打开设置") { GraphEditorMessage.OpenSettings } }""",
            ),
            "打开设置来自 JCEF query handler；必须异步派发，避免设置模态框阻塞 handler 返回并卡住前端页面。",
        )
        assertFalse(
            compactSource.contains(
                """requestOpenSettingsQuery.addSafeHandler("打开设置") { bridge.dispatch(GraphEditorMessage.OpenSettings) }""",
            ),
            "打开设置不能在 JCEF query handler 中同步执行。",
        )
    }

    @Test
    fun bridgeHandlersThatOpenIdeUiOrWriteFilesReturnBeforeBackendWorkRuns() {
        val source = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserBridgeRegistrar.kt"),
        )
        val compactSource = source.replace(Regex("\\s+"), " ")

        val asynchronousHandlers = listOf(
            HandlerExpectation(
                query = "requestOpenSettingsQuery",
                label = "打开设置",
                message = "GraphEditorMessage.OpenSettings",
                reason = "打开设置会显示 IDE 模态设置窗口。",
            ),
            HandlerExpectation(
                query = "applyCodeDraftsQuery",
                label = "写入全部代码草稿",
                message = "GraphEditorMessage.ApplyCodeDrafts",
                reason = "写入全部代码草稿会写文件并打开写入后的目标文件。",
            ),
            HandlerExpectation(
                query = "applySingleCodeDraftQuery",
                label = "写入单个代码草稿",
                message = "GraphEditorMessage.ApplySingleCodeDraft(draftId)",
                reason = "写入单个代码草稿会写文件并打开写入后的目标文件。",
            ),
            HandlerExpectation(
                query = "openCodeDraftNativeDiffQuery",
                label = "打开代码草稿原生 Diff",
                message = "GraphEditorMessage.OpenCodeDraftNativeDiff(draftId)",
                reason = "原生 Diff 会打开 IDE merge UI。",
            ),
            HandlerExpectation(
                query = "requestDraftNavigationQuery",
                label = "代码草稿导航",
                message = "GraphEditorMessage.RequestDraftNavigation(targetPath)",
                reason = "代码草稿导航会切回 IDE 线程打开编辑器。",
            ),
        )

        asynchronousHandlers.forEach { expectation ->
            assertTrue(
                compactSource.contains(
                    """${expectation.query}.addSafe""",
                ) && compactSource.contains(
                    """dispatchBridgeAsync("${expectation.label}") { ${expectation.message} }""",
                ),
                "${expectation.label} 必须异步派发，避免 JCEF query handler 等待后端 UI/文件操作返回：${expectation.reason}",
            )
            assertFalse(
                expectation.synchronousDispatchPattern().containsMatchIn(compactSource),
                "${expectation.label} 不能在 JCEF query handler 中同步 bridge.dispatch。",
            )
        }
    }

    private data class HandlerExpectation(
        val query: String,
        val label: String,
        val message: String,
        val reason: String,
    ) {
        fun synchronousDispatchPattern(): Regex {
            return Regex(
                Regex.escape(query) +
                    """\.addSafe(?:Payload)?Handler\("""" +
                    Regex.escape(label) +
                    """"[^{}]*\{[^{}]*bridge\.dispatch\(""" +
                    Regex.escape(message) +
                    """\)[^{}]*\}""",
            )
        }
    }
}
