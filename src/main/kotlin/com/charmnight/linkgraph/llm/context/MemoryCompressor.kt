package com.charmnight.linkgraph.llm.context

/**
 * 对历史记忆做轻量压缩。
 * 当前仅做去空和去重，避免把完整多轮历史直接送入后续阶段。
 */
class MemoryCompressor {
    fun compress(lines: List<String>): List<String> {
        return lines.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }
}
