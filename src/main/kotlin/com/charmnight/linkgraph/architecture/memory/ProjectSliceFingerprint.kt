package com.charmnight.linkgraph.architecture.memory

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 项目 slice 指纹工具。
 *
 * 提供文件级与内容级的哈希计算，用于增量索引判断文件是否变化。
 * 全部使用 SHA-256，保证碰撞概率可以忽略。
 */
object ProjectSliceFingerprint {
    /**
     * 计算一组文件指纹的整体哈希。
     * 按相对路径排序后拼接每条指纹的字段，再做 SHA-256。
     * 排序保证同一组文件多次计算结果一致。
     */
    fun fileHash(files: List<ProjectFileFingerprint>): String =
        stableSha256(
            files.sortedBy(ProjectFileFingerprint::relativePath)
                .joinToString("|") { file ->
                    listOf(
                        file.relativePath,
                        file.size.toString(),
                        file.modifiedAtMillis.toString(),
                        file.contentSha256.orEmpty(),
                    ).joinToString(":")
                },
        )

    /**
     * 计算文件内容的 SHA-256。
     * 非普通文件（目录、不存在等）返回 null。
     */
    fun contentSha256(path: Path): String? =
        runCatching {
            if (!Files.isRegularFile(path)) {
                return@runCatching null
            }
            Files.newInputStream(path).use(::contentSha256)
        }.getOrNull()

    /** 计算字节数组的 SHA-256。 */
    fun contentSha256(bytes: ByteArray): String =
        stableSha256(bytes)

    /**
     * 流式计算 SHA-256。
     * 适合大文件——边读边更新摘要，不需要把整个文件加载到内存。
     */
    fun contentSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) {
                break
            }
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().toHex()
    }

    /** 字符串 → SHA-256（UTF-8 编码）。 */
    private fun stableSha256(value: String): String =
        stableSha256(value.toByteArray(StandardCharsets.UTF_8))

    /** 字节数组 → SHA-256。 */
    private fun stableSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHex()

    /** 字节数组 → 十六进制字符串（小写）。 */
    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}
