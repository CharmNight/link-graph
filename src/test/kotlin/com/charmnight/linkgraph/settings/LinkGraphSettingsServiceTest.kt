package com.charmnight.linkgraph.settings

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.LlmProviderPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphSettingsServiceTest {
    private class FakeSecretStore(
        initialApiKey: String = "",
    ) : LinkGraphSecretStore {
        var storedApiKey: String = initialApiKey

        override fun loadApiKey(): String = storedApiKey

        override fun saveApiKey(apiKey: String) {
            storedApiKey = apiKey
        }

        override fun clearApiKey() {
            storedApiKey = ""
        }
    }

    @Test
    fun exposesExpectedDefaultSettings() {
        val snapshot = LinkGraphSettingsService(FakeSecretStore()).snapshot()

        assertFalse(snapshot.llmEnabled)
        assertEquals(LlmProviderPresets.MOCK.id, snapshot.providerPreset().id)
        assertFalse(snapshot.providerPreset().isRemote)
        assertEquals("", snapshot.normalizedEndpoint())
        assertEquals("", snapshot.apiKey)
        assertEquals(LinkGraphSettingsState.DEFAULT_MODEL, snapshot.model)
        assertEquals(LinkGraphSettingsState.DEFAULT_TIMEOUT_SECONDS, snapshot.effectiveTimeoutSeconds())
        assertEquals(LinkGraphSettingsState.DEFAULT_TEMPERATURE, snapshot.effectiveTemperature())
    }

    @Test
    fun sanitizesRuntimeSnapshotAndSplitsApiKeyFromPersistedState() {
        val secretStore = FakeSecretStore()
        val service = LinkGraphSettingsService(secretStore)

        service.update(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = " https://api.example.com/v1/ ",
                apiKey = " secret-key ",
                model = " gpt-4.1-mini ",
                timeoutSeconds = 0,
                temperature = 1.7,
            ),
        )

        val snapshot = service.snapshot()
        assertTrue(snapshot.llmEnabled)
        assertEquals(LlmProviderPresets.OPENAI_COMPATIBLE.id, snapshot.providerPreset().id)
        assertTrue(snapshot.providerPreset().isRemote)
        assertEquals("https://api.example.com/v1", snapshot.normalizedEndpoint())
        assertEquals("secret-key", snapshot.apiKey.trim())
        assertEquals("gpt-4.1-mini", snapshot.model.trim())
        assertEquals(30, snapshot.effectiveTimeoutSeconds())
        assertEquals(1.0, snapshot.effectiveTemperature())
        assertEquals("secret-key", secretStore.storedApiKey)
        assertEquals(
            LinkGraphPersistentSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                model = "gpt-4.1-mini",
                timeoutSeconds = 30,
                temperature = 1.0,
            ),
            service.state,
        )
        assertTrue(service.isRemoteGenerationReady())
    }

    @Test
    fun remoteGenerationRequiresCompleteConnectionInfo() {
        val service = LinkGraphSettingsService(FakeSecretStore())

        service.loadState(
            LinkGraphPersistentSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "",
                model = "",
            ),
        )

        assertFalse(service.isRemoteGenerationReady())
    }

    @Test
    fun usesMiniMaxPresetDefaultsWhenEndpointAndModelAreBlank() {
        val service = LinkGraphSettingsService(FakeSecretStore(initialApiKey = " secret-key "))

        service.loadState(
            LinkGraphPersistentSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MINIMAX_ANTHROPIC.id,
                endpoint = "",
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
        val service = LinkGraphSettingsService(FakeSecretStore(initialApiKey = " secret-key "))

        service.loadState(
            LinkGraphPersistentSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_RESPONSES.id,
                endpoint = "",
                model = "",
            ),
        )

        val snapshot = service.snapshot()
        assertEquals(LlmProviderPresets.OPENAI_RESPONSES.id, snapshot.providerPreset().id)
        assertEquals("https://api.openai.com/v1", snapshot.effectiveEndpoint())
        assertEquals("gpt-4.1-mini", snapshot.effectiveModel())
        assertTrue(service.isRemoteGenerationReady())
    }

    @Test
    fun clearsStoredApiKeyWhenUpdatedStateLeavesKeyBlank() {
        val secretStore = FakeSecretStore(initialApiKey = "secret-key")
        val service = LinkGraphSettingsService(secretStore)

        service.update(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "",
                model = "gpt-4.1-mini",
            ),
        )

        assertEquals("", secretStore.storedApiKey)
        assertFalse(service.isRemoteGenerationReady())
    }
}
