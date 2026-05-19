package com.charmnight.linkgraph.review.git

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitChangeSetProviderTest {
    @Test
    fun parsesRenameDeleteAndHunks() {
        val diff = """
            diff --git a/src/main/java/com/example/Old.java b/src/main/java/com/example/New.java
            similarity index 88%
            rename from src/main/java/com/example/Old.java
            rename to src/main/java/com/example/New.java
            --- a/src/main/java/com/example/Old.java
            +++ b/src/main/java/com/example/New.java
            @@ -4,2 +4,3 @@
            - old
            + new
            diff --git a/src/main/java/com/example/Deleted.java b/src/main/java/com/example/Deleted.java
            deleted file mode 100644
            --- a/src/main/java/com/example/Deleted.java
            +++ /dev/null
            @@ -1,2 +0,0 @@
            - class Deleted {}
        """.trimIndent()

        val files = GitChangeSetProvider.parseUnifiedDiff(diff)

        assertEquals(2, files.size)
        assertEquals(GitChangeKind.RENAMED, files[0].changeKind)
        assertEquals("src/main/java/com/example/Old.java", files[0].oldPath)
        assertEquals("src/main/java/com/example/New.java", files[0].newPath)
        assertEquals(88, files[0].similarity)
        assertEquals(4, files[0].hunks.single().oldStart)
        assertEquals(GitChangeKind.DELETED, files[1].changeKind)
        assertEquals("src/main/java/com/example/Deleted.java", files[1].oldPath)
        assertEquals(null, files[1].newPath)
    }

    @Test
    fun workingTreeChangeSetIncludesStagedAndUnstagedDiffs() {
        val repo = Files.createTempDirectory("link-graph-review-git")
        runGit(repo, "init")
        runGit(repo, "config", "user.email", "test@example.com")
        runGit(repo, "config", "user.name", "Test User")
        Files.createDirectories(repo.resolve("src/main/java/com/example"))
        Files.writeString(repo.resolve("src/main/java/com/example/Staged.java"), "class Staged { int value = 1; }\n")
        Files.writeString(repo.resolve("src/main/java/com/example/Unstaged.java"), "class Unstaged { int value = 1; }\n")
        runGit(repo, "add", ".")
        runGit(repo, "commit", "-m", "initial")
        Files.writeString(repo.resolve("src/main/java/com/example/Staged.java"), "class Staged { int value = 2; }\n")
        runGit(repo, "add", "src/main/java/com/example/Staged.java")
        Files.writeString(repo.resolve("src/main/java/com/example/Unstaged.java"), "class Unstaged { int value = 2; }\n")
        Files.writeString(repo.resolve("src/main/java/com/example/NewFile.java"), "class NewFile {}\n")

        val files = GitChangeSetProvider(repo.toString()).workingTreeChangeSet()

        assertTrue(files.any { file -> file.newPath == "src/main/java/com/example/Staged.java" })
        assertTrue(files.any { file -> file.newPath == "src/main/java/com/example/Unstaged.java" })
        assertTrue(files.any { file ->
            file.newPath == "src/main/java/com/example/NewFile.java" &&
                file.changeKind == GitChangeKind.ADDED &&
                file.hunks.single().newStart == 1
        })
        assertEquals(listOf("src/main/java/com/example/Staged.java"), GitChangeSetProvider(repo.toString()).stagedChangeSet().mapNotNull(GitChangedFile::newPath))
    }

    private fun runGit(
        repo: Path,
        vararg args: String,
    ) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "git ${args.joinToString(" ")} failed: $output"
        }
    }
}
