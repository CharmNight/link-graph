package com.charmnight.linkgraph.ui

import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.URI
import kotlin.math.min

/**
 * 把 Vite 拆出的静态资源挂到 JCEF 页面同源地址下，供 modulepreload、dynamic import 和 Worker 加载。
 */
internal class GraphBrowserFrontendAssetRegistrar(
    private val browser: JBCefBrowser,
    private val frontendAssetLoader: FrontendAssetLoader,
    entryUrl: String,
    entryHtmlProvider: () -> String,
) {
    private val requestHandler = GraphBrowserFrontendAssetRequestHandler(
        entryUrl = entryUrl,
        frontendAssetLoader = frontendAssetLoader,
        entryHtmlProvider = entryHtmlProvider,
    )

    /**
     * 将构造好的请求处理器挂载到 JCEF 客户端上，使其能在后续请求中被调度到。
     */
    fun registerHandlers() {
        browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)
    }
}

/**
 * JCEF 请求处理器实现，负责拦截同源静态资源请求并直接返回前端打包产物，
 * 避免这些请求走默认网络栈导致 404 或跨域问题。
 */
internal class GraphBrowserFrontendAssetRequestHandler(
    entryUrl: String,
    private val frontendAssetLoader: FrontendAssetLoader,
    private val entryHtmlProvider: () -> String,
) : CefRequestHandlerAdapter() {
    // 入口页面同源的基准 URI，用于判定哪些请求属于本注册器负责的范围内
    private val entryUri = URI(entryUrl)
    // 实际负责派发前端静态资源字节流的资源处理器
    private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
        /**
         * 根据请求的 URL 判别其应当返回入口 HTML 还是某个具体静态资源，
         * 并构造对应的 CEF 资源处理器实例。
         */
        override fun getResourceHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
        ): CefResourceHandler? {
            return when (val resourceReference = resourceReference(request.getURL())) {
                FrontendResourceReference.Entry ->
                    FrontendAssetResourceHandler.ok(
                        FrontendAsset(
                            bytes = entryHtmlProvider().toByteArray(Charsets.UTF_8),
                            mimeType = "text/html",
                        ),
                    )
                is FrontendResourceReference.Asset -> {
                    val asset = frontendAssetLoader.loadAsset(resourceReference.assetReference)
                        ?: return FrontendAssetResourceHandler.notFound(resourceReference.assetReference)
                    FrontendAssetResourceHandler.ok(asset)
                }
                null -> null
            }
        }
    }

    /**
     * 仅当请求落在同源入口或资源范围内时返回自定义资源处理器，
     * 同时关闭默认处理，避免被 IntelliJ 内置 resolver 抢先处理。
     */
    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef,
    ): CefResourceRequestHandler? {
        return if (resourceReference(request.getURL()) != null) {
            disableDefaultHandling.set(true)
            resourceRequestHandler
        } else {
            null
        }
    }

    /**
     * 对外暴露的便捷方法，仅当 URL 指向某个具体前端资源时返回该资源的相对引用，
     * 入口请求或不相关请求均返回 null。
     */
    internal fun assetReference(url: String?): String? {
        return (resourceReference(url) as? FrontendResourceReference.Asset)?.assetReference
    }

    /**
     * 将一个 URL 归类为入口请求、具体资源请求或与本注册器无关的请求。
     * 同源校验通过后才会进一步区分入口与资源两种情况。
     */
    internal fun resourceReference(url: String?): FrontendResourceReference? {
        if (url.isNullOrBlank()) {
            return null
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!FrontendAssetReferencePolicy.isSameOrigin(uri, entryUri)) {
            return null
        }
        if (isEntryRequest(uri)) {
            return FrontendResourceReference.Entry
        }
        return FrontendAssetReferencePolicy
            .assetReferenceFromSameOriginUrl(url, entryUri)
            ?.let(FrontendResourceReference::Asset)
    }

    /**
     * 判断给定 URI 是否应被视为入口请求，例如根路径或与入口路径完全一致时。
     */
    private fun isEntryRequest(uri: URI): Boolean {
        val path = uri.path.orEmpty().trim('/')
        val entryPath = entryUri.path.orEmpty().trim('/')
        return path.isBlank() || path == entryPath
    }
}

/**
 * 对前端请求目标的密封分类，区分入口页面与具体静态资源两种情况。
 */
internal sealed interface FrontendResourceReference {
    /** 入口页面（index.html）请求 */
    data object Entry : FrontendResourceReference

    /** 指向某个具体静态资源（JS / CSS 等）的请求 */
    data class Asset(val assetReference: String) : FrontendResourceReference
}

/**
 * 把一段内存中的前端资源字节流包装成 CEF 可读取的资源处理器，
 * 支持以指定的 HTTP 状态码与 MIME 类型返回响应内容。
 */
private class FrontendAssetResourceHandler(
    private val status: Int,
    private val statusText: String,
    private val asset: FrontendAsset,
) : CefResourceHandler {
    // 当前已读取到的字节偏移量，用于分块返回给 CEF
    private var offset: Int = 0

    /**
     * 接到请求时立即回调 Continue，触发后续响应头与正文的派发。
     */
    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        callback.Continue()
        return true
    }

    /**
     * 写出响应头，包含状态码、状态文本、MIME 类型以及正文总长度。
     */
    override fun getResponseHeaders(
        response: CefResponse,
        responseLength: IntRef,
        redirectUrl: StringRef,
    ) {
        response.setStatus(status)
        response.setStatusText(statusText)
        response.setMimeType(asset.mimeType)
        responseLength.set(asset.bytes.size)
    }

    /**
     * 按需读取剩余字节并写入 CEF 提供的缓冲区，全部读完时返回 false 表示结束。
     */
    override fun readResponse(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefCallback,
    ): Boolean {
        if (offset >= asset.bytes.size) {
            bytesRead.set(0)
            return false
        }
        val readCount = min(bytesToRead, min(dataOut.size, asset.bytes.size - offset))
        System.arraycopy(asset.bytes, offset, dataOut, 0, readCount)
        offset += readCount
        bytesRead.set(readCount)
        return true
    }

    /**
     * 取消请求时直接将偏移推到末尾，使后续读取返回空数据。
     */
    override fun cancel() {
        offset = asset.bytes.size
    }

    companion object {
        /**
         * 构造一个 200 成功响应，用于返回找到的前端资源内容。
         */
        fun ok(asset: FrontendAsset): FrontendAssetResourceHandler =
            FrontendAssetResourceHandler(
                status = 200,
                statusText = "OK",
                asset = asset,
            )

        /**
         * 构造一个 404 响应，正文为提示信息，用于资源未命中的情况。
         */
        fun notFound(assetReference: String): FrontendAssetResourceHandler =
            FrontendAssetResourceHandler(
                status = 404,
                statusText = "Not Found",
                asset = FrontendAsset(
                    bytes = "未找到前端资源: $assetReference".toByteArray(Charsets.UTF_8),
                    mimeType = "text/plain",
                ),
            )
    }
}
