package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 为“远程 LLM 未就绪”生成可直接展示给用户的中文提示。
 * 重点是明确缺少哪项、去哪修，以及需要先在设置页完成验证。
 */
internal fun LinkGraphSettingsState.remoteLlmSetupHint(fallbackTarget: String): String {
    // 收集缺失或格式异常的关键配置项，便于直接展示给用户。
    /** 缺失或格式异常的配置项列表。 */
    val missingFields = buildList {
        if (effectiveEndpoint().isBlank()) {
            add("请求地址")
        } else if (!LlmUserMessageFormatter.isLikelyEndpoint(effectiveEndpoint())) {
            add("请求地址格式不正确")
        }
        if (apiKey.isBlank()) {
            add("API 密钥")
        }
        if (effectiveModel().isBlank()) {
            add("模型名")
        }
    }
    // 根据是否存在明确缺失项，生成不同粒度的中文提示文本。
    /** 面向用户展示的缺失项摘要。 */
    val missingText = if (missingFields.isEmpty()) {
        "当前远程参数仍需到设置页确认。"
    } else {
        "缺少或异常项：${missingFields.joinToString("、")}。"
    }
    // 在最终提示中同时说明回退结果、修复入口和建议动作。
    return "远程 LLM 配置未就绪，已回退为$fallbackTarget。$missingText 请先到链路图设置（IDE 设置 > 工具 > 链路图）补全，并先验证远程配置。"
}
