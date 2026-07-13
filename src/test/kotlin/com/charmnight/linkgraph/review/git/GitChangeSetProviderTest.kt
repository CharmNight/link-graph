package com.charmnight.linkgraph.review.git

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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

    @Test
    fun runGitReturnsNullWhenOutputExceedsConfiguredCap() {
        val repo = Files.createTempDirectory("link-graph-review-git-output-cap")
        val fakeGit = fakeGit(repo, "printf 'diff --git a/A.java b/A.java\\n'; head -c 200 /dev/zero | tr '\\0' 'x'")

        val diff = GitChangeSetProvider(
            projectBasePath = repo.toString(),
            gitExecutable = fakeGit.toString(),
            maxGitOutputChars = 64,
        ).unifiedDiff()

        assertNull(diff)
    }

    @Test
    fun runGitTimesOutSlowProcess() {
        val repo = Files.createTempDirectory("link-graph-review-git-timeout")
        val fakeGit = fakeGit(repo, "sleep 2; printf 'diff --git a/A.java b/A.java\\n'")

        val elapsed = measureTime {
            val diff = GitChangeSetProvider(
                projectBasePath = repo.toString(),
                gitExecutable = fakeGit.toString(),
                gitTimeoutMillis = 50,
            ).unifiedDiff()
            assertNull(diff)
        }

        assertTrue(elapsed < 1.seconds, "git timeout should not wait for the full fake process sleep: $elapsed")
    }

    @Test
    fun untrackedChangeSetSkipsFilesOverConfiguredCap() {
        val repo = Files.createTempDirectory("link-graph-review-git-untracked-cap")
        runGit(repo, "init")
        Files.writeString(repo.resolve("Huge.java"), "x".repeat(32))

        val files = GitChangeSetProvider(
            projectBasePath = repo.toString(),
            maxUntrackedFileChars = 8,
        ).workingTreeChangeSet()

        assertTrue(files.none { file -> file.newPath == "Huge.java" })
    }

    @Test
    fun untrackedChangeSetSkipsSymlinksToFilesOutsideRepository() {
        val repo = Files.createTempDirectory("link-graph-review-git-untracked-symlink")
        val externalSecret = Files.createTempFile("link-graph-review-secret", ".txt")
        runGit(repo, "init")
        Files.writeString(externalSecret, "leaked-secret-value\n")
        val symlink = repo.resolve("leaked.txt")
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(symlink, externalSecret)
        }.isSuccess
        if (!symlinkCreated) {
            return
        }

        val files = GitChangeSetProvider(projectBasePath = repo.toString()).workingTreeChangeSet()

        assertTrue(files.none { file -> file.newPath == "leaked.txt" })
        assertTrue(files.flatMap(GitChangedFile::hunks).flatMap(GitHunk::lines).none { line ->
            line.contains("leaked-secret-value")
        })
    }

    private fun fakeGit(repo: Path, scriptBody: String): Path {
        val script = repo.resolve("fake-git.sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            $scriptBody
            """.trimIndent(),
        )
        script.toFile().setExecutable(true)
        return script
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
