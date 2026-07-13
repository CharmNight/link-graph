package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteLlmProviderSupportTest {
    @Test
    fun requestFactoryRejectsSourceClassificationsWithoutConsent() {
        val settings = remoteSettings(allowRemoteSourceContext = false)
        val connection = requireNotNull(settings.remoteConnectionOrNull())

        val error = assertFailsWith<IllegalArgumentException> {
            RemoteLlmRequestFactory.create(
                connection = connection,
                settings = settings,
                content = RemotePromptContent(
                    systemPrompt = "system",
                    userPrompt = "SECRET_SOURCE_MARKER",
                    classifications = setOf(RemoteDataClassification.SOURCE_CODE),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("source", ignoreCase = true))
    }

    @Test
    fun requestFactoryAllowsSourceClassificationsWithConsent() {
        val settings = remoteSettings(allowRemoteSourceContext = true)
        val request = RemoteLlmRequestFactory.create(
            connection = requireNotNull(settings.remoteConnectionOrNull()),
            settings = settings,
            content = RemotePromptContent(
                systemPrompt = "system",
                userPrompt = "SECRET_SOURCE_MARKER",
                classifications = setOf(RemoteDataClassification.DECOMPILED_CODE),
            ),
        )

        assertEquals("SECRET_SOURCE_MARKER", request.userPrompt)
    }

    @Test
    fun disabledLlmDoesNotEnterRemoteProviderPath() {
        val settings = LinkGraphSettingsState(
            llmEnabled = false,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            endpoint = "https://api.example.com/v1",
            apiKey = "token",
            model = "gpt-4.1-mini",
        )

        assertFalse(settings.usesRemoteProvider())
        assertNull(settings.remoteConnectionOrNull())
    }

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

    private fun remoteSettings(allowRemoteSourceContext: Boolean): LinkGraphSettingsState =
        LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            endpoint = "https://api.example.com/v1",
            apiKey = "token",
            model = "gpt-4.1-mini",
            allowRemoteSourceContext = allowRemoteSourceContext,
        )
}
