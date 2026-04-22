package com.charmnight.linkgraph.settings

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.llm.LlmProviderPreset
import com.charmnight.linkgraph.llm.LlmProviderPresets
import com.charmnight.linkgraph.llm.remoteConnectionOrNull
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 一期内置的 LLM provider 类型。
 * `MOCK` 表示本地规则化计划，`OPENAI_COMPATIBLE` 表示请求兼容 OpenAI Chat Completions 的远程服务。
 */
enum class LlmProviderType {
    /** 表示本地 Mock 或规则模式。 */
    MOCK {
        override fun toString(): String = LinkGraphBundle.message("settings.link-graph.provider.mock")
    },
    /** 表示远程 OpenAI 兼容模式。 */
    OPENAI_COMPATIBLE {
        override fun toString(): String = LinkGraphBundle.message("settings.link-graph.provider.openai-compatible")
    },
}

/**
 * Link Graph 持久化设置快照。
 * 这里集中定义默认值，避免设置页、服务逻辑和文档各写一套。
 */
data class LinkGraphSettingsState(
    /** 标记是否启用 LLM 功能。 */
    var llmEnabled: Boolean = DEFAULT_LLM_ENABLED,
    /** 保存当前 provider 标识。 */
    var provider: String = DEFAULT_PROVIDER_ID,
    /** 保存用户填写的接口地址。 */
    var endpoint: String = "",
    /** 保存用户填写的 API 密钥。 */
    var apiKey: String = "",
    /** 保存用户填写的模型名。 */
    var model: String = DEFAULT_MODEL,
    /** 保存超时时间，单位为秒。 */
    var timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** 保存采样温度。 */
    var temperature: Double = DEFAULT_TEMPERATURE,
) {
    /**
     * 兼容旧逻辑的二分类：本地规则 vs 远程。
     * 新逻辑应优先使用 [providerPreset] 获取具体预设。
     */
    fun providerType(): LlmProviderType {
        return if (providerPreset().isRemote) LlmProviderType.OPENAI_COMPATIBLE else LlmProviderType.MOCK
    }

    /**
     * 解析当前 provider 预设。
     */
    fun providerPreset(): LlmProviderPreset {
        return LlmProviderPresets.resolve(provider)
    }

    /**
     * 返回去掉尾部斜杠后的接口地址。
     */
    fun normalizedEndpoint(): String {
        return endpoint.trim().removeSuffix("/")
    }

    /**
     * 返回最终生效的接口地址。
     */
    fun effectiveEndpoint(): String {
        return normalizedEndpoint().ifBlank { providerPreset().defaultEndpoint.trim().removeSuffix("/") }
    }

    /**
     * 返回最终生效的模型名。
     */
    fun effectiveModel(): String {
        return model.trim().ifBlank { providerPreset().defaultModel }.ifBlank { DEFAULT_MODEL }
    }

    /**
     * 返回落在合法范围内的超时时间。
     */
    fun effectiveTimeoutSeconds(): Int {
        return timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    }

    /**
     * 返回落在合法范围内的温度值。
     */
    fun effectiveTemperature(): Double {
        return temperature.coerceIn(0.0, 1.0)
    }

    /**
     * 产出一份规范化后的设置快照。
     */
    fun sanitized(): LinkGraphSettingsState {
        return copy(
            provider = providerPreset().id,
            endpoint = normalizedEndpoint(),
            apiKey = apiKey.trim(),
            model = effectiveModel(),
            timeoutSeconds = effectiveTimeoutSeconds(),
            temperature = effectiveTemperature(),
        )
    }

    fun toPersistentState(): LinkGraphPersistentSettingsState {
        val sanitized = sanitized()
        return LinkGraphPersistentSettingsState(
            llmEnabled = sanitized.llmEnabled,
            provider = sanitized.provider,
            endpoint = sanitized.endpoint,
            model = sanitized.model,
            timeoutSeconds = sanitized.timeoutSeconds,
            temperature = sanitized.temperature,
        )
    }

    companion object {
        /** 定义 LLM 默认关闭。 */
        const val DEFAULT_LLM_ENABLED: Boolean = false
        /** 定义默认 provider 标识。 */
        const val DEFAULT_PROVIDER_ID: String = "MOCK"
        /** 定义默认 provider 类型。 */
        val DEFAULT_PROVIDER: LlmProviderType = LlmProviderType.MOCK
        /** 定义默认模型名。 */
        const val DEFAULT_MODEL: String = "gpt-4.1-mini"
        /** 定义默认超时时间。 */
        const val DEFAULT_TIMEOUT_SECONDS: Int = 60
        /** 定义最小超时时间。 */
        const val MIN_TIMEOUT_SECONDS: Int = 30
        /** 定义最大超时时间。 */
        const val MAX_TIMEOUT_SECONDS: Int = 300
        /** 定义默认温度。 */
        const val DEFAULT_TEMPERATURE: Double = 0.2
    }
}

/**
 * Link Graph 的 XML 持久化设置模型。
 * 只允许落盘非敏感字段；API Key 必须走 IDE 安全存储。
 */
data class LinkGraphPersistentSettingsState(
    var llmEnabled: Boolean = LinkGraphSettingsState.DEFAULT_LLM_ENABLED,
    var provider: String = LinkGraphSettingsState.DEFAULT_PROVIDER_ID,
    var endpoint: String = "",
    var model: String = LinkGraphSettingsState.DEFAULT_MODEL,
    var timeoutSeconds: Int = LinkGraphSettingsState.DEFAULT_TIMEOUT_SECONDS,
    var temperature: Double = LinkGraphSettingsState.DEFAULT_TEMPERATURE,
) {
    fun sanitized(): LinkGraphPersistentSettingsState {
        return toRuntimeState().toPersistentState()
    }

    fun toRuntimeState(apiKey: String = ""): LinkGraphSettingsState {
        return LinkGraphSettingsState(
            llmEnabled = llmEnabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            timeoutSeconds = timeoutSeconds,
            temperature = temperature,
        ).sanitized()
    }
}

/**
 * 插件级设置存储。
 * 当前主要承载 LLM 生成计划所需的 provider、endpoint、model 和鉴权信息。
 */
@State(
    name = "LinkGraphSettings",
    storages = [Storage("link-graph.xml")],
)
@Service(Service.Level.APP)
class LinkGraphSettingsService : PersistentStateComponent<LinkGraphPersistentSettingsState> {
    /** 保存当前持久化状态。 */
    private var state = LinkGraphPersistentSettingsState()
    private val secretStore: LinkGraphSecretStore

    constructor() {
        secretStore = PasswordSafeLinkGraphSecretStore()
    }

    internal constructor(secretStore: LinkGraphSecretStore) {
        this.secretStore = secretStore
    }

    /**
     * 返回当前持久化状态。
     */
    override fun getState(): LinkGraphPersistentSettingsState = state

    /**
     * 加载外部持久化状态。
     */
    override fun loadState(state: LinkGraphPersistentSettingsState) {
        this.state = state.sanitized()
    }

    /**
     * 返回规范化后的设置快照。
     */
    fun snapshot(): LinkGraphSettingsState = state.toRuntimeState(apiKey = secretStore.loadApiKey())

    /**
     * 更新设置状态。
     */
    fun update(nextState: LinkGraphSettingsState) {
        val sanitized = nextState.sanitized()
        state = sanitized.toPersistentState()
        if (sanitized.apiKey.isBlank()) {
            secretStore.clearApiKey()
        } else {
            secretStore.saveApiKey(sanitized.apiKey)
        }
    }

    /**
     * 判断远程生成所需配置是否已经就绪。
     */
    fun isRemoteGenerationReady(): Boolean {
        return snapshot().remoteConnectionOrNull() != null
    }
}
