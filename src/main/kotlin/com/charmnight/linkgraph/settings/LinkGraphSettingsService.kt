package com.charmnight.linkgraph.settings

import com.charmnight.linkgraph.settings.LlmProviderPreset
import com.charmnight.linkgraph.settings.LlmProviderPresets
import com.charmnight.linkgraph.llm.remoteConnectionOrNull
import com.charmnight.linkgraph.source.AttachedJarEntry
import com.charmnight.linkgraph.source.AttachedJarSettingsValidator
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

/**
 * Link Graph 持久化设置快照。
 * 这里集中定义默认值，避免设置页、服务逻辑和文档各写一套。
 */
data class LinkGraphSettingsState(
    /** 标记是否启用 LLM 功能。 */
    var llmEnabled: Boolean = DEFAULT_LLM_ENABLED,
    /** 保存当前 provider preset 标识。 */
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
    /** 用户显式附加的外部 class/source JAR。 */
    var attachedJars: List<AttachedJarEntry> = emptyList(),
    /** 是否允许对 class JAR 返回反编译来源标记。 */
    var allowClassJarDecompile: Boolean = DEFAULT_ALLOW_CLASS_JAR_DECOMPILE,
    /** 是否允许架构索引展开外部库类。 */
    var allowExternalLibraryExpansion: Boolean = DEFAULT_ALLOW_EXTERNAL_LIBRARY_EXPANSION,
    /** 是否允许架构索引展开 JDK 类。 */
    var allowJdkLibraryExpansion: Boolean = DEFAULT_ALLOW_JDK_LIBRARY_EXPANSION,
    /** 外部类节点预算。 */
    var maxExternalClassNodes: Int = DEFAULT_MAX_EXTERNAL_CLASS_NODES,
    /** runtime 临时下压的远程请求超时，不持久化到设置页。 */
    var runtimeTimeoutSecondsOverride: Int? = null,
) {
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
        return runtimeTimeoutSecondsOverride
            ?.coerceAtLeast(1)
            ?: timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
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
            attachedJars = attachedJars
                .map(AttachedJarEntry::normalized)
                .filter { entry -> entry.path.isNotBlank() }
                .distinctBy { entry -> entry.path to entry.sourceJarPath },
            allowClassJarDecompile = allowClassJarDecompile,
            allowExternalLibraryExpansion = allowExternalLibraryExpansion,
            allowJdkLibraryExpansion = allowJdkLibraryExpansion,
            maxExternalClassNodes = maxExternalClassNodes.coerceIn(MIN_EXTERNAL_CLASS_NODES, MAX_EXTERNAL_CLASS_NODES),
            runtimeTimeoutSecondsOverride = runtimeTimeoutSecondsOverride?.coerceAtLeast(1),
        )
    }

    /** 校验当前附加 JAR 配置是否合法，返回包含错误信息的校验结果。 */
    fun attachedJarValidation() = AttachedJarSettingsValidator.validate(attachedJars)

    /**
     * 生成一份用于持久化落盘的状态。
     * 内部先做规范化，再剔除 API Key 等敏感字段，避免明文写入 XML。
     */
    fun toPersistentState(): LinkGraphPersistentSettingsState {
        val sanitized = sanitized()
        return LinkGraphPersistentSettingsState(
            llmEnabled = sanitized.llmEnabled,
            provider = sanitized.provider,
            endpoint = sanitized.endpoint,
            model = sanitized.model,
            timeoutSeconds = sanitized.timeoutSeconds,
            temperature = sanitized.temperature,
            attachedJars = sanitized.attachedJars.map(AttachedJarEntry::normalized).toMutableList(),
            allowClassJarDecompile = sanitized.allowClassJarDecompile,
            allowExternalLibraryExpansion = sanitized.allowExternalLibraryExpansion,
            allowJdkLibraryExpansion = sanitized.allowJdkLibraryExpansion,
            maxExternalClassNodes = sanitized.maxExternalClassNodes,
        )
    }

    /**
     * 返回用于日志的字符串表示。
     * 对 API Key 做脱敏处理，仅展示空或已设置的占位符。
     */
    override fun toString(): String {
        return "LinkGraphSettingsState(" +
            "llmEnabled=$llmEnabled, " +
            "provider=$provider, " +
            "endpoint=$endpoint, " +
            "apiKey=${if (apiKey.isBlank()) "<empty>" else "<redacted>"}, " +
            "model=$model, " +
            "timeoutSeconds=$timeoutSeconds, " +
            "runtimeTimeoutSecondsOverride=$runtimeTimeoutSecondsOverride, " +
            "temperature=$temperature, " +
            "attachedJars=${attachedJars.size}, " +
            "allowClassJarDecompile=$allowClassJarDecompile, " +
            "allowExternalLibraryExpansion=$allowExternalLibraryExpansion, " +
            "allowJdkLibraryExpansion=$allowJdkLibraryExpansion, " +
            "maxExternalClassNodes=$maxExternalClassNodes" +
            ")"
    }

    companion object {
        /** 定义 LLM 默认关闭。 */
        const val DEFAULT_LLM_ENABLED: Boolean = false
        /** 定义默认 preset 标识。 */
        const val DEFAULT_PROVIDER_ID: String = "MOCK"
        /** 定义默认模型名。 */
        const val DEFAULT_MODEL: String = "gpt-4.1-mini"
        /** 定义默认超时时间。 */
        const val DEFAULT_TIMEOUT_SECONDS: Int = 60
        /** 定义最小超时时间。 */
        const val MIN_TIMEOUT_SECONDS: Int = 30
        /** 定义最大超时时间。 */
        const val MAX_TIMEOUT_SECONDS: Int = 3_600
        /** 定义默认温度。 */
        const val DEFAULT_TEMPERATURE: Double = 0.2
        /** 默认开启：允许对 class JAR 提供反编译来源标记。 */
        const val DEFAULT_ALLOW_CLASS_JAR_DECOMPILE: Boolean = true
        /** 默认开启：允许架构索引展开外部库类。 */
        const val DEFAULT_ALLOW_EXTERNAL_LIBRARY_EXPANSION: Boolean = true
        /** 默认关闭：是否允许架构索引展开 JDK 类。 */
        const val DEFAULT_ALLOW_JDK_LIBRARY_EXPANSION: Boolean = false
        /** 默认外部类节点预算上限。 */
        const val DEFAULT_MAX_EXTERNAL_CLASS_NODES: Int = 3000
        /** 外部类节点数量下限。 */
        const val MIN_EXTERNAL_CLASS_NODES: Int = 0
        /** 外部类节点数量上限。 */
        const val MAX_EXTERNAL_CLASS_NODES: Int = 50_000
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
    var attachedJars: MutableList<AttachedJarEntry> = mutableListOf(),
    var allowClassJarDecompile: Boolean = LinkGraphSettingsState.DEFAULT_ALLOW_CLASS_JAR_DECOMPILE,
    var allowExternalLibraryExpansion: Boolean = LinkGraphSettingsState.DEFAULT_ALLOW_EXTERNAL_LIBRARY_EXPANSION,
    var allowJdkLibraryExpansion: Boolean = LinkGraphSettingsState.DEFAULT_ALLOW_JDK_LIBRARY_EXPANSION,
    var maxExternalClassNodes: Int = LinkGraphSettingsState.DEFAULT_MAX_EXTERNAL_CLASS_NODES,
) {
    /** 规范化当前持久化状态，过滤无效附加 JAR 等脏数据。 */
    fun sanitized(): LinkGraphPersistentSettingsState {
        return toRuntimeState().toPersistentState()
    }

    /**
     * 把持久化状态转换为运行时状态，并显式注入 API Key。
     * 调用方需要根据上下文决定是否传入真实密钥。
     */
    fun toRuntimeState(apiKey: String = ""): LinkGraphSettingsState {
        return LinkGraphSettingsState(
            llmEnabled = llmEnabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            timeoutSeconds = timeoutSeconds,
            temperature = temperature,
            attachedJars = attachedJars,
            allowClassJarDecompile = allowClassJarDecompile,
            allowExternalLibraryExpansion = allowExternalLibraryExpansion,
            allowJdkLibraryExpansion = allowJdkLibraryExpansion,
            maxExternalClassNodes = maxExternalClassNodes,
        ).sanitized()
    }
}

/**
 * 插件级设置存储。
 * 当前主要承载 LLM 生成计划所需的 preset、endpoint、model 和鉴权信息。
 */
@State(
    name = "LinkGraphSettings",
    storages = [Storage("link-graph.xml")],
)
@Service(Service.Level.APP)
class LinkGraphSettingsService : PersistentStateComponent<LinkGraphPersistentSettingsState> {
    /** 保存当前持久化状态。 */
    private var state = LinkGraphPersistentSettingsState()
    /** 密钥存储抽象，默认指向 PasswordSafe 实现。 */
    private val secretStore: LinkGraphSecretStore

    /** 默认构造函数：使用 PasswordSafe 实现管理密钥。 */
    constructor() {
        secretStore = PasswordSafeLinkGraphSecretStore()
    }

    /** 测试用构造函数：允许注入自定义密钥存储以隔离 PasswordSafe 依赖。 */
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
     * 返回不包含敏感信息的设置快照。
     * 架构索引和源码解析只需要非敏感字段，不能在 read action 中触碰 PasswordSafe。
     */
    fun nonSecretSnapshot(): LinkGraphSettingsState = state.toRuntimeState(apiKey = "")

    /**
     * 更新设置状态。
     */
    fun update(
        nextState: LinkGraphSettingsState,
        preserveBlankApiKey: Boolean = false,
    ) {
        val before = nonSecretSnapshot()
        val sanitized = nextState.sanitized()
        state = sanitized.toPersistentState()
        when {
            sanitized.apiKey.isBlank() && preserveBlankApiKey -> Unit
            sanitized.apiKey.isBlank() -> secretStore.clearApiKey()
            else -> secretStore.saveApiKey(sanitized.apiKey)
        }
        if (before.architectureIndexSettingsKey() != sanitized.architectureIndexSettingsKey()) {
            ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(LinkGraphSettingsChangedNotifier.TOPIC)
                .onArchitectureIndexSettingsChanged(before, sanitized)
        }
    }

    /**
     * 判断远程生成所需配置是否已经就绪。
     */
    fun isRemoteGenerationReady(): Boolean {
        return snapshot().remoteConnectionOrNull() != null
    }
}

/**
 * 设置变更通知器。
 * 当影响架构索引的配置发生变化时，通过 IntelliJ 消息总线广播事件，
 * 让索引重建等监听方及时响应。
 */
interface LinkGraphSettingsChangedNotifier {
    /**
     * 当影响架构索引的设置发生变化时触发。
     * 入参 [before] 与 [after] 分别表示变更前后的快照，便于差异分析。
     */
    fun onArchitectureIndexSettingsChanged(
        before: LinkGraphSettingsState,
        after: LinkGraphSettingsState,
    )

    companion object {
        /** 注册在 IntelliJ 消息总线上的设置变更主题。 */
        @JvmField
        val TOPIC: Topic<LinkGraphSettingsChangedNotifier> = Topic.create(
            "linkGraphSettingsChanged",
            LinkGraphSettingsChangedNotifier::class.java,
        )
    }
}

/**
 * 提取一组用于判断架构索引是否需要重建的稳定字段。
 * 任何一个字段变化都意味着索引输入发生改变。
 */
private fun LinkGraphSettingsState.architectureIndexSettingsKey(): List<Any?> =
    listOf(
        attachedJars,
        allowClassJarDecompile,
        allowExternalLibraryExpansion,
        allowJdkLibraryExpansion,
        maxExternalClassNodes,
    )
