package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteLlmProviderSupportTest {
    @Test
    fun rejectsHttpEndpointByDefaultWhenBuildingRemoteConnection() {
        val connection = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            endpoint = "http://example.com/v1",
            apiKey = "token",
            model = "gpt-4.1-mini",
        ).remoteConnectionOrNull()

        assertNull(connection)
    }

    @Test
    fun allowsHttpEndpointOnlyWhenDebugPolicyEnabled() {
        val connection = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            endpoint = "http://example.com/v1",
            apiKey = "token",
            model = "gpt-4.1-mini",
        ).remoteConnectionOrNull(
            endpointPolicy = RemoteLlmEndpointPolicy(allowInsecureHttp = true),
        )

        assertNotNull(connection)
        assertEquals("http://example.com/v1", connection.endpoint)
    }

    @Test
    fun setupHintMarksHttpEndpointAsInsecure() {
        val message = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            endpoint = "http://example.com/v1",
            apiKey = "token",
            model = "gpt-4.1-mini",
        ).remoteLlmSetupHint("本地规则问答")

        assertTrue(message.contains("请求地址必须使用 https://"))
    }
}
