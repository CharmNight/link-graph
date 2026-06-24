package com.charmnight.linkgraph.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ExecutionException

/**
 * 远程 LLM API Key 的安全存储抽象。
 *
 * 把密钥从普通持久化 XML 中抽离出来，专门交给 IDE 的安全存储，
 * 避免敏感信息明文落盘或在日志/快照中被一起导出。
 */
interface LinkGraphSecretStore {
    /**
     * 读取已保存的 API Key；未配置时返回空字符串而非 null，方便上层链式处理。
     */
    fun loadApiKey(): String

    /**
     * 保存或覆盖 API Key。
     */
    fun saveApiKey(apiKey: String)

    /**
     * 清除已保存的 API Key。
     */
    fun clearApiKey()
}

/**
 * 基于 IntelliJ PasswordSafe 的密钥存储实现。
 */
class PasswordSafeLinkGraphSecretStore : LinkGraphSecretStore {
    /** 在 PasswordSafe 中标识本插件远程 LLM 密钥的凭证属性。 */
    private val attributes = CredentialAttributes(
        generateServiceName("LinkGraph", "remote-llm-api-key"),
    )

    /**
     * 从 PasswordSafe 读取密钥并以字符串返回。
     */
    override fun loadApiKey(): String {
        return runPasswordSafeOperation {
            PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
        }
    }

    /**
     * 把 API Key 包装成凭证写入 PasswordSafe。
     */
    override fun saveApiKey(apiKey: String) {
        runPasswordSafeOperation {
            PasswordSafe.instance.set(attributes, Credentials("LinkGraph Remote LLM", apiKey))
        }
    }

    /**
     * 通过写入 null 凭证的方式清除已有密钥。
     */
    override fun clearApiKey() {
        runPasswordSafeOperation {
            PasswordSafe.instance.set(attributes, null)
        }
    }

    /**
     * 包装 PasswordSafe 操作。
     *
     * PasswordSafe 在 EDT 上可能触发只读锁冲突，因此一旦发现当前线程是 EDT，
     * 就把操作转移到后台线程池中执行并同步等待结果，避免死锁或异常。
     */
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
