package com.charmnight.linkgraph.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphBrowserFrontendAssetRegistrarTest {
    @Test
    fun requestHandlerMapsOnlySameOriginEntryAndStaticAssets() {
        val handler = GraphBrowserFrontendAssetRequestHandler(
            entryUrl = GraphBrowserPanel.INLINE_ENTRY_URL,
            frontendAssetLoader = object : FrontendAssetLoader {
                override fun loadEntryHtml(): String = "<html></html>"

                override fun loadAsset(assetReference: String): FrontendAsset? = null
            },
            entryHtmlProvider = { "<html><body>entry</body></html>" },
        )

        assertEquals(
            "assets/index-abc123.js",
            handler.assetReference("https://linkgraph.local/assets/index-abc123.js"),
        )
        assertEquals(
            "assets/elk-worker.min-abc123.js",
            handler.assetReference("https://linkgraph.local/assets/elk-worker.min-abc123.js?worker_file&type=module"),
        )
        assertEquals(
            "assets/index-abc123.js",
            handler.assetReference("https://linkgraph.local:443/assets/index-abc123.js"),
        )
        assertEquals(
            "assets/space name.js",
            handler.assetReference("https://linkgraph.local/assets/space%20name.js"),
        )
        assertEquals(
            "assets/percent%name.js",
            handler.assetReference("https://linkgraph.local/assets/percent%25name.js"),
        )
        assertTrue(handler.resourceReference("https://linkgraph.local/index.html") is FrontendResourceReference.Entry)
        assertTrue(handler.resourceReference("https://linkgraph.local/") is FrontendResourceReference.Entry)
        assertNull(handler.assetReference("https://linkgraph.local/favicon.ico"))
        assertNull(handler.assetReference("https://linkgraph.local/assets/%2e%2e/index.html"))
        assertNull(handler.assetReference("https://linkgraph.local/assets/%2e%2e/%2e%2e/secrets.txt"))
        assertNull(handler.assetReference("https://example.com/assets/index-abc123.js"))
        assertNull(handler.assetReference("http://linkgraph.local/assets/index-abc123.js"))
        assertNull(handler.assetReference("https://linkgraph.local:8443/assets/index-abc123.js"))
    }
}
