package com.charmnight.linkgraph.llm.context

/**
 * 对历史记忆做轻量压缩。
 *
 * 当前仅做去空和去重，避免把完整多轮历史直接送入后续阶段。
 * 后续若需要更强的语义压缩（例如把多轮对话归纳为单条摘要），
 * 在本类内部增强即可，调用方签名保持不变。
 */
class MemoryCompressor {
    /**
     * 对输入文本行做归一化压缩。
     *
     * @param lines 原始历史文本行（可能含空白、重复）
     * @return 去除首尾空白后过滤掉空行、再去重的结果；保留原顺序的首次出现
     */
    fun compress(lines: List<String>): List<String> {
        return lines.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }
}
