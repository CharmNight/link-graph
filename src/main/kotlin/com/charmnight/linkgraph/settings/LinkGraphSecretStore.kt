package com.charmnight.linkgraph.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ExecutionException

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
        return runPasswordSafeOperation {
            PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
        }
    }

    override fun saveApiKey(apiKey: String) {
        runPasswordSafeOperation {
            PasswordSafe.instance.set(attributes, Credentials("LinkGraph Remote LLM", apiKey))
        }
    }

    override fun clearApiKey() {
        runPasswordSafeOperation {
            PasswordSafe.instance.set(attributes, null)
        }
    }

    private fun <T> runPasswordSafeOperation(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            return action()
        }
        val future = AppExecutorUtil.getAppExecutorService().submit<T> {
            action()
        }
        return try {
            future.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}
