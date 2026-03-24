package com.charmnight.linkgraph.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

class GraphBrowserPanel(
    project: Project,
) : JPanel(BorderLayout()) {
    private val bridge: GraphEditorBridge = GraphEditorBridge(project)
    private val entryUrl: String = resolveEntryUrl()
    private val browser: JBCefBrowser? = createBrowser(entryUrl)

    init {
        add(browser?.component ?: createFallbackView(entryUrl), BorderLayout.CENTER)
        bridge.onFrontendLoaded(entryUrl)
    }

    fun currentEntryUrl(): String = entryUrl

    fun bridge(): GraphEditorBridge = bridge

    private fun createBrowser(entryUrl: String): JBCefBrowser? {
        if (!JBCefApp.isSupported()) {
            return null
        }
        return runCatching {
            JBCefBrowser(entryUrl)
        }.getOrNull()
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

    private fun resolveEntryUrl(): String {
        return javaClass.getResource("/linkgraph/editor-shell.html")?.toExternalForm() ?: FALLBACK_ENTRY_URL
    }

    companion object {
        const val FALLBACK_ENTRY_URL: String = "linkgraph://shell/index.html"
    }
}
