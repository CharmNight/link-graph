package com.charmnight.linkgraph.settings

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.settings.LlmProviderPresets
import com.charmnight.linkgraph.source.AttachedJarEntry
import com.intellij.openapi.application.ApplicationManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphSettingsServiceTest {
    private class FakeSecretStore(
        initialApiKey: String = "",
    ) : LinkGraphSecretStore {
        var storedApiKey: String = initialApiKey
        var loadCount: Int = 0

        override fun loadApiKey(): String {
            loadCount += 1
            return storedApiKey
        }

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
        assertTrue(snapshot.allowClassJarDecompile)
        assertTrue(snapshot.allowExternalLibraryExpansion)
        assertFalse(snapshot.allowJdkLibraryExpansion)
        assertEquals(LinkGraphSettingsState.DEFAULT_MAX_EXTERNAL_CLASS_NODES, snapshot.maxExternalClassNodes)
        assertEquals(emptyList(), snapshot.attachedJars)
    }

    @Test
    fun nonSecretSnapshotDoesNotLoadApiKey() {
        val secretStore = FakeSecretStore(initialApiKey = "secret-key")
        val service = LinkGraphSettingsService(secretStore)

        val snapshot = service.nonSecretSnapshot()

        assertEquals("", snapshot.apiKey)
        assertEquals(0, secretStore.loadCount)
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
    fun allowsOneHourTimeoutAndClampsHigherValues() {
        val service = LinkGraphSettingsService(FakeSecretStore())

        service.update(LinkGraphSettingsState(timeoutSeconds = 3_600))

        assertEquals(3_600, service.snapshot().effectiveTimeoutSeconds())
        assertEquals(3_600, service.state.timeoutSeconds)

        service.update(LinkGraphSettingsState(timeoutSeconds = 7_200))

        assertEquals(3_600, service.snapshot().effectiveTimeoutSeconds())
        assertEquals(3_600, service.state.timeoutSeconds)
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

    @Test
    fun canPreserveStoredApiKeyWhenApplyingNonSecretSettings() {
        val secretStore = FakeSecretStore(initialApiKey = "secret-key")
        val service = LinkGraphSettingsService(secretStore)
        service.loadState(
            LinkGraphPersistentSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                model = "gpt-4.1-mini",
            ),
        )

        service.update(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "",
                model = "gpt-4.1",
            ),
            preserveBlankApiKey = true,
        )

        assertEquals("secret-key", secretStore.storedApiKey)
        assertEquals(0, secretStore.loadCount)
        assertEquals("gpt-4.1", service.state.model)
    }

    @Test
    fun updateDoesNotLoadApiKeyWhenOnlyNonSecretComparisonIsNeeded() {
        val secretStore = FakeSecretStore(initialApiKey = "secret-key")
        val service = LinkGraphSettingsService(secretStore)

        service.update(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://api.example.com/v1",
                apiKey = "next-secret",
                model = "gpt-4.1-mini",
            ),
        )

        assertEquals(0, secretStore.loadCount)
        assertEquals("next-secret", secretStore.storedApiKey)
    }

    @Test
    fun settingsStateToStringRedactsApiKey() {
        val state = LinkGraphSettingsState(
            llmEnabled = true,
            provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
            endpoint = "https://api.example.com/v1",
            apiKey = "secret-key",
            model = "gpt-4.1-mini",
        )

        val text = state.toString()

        assertFalse(text.contains("secret-key"))
        assertTrue(text.contains("apiKey=<redacted>"))
    }

    @Test
    fun persistsAttachedJarSettingsWithNonSecretState() {
        val service = LinkGraphSettingsService(FakeSecretStore())

        service.update(
            LinkGraphSettingsState(
                attachedJars = listOf(
                    AttachedJarEntry(
                        path = " /tmp/external.jar ",
                        sourceJarPath = " /tmp/external-sources.jar ",
                        enabled = true,
                    ),
                ),
                allowClassJarDecompile = false,
                allowExternalLibraryExpansion = false,
                allowJdkLibraryExpansion = true,
                maxExternalClassNodes = 42,
            ),
        )

        val snapshot = service.snapshot()
        assertEquals(1, snapshot.attachedJars.size)
        assertEquals("/tmp/external.jar", snapshot.attachedJars.single().path)
        assertEquals("/tmp/external-sources.jar", snapshot.attachedJars.single().sourceJarPath)
        assertFalse(snapshot.allowClassJarDecompile)
        assertFalse(snapshot.allowExternalLibraryExpansion)
        assertTrue(snapshot.allowJdkLibraryExpansion)
        assertEquals(42, snapshot.maxExternalClassNodes)
        assertEquals(snapshot.attachedJars, service.state.attachedJars)
    }

    @Test
    fun publishesArchitectureIndexSettingsChangedWhenIndexRelevantSettingsChange() {
        val service = LinkGraphSettingsService(FakeSecretStore())
        val events = mutableListOf<Pair<LinkGraphSettingsState, LinkGraphSettingsState>>()
        val connection = ApplicationManager.getApplication().messageBus.connect()
        try {
            connection.subscribe(
                LinkGraphSettingsChangedNotifier.TOPIC,
                object : LinkGraphSettingsChangedNotifier {
                    override fun onArchitectureIndexSettingsChanged(
                        before: LinkGraphSettingsState,
                        after: LinkGraphSettingsState,
                    ) {
                        events += before to after
                    }
                },
            )

            service.update(LinkGraphSettingsState(model = "gpt-no-index-change"))
            service.update(
                service.snapshot().copy(
                    allowJdkLibraryExpansion = true,
                    attachedJars = listOf(AttachedJarEntry(path = "/tmp/external.jar")),
                ),
            )

            assertEquals(1, events.size)
            assertFalse(events.single().first.allowJdkLibraryExpansion)
            assertTrue(events.single().second.allowJdkLibraryExpansion)
        } finally {
            connection.disconnect()
        }
    }
}
