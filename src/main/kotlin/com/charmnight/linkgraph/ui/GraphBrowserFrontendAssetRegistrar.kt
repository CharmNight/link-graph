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

    fun registerHandlers() {
        browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)
    }
}

internal class GraphBrowserFrontendAssetRequestHandler(
    entryUrl: String,
    private val frontendAssetLoader: FrontendAssetLoader,
    private val entryHtmlProvider: () -> String,
) : CefRequestHandlerAdapter() {
    private val entryUri = URI(entryUrl)
    private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
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

    internal fun assetReference(url: String?): String? {
        return (resourceReference(url) as? FrontendResourceReference.Asset)?.assetReference
    }

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

    private fun isEntryRequest(uri: URI): Boolean {
        val path = uri.path.orEmpty().trim('/')
        val entryPath = entryUri.path.orEmpty().trim('/')
        return path.isBlank() || path == entryPath
    }
}

internal sealed interface FrontendResourceReference {
    data object Entry : FrontendResourceReference

    data class Asset(val assetReference: String) : FrontendResourceReference
}

private class FrontendAssetResourceHandler(
    private val status: Int,
    private val statusText: String,
    private val asset: FrontendAsset,
) : CefResourceHandler {
    private var offset: Int = 0

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        callback.Continue()
        return true
    }

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

    override fun cancel() {
        offset = asset.bytes.size
    }

    companion object {
        fun ok(asset: FrontendAsset): FrontendAssetResourceHandler =
            FrontendAssetResourceHandler(
                status = 200,
                statusText = "OK",
                asset = asset,
            )

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
