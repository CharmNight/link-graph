package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.LinkGraphBundle

/**
 * 定义远程 LLM 兼容协议类型。
 */
enum class LlmWireProtocol {
    /** 表示 OpenAI Chat Completions 协议。 */
    OPENAI_CHAT_COMPLETIONS,
    /** 表示 OpenAI Responses 协议。 */
    OPENAI_RESPONSES,
    /** 表示 Anthropic Messages 协议。 */
    ANTHROPIC_MESSAGES,
}

/**
 * 表示一个可选的 LLM Provider 预设。
 */
data class LlmProviderPreset(
    /** 保存预设唯一标识。 */
    val id: String,
    /** 保存国际化标签键。 */
    private val labelKey: String,
    /** 标记该预设是否需要远程请求。 */
    val isRemote: Boolean,
    /** 保存底层线协议。 */
    val wireProtocol: LlmWireProtocol? = null,
    /** 保存默认请求地址。 */
    val defaultEndpoint: String = "",
    /** 保存默认模型名。 */
    val defaultModel: String = DEFAULT_GENERIC_MODEL,
    /** 保存当前预设支持的能力集合。 */
    val capabilities: LlmCapabilitySet = if (isRemote) DEFAULT_REMOTE_CAPABILITIES else LlmCapabilitySet(),
) {
    /**
     * 返回用于界面展示的本地化名称。
     */
    override fun toString(): String = LinkGraphBundle.message(labelKey)

    companion object {
        /** 定义通用默认模型名。 */
        const val DEFAULT_GENERIC_MODEL: String = "gpt-4.1-mini"
        /** 定义 MiniMax 默认模型名。 */
        const val DEFAULT_MINIMAX_MODEL: String = "MiniMax-M2.7"
        /** 远程预设默认具备的能力集合。 */
        val DEFAULT_REMOTE_CAPABILITIES = LlmCapabilitySet(
            supportsStructuredJson = true,
            supportsStreaming = false,
        )
    }
}

/**
 * 描述一个 provider 预设支持的能力集合。
 */
data class LlmCapabilitySet(
    /** 是否支持结构化 JSON 场景。 */
    val supportsStructuredJson: Boolean = false,
    /** 是否支持流式输出。 */
    val supportsStreaming: Boolean = false,
)

/**
 * 集中维护内置的 Provider 预设列表。
 */
object LlmProviderPresets {
    /** 表示本地 Mock 预设。 */
    val MOCK = LlmProviderPreset(
        id = "MOCK",
        labelKey = "settings.link-graph.provider.mock",
        isRemote = false,
    )

    /** 表示通用 OpenAI 兼容预设。 */
    val OPENAI_COMPATIBLE = LlmProviderPreset(
        id = "OPENAI_COMPATIBLE",
        labelKey = "settings.link-graph.provider.openai-compatible",
        isRemote = true,
        wireProtocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultModel = LlmProviderPreset.DEFAULT_GENERIC_MODEL,
        capabilities = LlmCapabilitySet(
            supportsStructuredJson = true,
            supportsStreaming = true,
        ),
    )

    /** 表示通用 OpenAI Responses 预设。 */
    val OPENAI_RESPONSES = LlmProviderPreset(
        id = "OPENAI_RESPONSES",
        labelKey = "settings.link-graph.provider.openai-responses",
        isRemote = true,
        wireProtocol = LlmWireProtocol.OPENAI_RESPONSES,
        defaultEndpoint = "https://api.openai.com/v1",
        defaultModel = LlmProviderPreset.DEFAULT_GENERIC_MODEL,
        capabilities = LlmCapabilitySet(
            supportsStructuredJson = true,
            supportsStreaming = true,
        ),
    )

    /** 表示 MiniMax 的 OpenAI 兼容预设。 */
    val MINIMAX_OPENAI = LlmProviderPreset(
        id = "MINIMAX_OPENAI",
        labelKey = "settings.link-graph.provider.minimax-openai",
        isRemote = true,
        wireProtocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultEndpoint = "https://api.minimax.io/v1",
        defaultModel = LlmProviderPreset.DEFAULT_MINIMAX_MODEL,
        capabilities = LlmCapabilitySet(
            supportsStructuredJson = true,
            supportsStreaming = true,
        ),
    )

    /** 表示 MiniMax 的 Anthropic 兼容预设。 */
    val MINIMAX_ANTHROPIC = LlmProviderPreset(
        id = "MINIMAX_ANTHROPIC",
        labelKey = "settings.link-graph.provider.minimax-anthropic",
        isRemote = true,
        wireProtocol = LlmWireProtocol.ANTHROPIC_MESSAGES,
        defaultEndpoint = "https://api.minimax.io/anthropic",
        defaultModel = LlmProviderPreset.DEFAULT_MINIMAX_MODEL,
        capabilities = LlmCapabilitySet(
            supportsStructuredJson = true,
            supportsStreaming = false,
        ),
    )

    /** 保存全部可选预设。 */
    val entries: List<LlmProviderPreset> = listOf(
        MOCK,
        OPENAI_COMPATIBLE,
        OPENAI_RESPONSES,
        MINIMAX_OPENAI,
        MINIMAX_ANTHROPIC,
    )

    /**
     * 根据标识解析预设，未知值时回退到安全默认值。
     */
    fun resolve(id: String): LlmProviderPreset {
        return entries.firstOrNull { preset -> preset.id == id } ?: when (id) {
            "MOCK" -> MOCK
            "OPENAI_COMPATIBLE" -> OPENAI_COMPATIBLE
            "OPENAI_RESPONSES" -> OPENAI_RESPONSES
            else -> MOCK
        }
    }
}
