package com.charmnight.linkgraph.investigation.resolving.java

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class JavaPsiEvidenceSupportTest {
    @Test
    fun classCandidateResolutionDoesNotKeepUnboundedJavaFileFallbackScan() {
        val source = Files.readString(
            Path.of("").toAbsolutePath()
                .resolve("src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaPsiEvidenceSupport.kt"),
        )

        assertFalse(source.contains("FilenameIndex.getAllFilesByExt(context.project, \"java\""))
    }
}
