package com.charmnight.linkgraph.mermaid

/**
 * Mermaid 注释元数据需要同时满足两点：
 * 1. 人眼可读，不能把普通路径/签名全部编码成 `%2F` 这种形式。
 * 2. 可稳定回导，必须转义分隔符 `|`、`=` 和换行。
 */
internal object MermaidMetadataCodec {
    /**
     * 对 Mermaid 注释中的元数据值进行转义编码。
     */
    fun encode(value: String): String {
        if (value.isEmpty()) {
            return value
        }
        // 预估长度初始化构建器，减少频繁扩容。
        val builder = StringBuilder(value.length)
        value.forEach { ch ->
            when (ch) {
                '%', '|', '=', '\n', '\r', '[', ']', '{', '}', '(', ')', '<', '>', '"' -> {
                    // 关键分隔符统一编码成 `%XX`，兼顾可读性与回导稳定性。
                    builder.append('%')
                    builder.append(ch.code.toString(16).uppercase().padStart(2, '0'))
                }

                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * 把 Mermaid 元数据中的 `%XX` 转义片段解码回原始文本。
     */
    fun decode(value: String): String {
        if (value.indexOf('%') < 0) {
            return value
        }
        // 没有转义符时直接返回原值，避免无意义的遍历。
        val builder = StringBuilder(value.length)
        // 使用手动索引扫描，便于处理 `%XX` 三字符窗口。
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '%' && index + 2 < value.length) {
                // 满足 `%XX` 形式时尝试按十六进制解码。
                val hex = value.substring(index + 1, index + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    builder.append(decoded.toChar())
                    index += 3
                    continue
                }
            }
            builder.append(current)
            index += 1
        }
        return builder.toString()
    }
}
