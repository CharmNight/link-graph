package com.charmnight.linkgraph.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * 负责将 Link Graph 的远程 LLM API Key 存入 IDE 安全存储。
 */
interface LinkGraphSecretStore {
    fun loadApiKey(): String

    fun saveApiKey(apiKey: String)

    fun clearApiKey()
}

class PasswordSafeLinkGraphSecretStore : LinkGraphSecretStore {
    private val attributes = CredentialAttributes(
        generateServiceName("LinkGraph", "remote-llm-api-key"),
    )

    override fun loadApiKey(): String {
        return PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
    }

    override fun saveApiKey(apiKey: String) {
        PasswordSafe.instance.set(attributes, Credentials("LinkGraph Remote LLM", apiKey))
    }

    override fun clearApiKey() {
        PasswordSafe.instance.set(attributes, null)
    }
}
