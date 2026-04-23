package com.charmnight.linkgraph.ui

import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter

internal class GraphBrowserLifecycle(
    private val browser: JBCefBrowser,
    private val onMainFrameLoadStarted: (CefBrowser) -> Unit,
    private val onMainFrameLoadEnded: (CefBrowser, Int) -> Unit,
    private val onMainFrameLoadError: (CefLoadHandler.ErrorCode, String, String) -> Unit,
    private val onConsoleMessage: (org.cef.CefSettings.LogSeverity?, String?, String?, Int) -> Unit,
) {
    fun install() {
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadStart(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    transitionType: org.cef.network.CefRequest.TransitionType?,
                ) {
                    if (!frame.isMain) {
                        return
                    }
                    onMainFrameLoadStarted(cefBrowser)
                }

                override fun onLoadEnd(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    httpStatusCode: Int,
                ) {
                    if (!frame.isMain) {
                        return
                    }
                    onMainFrameLoadEnded(cefBrowser, httpStatusCode)
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    errorCode: CefLoadHandler.ErrorCode,
                    errorText: String,
                    failedUrl: String,
                ) {
                    if (!frame.isMain) {
                        return
                    }
                    onMainFrameLoadError(errorCode, errorText, failedUrl)
                }
            },
            browser.cefBrowser,
        )
        browser.jbCefClient.addDisplayHandler(
            object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: org.cef.CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int,
                ): Boolean {
                    onConsoleMessage(level, message, source, line)
                    return false
                }
            },
            browser.cefBrowser,
        )
    }
}
