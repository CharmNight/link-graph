package com.charmnight.linkgraph.ui

import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter

/**
 * JCEF 浏览器生命周期钩子封装。
 *
 * 把 JCEF 的 LoadHandler / DisplayHandler 回调包装为更易用的回调集合：
 * - 主框架开始加载；
 * - 主框架加载完成（带 HTTP 状态码）；
 * - 主框架加载失败；
 * - 控制台消息。
 *
 * 只关心主框架（isMain=true）的事件，忽略子框架（iframe）。
 * 这层封装让上层不必直接接触 CEF API，便于测试与替换。
 *
 * @param browser JCEF 浏览器实例
 * @param onMainFrameLoadStarted 主框架开始加载回调
 * @param onMainFrameLoadEnded 主框架加载完成回调
 * @param onMainFrameLoadError 主框架加载失败回调
 * @param onConsoleMessage 控制台消息回调
 */
internal class GraphBrowserLifecycle(
    private val browser: JBCefBrowser,
    private val onMainFrameLoadStarted: (CefBrowser) -> Unit,
    private val onMainFrameLoadEnded: (CefBrowser, Int) -> Unit,
    private val onMainFrameLoadError: (CefLoadHandler.ErrorCode, String, String) -> Unit,
    private val onConsoleMessage: (org.cef.CefSettings.LogSeverity?, String?, String?, Int) -> Unit,
) {
    /**
     * 把回调安装到浏览器上。
     * 安装后浏览器的事件会被路由到本类构造时传入的回调。
     */
    fun install() {
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                /** 主框架开始加载。 */
                override fun onLoadStart(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    transitionType: org.cef.network.CefRequest.TransitionType?,
                ) {
                    // 只关心主框架，忽略 iframe
                    if (!frame.isMain) {
                        return
                    }
                    onMainFrameLoadStarted(cefBrowser)
                }

                /** 主框架加载完成。 */
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

                /** 主框架加载失败。 */
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
                /** 控制台消息：把日志级别、消息、来源、行号转交给上层。 */
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: org.cef.CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int,
                ): Boolean {
                    onConsoleMessage(level, message, source, line)
                    // 返回 false 让 CEF 继续默认处理（例如打印到控制台）
                    return false
                }
            },
            browser.cefBrowser,
        )
    }
}
