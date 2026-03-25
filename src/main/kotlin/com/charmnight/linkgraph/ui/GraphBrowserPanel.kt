package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphJson
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

class GraphBrowserPanel(
    project: Project,
) : JPanel(BorderLayout()) {
    private val bridge: GraphEditorBridge = GraphEditorBridge(project)
    private val pageRenderer = GraphEditorPageRenderer()
    private val entryUrl: String = resolveEntryUrl()
    private val browser: JBCefBrowser? = createBrowser()
    private val exportMermaidQuery: JBCefJSQuery? = browser?.let(JBCefJSQuery::create)
    private val requestSyncPreviewQuery: JBCefJSQuery? = browser?.let(JBCefJSQuery::create)
    private val graphChangedQuery: JBCefJSQuery? = browser?.let(JBCefJSQuery::create)

    init {
        browser?.let(::configureBrowser)
        add(browser?.component ?: createFallbackView(entryUrl), BorderLayout.CENTER)
        bridge.onFrontendLoaded(entryUrl)
    }

    fun currentEntryUrl(): String = entryUrl

    fun bridge(): GraphEditorBridge = bridge

    fun syncFromProjectState() {
        val entryHtml = loadEntryHtml() ?: return
        browser?.loadHTML(pageRenderer.render(entryHtml, bridge.currentState()), entryUrl)
    }

    private fun createBrowser(): JBCefBrowser? {
        if (!JBCefApp.isSupported()) {
            return null
        }
        return runCatching {
            JBCefBrowser()
        }.getOrNull()
    }

    private fun configureBrowser(browser: JBCefBrowser) {
        exportMermaidQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.ExportMermaid)
            JBCefJSQuery.Response("ok")
        }
        requestSyncPreviewQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.RequestSyncPreview)
            JBCefJSQuery.Response("ok")
        }
        graphChangedQuery?.addHandler { payload ->
            runCatching {
                bridge.dispatch(GraphEditorMessage.GraphChanged(GraphJson.fromJson(payload)))
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "graphChanged failed")
            }
        }
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    httpStatusCode: Int,
                ) {
                    if (!frame.isMain) {
                        return
                    }
                    cefBrowser.executeJavaScript(buildBridgeScript(), cefBrowser.url, 0)
                }
            },
            browser.cefBrowser,
        )
        syncFromProjectState()
    }

    private fun buildBridgeScript(): String {
        return """
            window.linkGraphBridge = {
              exportMermaid: () => { ${exportMermaidQuery?.inject("'exportMermaid'") ?: ""} },
              requestSyncPreview: () => { ${requestSyncPreviewQuery?.inject("'requestSyncPreview'") ?: ""} },
              graphChanged: (payload) => { ${graphChangedQuery?.inject("JSON.stringify(payload)") ?: ""} }
            };
        """.trimIndent()
    }

    private fun createFallbackView(entryUrl: String): JComponent {
        return JEditorPane(
            "text/html",
            """
            <html>
              <body>
                <h2>Link Graph Editor Shell</h2>
                <p>Frontend entry: $entryUrl</p>
              </body>
            </html>
            """.trimIndent(),
        ).apply {
            isEditable = false
        }
    }

    private fun loadEntryHtml(): String? {
        return javaClass.getResource("/linkgraph/index.html")
            ?.readText()
            ?: javaClass.getResource("/linkgraph/editor-shell.html")?.readText()
    }

    private fun resolveEntryUrl(): String {
        return javaClass.getResource("/linkgraph/index.html")?.toExternalForm()
            ?: javaClass.getResource("/linkgraph/editor-shell.html")?.toExternalForm()
            ?: FALLBACK_ENTRY_URL
    }

    companion object {
        const val FALLBACK_ENTRY_URL: String = "linkgraph://shell/index.html"
    }
}
