package com.charmnight.linkgraph.architecture.memory

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ProjectSliceFingerprintTest {
    @Test
    fun fileHashChangesWhenContentChangesEvenIfPathSizeAndMtimeStayTheSame() {
        val first = ProjectFileFingerprint(
            relativePath = "src/main/java/com/example/OrderService.java",
            size = 24,
            modifiedAtMillis = 1_700_000_000_000,
            contentSha256 = "content-hash-one",
        )
        val second = first.copy(contentSha256 = "content-hash-two")

        assertNotEquals(
            ProjectSliceFingerprint.fileHash(listOf(first)),
            ProjectSliceFingerprint.fileHash(listOf(second)),
        )
    }

    @Test
    fun contentSha256ReadsActualFileContent() {
        val tempFile = Files.createTempFile("link-graph-fingerprint", ".java")
        try {
            Files.writeString(tempFile, "class OrderService {}")
            Files.setLastModifiedTime(tempFile, FileTime.fromMillis(1_700_000_000_000))
            val first = ProjectSliceFingerprint.contentSha256(tempFile)
            Files.writeString(tempFile, "class OrderClient0 {}")
            Files.setLastModifiedTime(tempFile, FileTime.fromMillis(1_700_000_000_000))
            val second = ProjectSliceFingerprint.contentSha256(tempFile)

            assertNotNull(first)
            assertNotNull(second)
            assertNotEquals(first, second)
        } finally {
            tempFile.toFile().delete()
        }
    }
}
