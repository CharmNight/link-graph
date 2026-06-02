package com.charmnight.linkgraph.settings

import com.charmnight.linkgraph.llm.LlmProviderPresets
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LinkGraphSettingsConfigurableTest : BasePlatformTestCase() {
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

    fun testResetAndIsModifiedDoNotSynchronouslyLoadApiKey() {
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
        val configurable = LinkGraphSettingsConfigurable(
            serviceProvider = { service },
            loadSecretSnapshotAsync = { _, _ -> },
        )
        try {
            configurable.createComponent()

            assertEquals(0, secretStore.loadCount)
            assertFalse(configurable.isModified())
            assertEquals(0, secretStore.loadCount)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
