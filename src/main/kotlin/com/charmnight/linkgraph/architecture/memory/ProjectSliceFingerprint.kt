package com.charmnight.linkgraph.architecture.memory

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object ProjectSliceFingerprint {
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

    fun contentSha256(path: Path): String? =
        runCatching {
            if (!Files.isRegularFile(path)) {
                return@runCatching null
            }
            Files.newInputStream(path).use(::contentSha256)
        }.getOrNull()

    fun contentSha256(bytes: ByteArray): String =
        stableSha256(bytes)

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

    private fun stableSha256(value: String): String =
        stableSha256(value.toByteArray(StandardCharsets.UTF_8))

    private fun stableSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}
