package com.charmnight.linkgraph.settings

import com.charmnight.linkgraph.llm.LlmProviderPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphSettingsServiceTest {
    @Test
    fun exposesExpectedDefaultSettings() {
        val snapshot = LinkGraphSettingsService().snapshot()

        assertFalse(snapshot.llmEnabled)
        assertEquals(LlmProviderType.MOCK, snapshot.providerType())
        assertEquals("", snapshot.normalizedEndpoint())
        assertEquals("", snapshot.apiKey)
        assertEquals(LinkGraphSettingsState.DEFAULT_MODEL, snapshot.model)
        assertEquals(LinkGraphSettingsState.DEFAULT_TIMEOUT_SECONDS, snapshot.effectiveTimeoutSeconds())
        assertEquals(LinkGraphSettingsState.DEFAULT_TEMPERATURE, snapshot.effectiveTemperature())
    }

    @Test
    fun sanitizesAndPersistsSettingsState() {
        val service = LinkGraphSettingsService()

        service.loadState(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = " https://api.example.com/v1/ ",
                apiKey = " secret-key ",
                model = " gpt-4.1-mini ",
                timeoutSeconds = 0,
                temperature = 1.7,
            ),
        )

        val snapshot = service.snapshot()
        assertTrue(snapshot.llmEnabled)
        assertEquals(LlmProviderType.OPENAI_COMPATIBLE, snapshot.providerType())
        assertEquals("https://api.example.com/v1", snapshot.normalizedEndpoint())
        assertEquals("secret-key", snapshot.apiKey.trim())
        assertEquals("gpt-4.1-mini", snapshot.model.trim())
        assertEquals(30, snapshot.effectiveTimeoutSeconds())
        assertEquals(1.0, snapshot.effectiveTemperature())
        assertTrue(service.isRemoteGenerationReady())
    }

    @Test
    fun remoteGenerationRequiresCompleteConnectionInfo() {
        val service = LinkGraphSettingsService()

        service.loadState(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "",
                apiKey = "",
                model = "",
            ),
        )

        assertFalse(service.isRemoteGenerationReady())
    }

    @Test
    fun usesMiniMaxPresetDefaultsWhenEndpointAndModelAreBlank() {
        val service = LinkGraphSettingsService()

        service.loadState(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MINIMAX_ANTHROPIC.id,
                endpoint = "",
                apiKey = " secret-key ",
                model = "",
            ),
        )

        val snapshot = service.snapshot()
        assertEquals(LlmProviderPresets.MINIMAX_ANTHROPIC.id, snapshot.providerPreset().id)
        assertEquals("https://api.minimax.io/anthropic", snapshot.effectiveEndpoint())
        assertEquals("MiniMax-M2.7", snapshot.effectiveModel())
        assertTrue(service.isRemoteGenerationReady())
    }

    @Test
    fun usesOpenAiResponsesPresetDefaultsWhenEndpointAndModelAreBlank() {
        val service = LinkGraphSettingsService()

        service.loadState(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_RESPONSES.id,
                endpoint = "",
                apiKey = " secret-key ",
                model = "",
            ),
        )

        val snapshot = service.snapshot()
        assertEquals(LlmProviderPresets.OPENAI_RESPONSES.id, snapshot.providerPreset().id)
        assertEquals("https://api.openai.com/v1", snapshot.effectiveEndpoint())
        assertEquals("gpt-4.1-mini", snapshot.effectiveModel())
        assertTrue(service.isRemoteGenerationReady())
    }
}
