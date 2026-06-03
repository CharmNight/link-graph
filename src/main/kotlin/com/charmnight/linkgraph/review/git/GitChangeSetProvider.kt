package com.charmnight.linkgraph.review.git

import java.nio.file.Files
import java.nio.file.Path

enum class GitChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
}

data class GitHunk(
    val oldStart: Int?,
    val oldLineCount: Int?,
    val newStart: Int?,
    val newLineCount: Int?,
    val header: String,
    val lines: List<String> = emptyList(),
)

data class GitChangedFile(
    val oldPath: String?,
    val newPath: String?,
    val changeKind: GitChangeKind,
    val hunks: List<GitHunk>,
    val similarity: Int? = null,
)

open class GitChangeSetProvider(
    private val projectBasePath: String?,
) {
    open fun workingTreeChangeSet(selectedPaths: List<String> = emptyList()): List<GitChangedFile> =
        (
            unifiedDiff(selectedPaths)
                ?.let(::parseUnifiedDiff)
                .orEmpty() +
                untrackedChangeSet(selectedPaths)
            )
            .mergeChangedFiles()

    fun stagedChangeSet(selectedPaths: List<String> = emptyList()): List<GitChangedFile> =
        stagedUnifiedDiff(selectedPaths)
            ?.let(::parseUnifiedDiff)
            .orEmpty()
            .mergeChangedFiles()

    fun unifiedDiff(selectedPaths: List<String> = emptyList()): String? {
        return listOfNotNull(
            unstagedUnifiedDiff(selectedPaths),
            stagedUnifiedDiff(selectedPaths),
        )
            .filter { text -> text.isNotBlank() && text.contains("diff --git") }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }

    fun stagedUnifiedDiff(selectedPaths: List<String> = emptyList()): String? =
        diff(selectedPaths, staged = true)

    private fun unstagedUnifiedDiff(selectedPaths: List<String> = emptyList()): String? =
        diff(selectedPaths, staged = false)

    private fun untrackedChangeSet(selectedPaths: List<String>): List<GitChangedFile> {
        val basePath = projectBasePath?.takeIf(String::isNotBlank) ?: return emptyList()
        val command = buildList {
            add("git")
            add("ls-files")
            add("--others")
            add("--exclude-standard")
            if (selectedPaths.isNotEmpty()) {
                add("--")
                addAll(selectedPaths)
            }
        }
        val base = Path.of(basePath).toAbsolutePath().normalize()
        return runGit(basePath, command)
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { relativePath ->
                val file = base.resolve(relativePath).normalize()
                if (!file.startsWith(base) || !Files.isRegularFile(file)) {
                    return@mapNotNull null
                }
                val text = runCatching { Files.readString(file) }.getOrNull().orEmpty()
                val lineCount = text.lineSequence().count().coerceAtLeast(1)
                GitChangedFile(
                    oldPath = null,
                    newPath = relativePath,
                    changeKind = GitChangeKind.ADDED,
                    hunks = listOf(
                        GitHunk(
                            oldStart = null,
                            oldLineCount = 0,
                            newStart = 1,
                            newLineCount = lineCount,
                            header = "@@ -0,0 +1,$lineCount @@",
                            lines = text.lineSequence().map { line -> "+$line" }.toList(),
                        ),
                    ),
                )
            }
            .toList()
    }

    private fun diff(
        selectedPaths: List<String>,
        staged: Boolean,
    ): String? {
        val basePath = projectBasePath?.takeIf(String::isNotBlank) ?: return null
        val command = buildList {
            add("git")
            add("diff")
            if (staged) {
                add("--cached")
            }
            add("--find-renames")
            add("--find-copies")
            add("--unified=80")
            if (selectedPaths.isNotEmpty()) {
                add("--")
                addAll(selectedPaths)
            }
        }
        return runGit(basePath, command)
            ?.takeIf { text -> text.isNotBlank() && text.contains("diff --git") }
    }

    open fun readHeadFile(path: String): String? {
        val basePath = projectBasePath?.takeIf(String::isNotBlank) ?: return null
        return runGit(basePath, listOf("git", "show", "HEAD:$path"))
            ?.takeIf(String::isNotBlank)
    }

    private fun runGit(
        basePath: String,
        command: List<String>,
    ): String? =
        runCatching {
            val process = ProcessBuilder(command)
                .directory(Path.of(basePath).toFile())
                .redirectErrorStream(false)
                .start()
            val text = process.inputStream.bufferedReader().use { reader -> reader.readText() }
            val exitCode = process.waitFor()
            text.takeIf { exitCode == 0 }
        }.getOrNull()

    companion object {
        fun parseUnifiedDiff(text: String): List<GitChangedFile> {
            if (!text.contains("diff --git")) {
                return emptyList()
            }
            val files = mutableListOf<MutableGitChangedFile>()
            var current: MutableGitChangedFile? = null
            var currentHunk: MutableGitHunk? = null
            text.lineSequence().forEach { line ->
                when {
                    line.startsWith("diff --git ") -> {
                        currentHunk?.let { hunk -> current?.hunks?.add(hunk.toImmutable()) }
                        current?.let { files += it }
                        currentHunk = null
                        val parts = line.removePrefix("diff --git ").split(' ').filter(String::isNotBlank)
                        current = MutableGitChangedFile(
                            oldPath = parts.getOrNull(0)?.toDiffPath(),
                            newPath = parts.getOrNull(1)?.toDiffPath(),
                        )
                    }
                    current == null -> Unit
                    line.startsWith("similarity index ") -> {
                        current?.similarity = line.removePrefix("similarity index ")
                            .removeSuffix("%")
                            .trim()
                            .toIntOrNull()
                    }
                    line.startsWith("new file mode ") -> current?.changeKind = GitChangeKind.ADDED
                    line.startsWith("deleted file mode ") -> current?.changeKind = GitChangeKind.DELETED
                    line.startsWith("copy from ") -> {
                        current?.oldPath = line.removePrefix("copy from ").trim().takeIf(String::isNotBlank)
                        current?.changeKind = GitChangeKind.COPIED
                    }
                    line.startsWith("copy to ") -> {
                        current?.newPath = line.removePrefix("copy to ").trim().takeIf(String::isNotBlank)
                        current?.changeKind = GitChangeKind.COPIED
                    }
                    line.startsWith("rename from ") -> {
                        current?.oldPath = line.removePrefix("rename from ").trim().takeIf(String::isNotBlank)
                        current?.changeKind = GitChangeKind.RENAMED
                    }
                    line.startsWith("rename to ") -> {
                        current?.newPath = line.removePrefix("rename to ").trim().takeIf(String::isNotBlank)
                        current?.changeKind = GitChangeKind.RENAMED
                    }
                    line.startsWith("--- ") -> {
                        current?.oldPath = line.removePrefix("--- ").toDiffPath()
                    }
                    line.startsWith("+++ ") -> {
                        current?.newPath = line.removePrefix("+++ ").toDiffPath()
                    }
                    line.startsWith("@@") -> {
                        currentHunk?.let { hunk -> current?.hunks?.add(hunk.toImmutable()) }
                        currentHunk = parseHunkHeader(line)
                    }
                    currentHunk != null -> currentHunk?.lines?.add(line)
                }
            }
            currentHunk?.let { hunk -> current?.hunks?.add(hunk.toImmutable()) }
            current?.let { files += it }
            return files.map { file -> file.toImmutable() }
        }

        private fun parseHunkHeader(header: String): MutableGitHunk {
            val match = HUNK_PATTERN.find(header)
            return MutableGitHunk(
                oldStart = match?.groupValues?.getOrNull(1)?.toIntOrNull(),
                oldLineCount = match?.groupValues?.getOrNull(2)?.takeIf(String::isNotBlank)?.toIntOrNull() ?: 1,
                newStart = match?.groupValues?.getOrNull(3)?.toIntOrNull(),
                newLineCount = match?.groupValues?.getOrNull(4)?.takeIf(String::isNotBlank)?.toIntOrNull() ?: 1,
                header = header,
            )
        }

        private fun String.toDiffPath(): String? =
            trim()
                .removePrefix("a/")
                .removePrefix("b/")
                .takeUnless { it == "/dev/null" }
                ?.takeIf(String::isNotBlank)

        private val HUNK_PATTERN = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
    }
}

private fun List<GitChangedFile>.mergeChangedFiles(): List<GitChangedFile> =
    groupBy { file ->
        listOf(
            file.oldPath.orEmpty(),
            file.newPath.orEmpty(),
            file.changeKind.name,
            file.similarity?.toString().orEmpty(),
        ).joinToString("|")
    }
        .values
        .map { sameFile ->
            val first = sameFile.first()
            first.copy(hunks = sameFile.flatMap(GitChangedFile::hunks))
        }

private data class MutableGitChangedFile(
    var oldPath: String?,
    var newPath: String?,
    var changeKind: GitChangeKind = GitChangeKind.MODIFIED,
    var similarity: Int? = null,
    val hunks: MutableList<GitHunk> = mutableListOf(),
) {
    fun toImmutable(): GitChangedFile =
        GitChangedFile(
            oldPath = oldPath,
            newPath = newPath,
            changeKind = when {
                changeKind != GitChangeKind.MODIFIED -> changeKind
                oldPath == null && newPath != null -> GitChangeKind.ADDED
                oldPath != null && newPath == null -> GitChangeKind.DELETED
                oldPath != null && newPath != null && oldPath != newPath -> GitChangeKind.RENAMED
                else -> GitChangeKind.MODIFIED
            },
            hunks = hunks.toList(),
            similarity = similarity,
        )
}

private data class MutableGitHunk(
    val oldStart: Int?,
    val oldLineCount: Int?,
    val newStart: Int?,
    val newLineCount: Int?,
    val header: String,
    val lines: MutableList<String> = mutableListOf(),
) {
    fun toImmutable(): GitHunk =
        GitHunk(
            oldStart = oldStart,
            oldLineCount = oldLineCount,
            newStart = newStart,
            newLineCount = newLineCount,
            header = header,
            lines = lines.toList(),
        )
}
