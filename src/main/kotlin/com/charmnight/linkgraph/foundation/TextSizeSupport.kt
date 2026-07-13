package com.charmnight.linkgraph.foundation

/** 以 UTF-8 字节数做上限校验；超过上限立即返回，避免为大字符串再分配完整 byte array。 */
internal fun utf8ByteLengthAtMost(text: String, maxBytes: Int): Boolean {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    var byteCount = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val bytes = when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            Character.isHighSurrogate(char) &&
                index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1]) -> {
                index += 1
                4
            }
            else -> 3
        }
        byteCount += bytes
        if (byteCount > maxBytes) {
            return false
        }
        index += 1
    }
    return true
}

/** 计算 UTF-8 字节数，不创建完整 byte array。 */
internal fun utf8ByteCount(text: String): Int {
    var byteCount = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        byteCount += when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        index += Character.charCount(codePoint)
    }
    return byteCount
}

/** 按 UTF-8 字节预算截断文本，永远不会切断代理对或多字节字符。 */
internal fun truncateUtf8(text: String, maxBytes: Int): String {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    if (utf8ByteLengthAtMost(text, maxBytes)) {
        return text
    }
    val result = StringBuilder()
    var byteCount = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val codePointBytes = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        if (byteCount + codePointBytes > maxBytes) {
            break
        }
        result.appendCodePoint(codePoint)
        byteCount += codePointBytes
        index += Character.charCount(codePoint)
    }
    return result.toString()
}
