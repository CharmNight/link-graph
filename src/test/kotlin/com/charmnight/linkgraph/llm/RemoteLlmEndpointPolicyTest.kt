package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteLlmEndpointPolicyTest {
    @Test
    fun acceptsHttpsEndpoint() {
        assertNull(RemoteLlmEndpointPolicy().validationError("https://example.com/v1"))
    }

    @Test
    fun rejectsHttpEndpointByDefault() {
        assertEquals(
            "请求地址必须使用 https://；http:// 仅允许在调试开关开启时使用。",
            RemoteLlmEndpointPolicy().validationError("http://example.com/v1"),
        )
    }

    @Test
    fun allowsHttpEndpointWhenDebugFlagEnabled() {
        assertNull(
            RemoteLlmEndpointPolicy(allowInsecureHttp = true).validationError("http://example.com/v1"),
        )
    }
}
