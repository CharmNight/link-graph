package com.charmnight.linkgraph.foundation

/**
 * 惰性日志输出工具：仅在调试开关开启时计算并输出消息。
 *
 * 普通的 `debug(message())` 会先计算 message 再决定是否输出，浪费性能；
 * 本函数用 lambda 推迟消息构造，仅在 [debugEnabled] 为 true 时才调用 [message]
 * 取出字符串并通过 [debug] 输出。常用于热路径上较昂贵的日志格式化。
 *
 * @param debugEnabled 是否启用调试日志
 * @param debug 实际输出函数（通常是 logger::debug）
 * @param message 惰性消息构造函数
 */
internal inline fun debugLazy(
    debugEnabled: Boolean,
    debug: (String) -> Unit,
    message: () -> String,
) {
    // 未开启调试时直接跳过消息构造，避免无谓开销
    if (!debugEnabled) {
        return
    }
    debug(message())
}
