package com.charmnight.linkgraph.review.git

import java.io.Reader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val DEFAULT_GIT_TIMEOUT_MILLIS: Long = 10_000
private const val DEFAULT_MAX_GIT_OUTPUT_CHARS: Int = 2 * 1024 * 1024
private const val DEFAULT_MAX_UNTRACKED_FILE_CHARS: Int = 512 * 1024

/**
 * 描述 Git 文件级变更类型。
 */
enum class GitChangeKind {
    /** 表示新增文件。 */
    ADDED,
    /** 表示已修改文件。 */
    MODIFIED,
    /** 表示已删除文件。 */
    DELETED,
    /** 表示重命名文件。 */
    RENAMED,
    /** 表示复制文件。 */
    COPIED,
}

/**
 * 描述一个 Git 代码块（hunk），包含新旧行号区间与具体行内容。
 */
data class GitHunk(
    /** 保存旧文件中代码块起始行号，新增块时为 null。 */
    val oldStart: Int?,
    /** 保存旧文件中代码块行数。 */
    val oldLineCount: Int?,
    /** 保存新文件中代码块起始行号，删除块时为 null。 */
    val newStart: Int?,
    /** 保存新文件中代码块行数。 */
    val newLineCount: Int?,
    /** 保存原始 hunk 头文本，例如 `@@ -1,3 +1,4 @@`。 */
    val header: String,
    /** 保存 hunk 内的全部行（含 `+`/`-`/空格前缀）。 */
    val lines: List<String> = emptyList(),
)

/**
 * 描述一个 Git 变更文件，包含新旧路径、变更类型与代码块列表。
 */
data class GitChangedFile(
    /** 保存旧路径，删除/重命名前路径。 */
    val oldPath: String?,
    /** 保存新路径，新增/重命名后路径。 */
    val newPath: String?,
    /** 保存文件级变更类型。 */
    val changeKind: GitChangeKind,
    /** 保存该文件包含的代码块列表。 */
    val hunks: List<GitHunk>,
    /** 保存 rename/copy 时 Git 给出的相似度百分比。 */
    val similarity: Int? = null,
)

/**
 * 通过 `git` 命令行获取变更集，并把 unified diff 解析为结构化 [GitChangedFile]。
 *
 * 同时覆盖工作区改动、暂存区改动与未跟踪文件，便于上层按统一格式处理。
 */
open class GitChangeSetProvider(
    /** 保存项目根路径，用于定位 Git 仓库。 */
    private val projectBasePath: String?,
    /** Git 可执行文件路径；测试可替换为 fake git 脚本。 */
    private val gitExecutable: String = "git",
    /** 单个 git 子进程允许执行的最长时间。 */
    private val gitTimeoutMillis: Long = DEFAULT_GIT_TIMEOUT_MILLIS,
    /** 单个 git 子进程允许返回的最大字符数。 */
    private val maxGitOutputChars: Int = DEFAULT_MAX_GIT_OUTPUT_CHARS,
    /** 未跟踪文件被整文件构造成 hunk 时允许读取的最大字符数。 */
    private val maxUntrackedFileChars: Int = DEFAULT_MAX_UNTRACKED_FILE_CHARS,
) {
    /**
     * 返回工作区完整变更集，包含 staged、unstaged 与未跟踪文件。
     */
    open fun workingTreeChangeSet(selectedPaths: List<String> = emptyList()): List<GitChangedFile> =
        (
            unifiedDiff(selectedPaths)
                ?.let(::parseUnifiedDiff)
                .orEmpty() +
                untrackedChangeSet(selectedPaths)
            )
            .mergeChangedFiles()

    /**
     * 返回暂存区变更集。
     */
    fun stagedChangeSet(selectedPaths: List<String> = emptyList()): List<GitChangedFile> =
        stagedUnifiedDiff(selectedPaths)
            ?.let(::parseUnifiedDiff)
            .orEmpty()
            .mergeChangedFiles()

    /**
     * 合并未暂存与已暂存的 unified diff 文本，得到完整 diff。
     */
    fun unifiedDiff(selectedPaths: List<String> = emptyList()): String? {
        return listOfNotNull(
            unstagedUnifiedDiff(selectedPaths),
            stagedUnifiedDiff(selectedPaths),
        )
            .filter { text -> text.isNotBlank() && text.contains("diff --git") }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }

    /**
     * 返回暂存区的 unified diff 文本。
     */
    fun stagedUnifiedDiff(selectedPaths: List<String> = emptyList()): String? =
        diff(selectedPaths, staged = true)

    /**
     * 返回工作区（未暂存）的 unified diff 文本。
     */
    private fun unstagedUnifiedDiff(selectedPaths: List<String> = emptyList()): String? =
        diff(selectedPaths, staged = false)

    /**
     * 把未跟踪文件构造成“新增”型变更集，使它们能与 diff 文件一起被处理。
     */
    private fun untrackedChangeSet(selectedPaths: List<String>): List<GitChangedFile> {
        val basePath = projectBasePath?.takeIf(String::isNotBlank) ?: return emptyList()
        val command = buildList {
            add(gitExecutable)
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
                // 跳过越界、目录、symlink 或其他非普通文件，避免误读仓库外内容。
                if (!file.startsWith(base) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    return@mapNotNull null
                }
                if (Files.size(file) > maxUntrackedFileChars) {
                    return@mapNotNull null
                }
                val text = runCatching { Files.readString(file) }.getOrNull().orEmpty()
                val lineCount = text.lineSequence().count().coerceAtLeast(1)
                GitChangedFile(
                    oldPath = null,
                    newPath = relativePath,
                    changeKind = GitChangeKind.ADDED,
                    // 整个文件视为一个 hunk，按 unified diff 风格构造行内容。
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

    /**
     * 执行 `git diff` 并返回 unified diff 文本。
     *
     * 启用 rename/copy 检测，并放大上下文行数以提升后续符号命中率。
     */
    private fun diff(
        selectedPaths: List<String>,
        staged: Boolean,
    ): String? {
        val basePath = projectBasePath?.takeIf(String::isNotBlank) ?: return null
        val command = buildList {
            add(gitExecutable)
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

    /**
     * 读取 HEAD 版本中指定路径的文件内容，供基线符号提取使用。
     */
    open fun readHeadFile(path: String): String? {
        val basePath = projectBasePath?.takeIf(String::isNotBlank) ?: return null
        return runGit(basePath, listOf(gitExecutable, "show", "HEAD:$path"))
            ?.takeIf(String::isNotBlank)
    }

    /**
     * 在项目根目录下执行 git 命令，仅当退出码为 0 时返回输出文本。
     */
    private fun runGit(
        basePath: String,
        command: List<String>,
    ): String? =
        runCatching {
            val process = ProcessBuilder(command)
                .directory(Path.of(basePath).toFile())
                .redirectErrorStream(true)
                .start()

            val outputFuture = CompletableFuture.supplyAsync<String?> {
                process.inputStream.bufferedReader().use { reader ->
                    reader.readTextBounded(maxGitOutputChars)
                }
            }
            val finished = process.waitFor(gitTimeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.inputStream.close()
                outputFuture.cancel(true)
                return@runCatching null
            }

            val text = outputFuture.get(1, TimeUnit.SECONDS) ?: return@runCatching null
            val exitCode = process.exitValue()
            text.takeIf { exitCode == 0 }
        }.getOrNull()

    companion object {
        /**
         * 解析 unified diff 文本为 [GitChangedFile] 列表。
         *
         * 通过逐行扫描识别 diff 头、rename/copy 标记、文件路径以及 hunk，
         * 把流式状态汇总为可变结构后再转为不可变结果。
         */
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
                        // 进入新文件 diff 段，先把上一段未提交的 hunk 与文件提交。
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
                        // 遇到新 hunk 时先把上一个 hunk 提交，再开始新的 hunk 累积行内容。
                        currentHunk?.let { hunk -> current?.hunks?.add(hunk.toImmutable()) }
                        currentHunk = parseHunkHeader(line)
                    }
                    currentHunk != null -> currentHunk?.lines?.add(line)
                }
            }
            // 文本结束后把最后一段 hunk 与文件提交。
            currentHunk?.let { hunk -> current?.hunks?.add(hunk.toImmutable()) }
            current?.let { files += it }
            return files.map { file -> file.toImmutable() }
        }

        /**
         * 解析 `@@ -start,count +start,count @@` 形式的 hunk 头。
         */
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

        /**
         * 把 diff header 中的路径段还原为真实路径，去掉 `a/`、`b/` 前缀并忽略 `/dev/null`。
         */
        private fun String.toDiffPath(): String? =
            trim()
                .removePrefix("a/")
                .removePrefix("b/")
                .takeUnless { it == "/dev/null" }
                ?.takeIf(String::isNotBlank)

        /** 匹配 unified diff 中的 `@@ -start,count +start,count @@` 行。 */
        private val HUNK_PATTERN = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
    }
}

private fun Reader.readTextBounded(maxChars: Int): String? {
    val buffer = CharArray(8192)
    val output = StringBuilder()
    while (true) {
        val read = read(buffer)
        if (read <= 0) {
            return output.toString()
        }
        output.append(buffer, 0, read)
        if (output.length > maxChars) {
            return null
        }
    }
}

/**
 * 把同名文件的多个变更项合并为单个变更文件，便于上层统一处理。
 */
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

/**
 * 解析过程中用于累积单个文件状态的临时可变结构。
 */
private data class MutableGitChangedFile(
    var oldPath: String?,
    var newPath: String?,
    var changeKind: GitChangeKind = GitChangeKind.MODIFIED,
    var similarity: Int? = null,
    val hunks: MutableList<GitHunk> = mutableListOf(),
) {
    /**
     * 转换为不可变 [GitChangedFile]，并在没有显式变更类型时按路径推断类型。
     */
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

/**
 * 解析过程中用于累积单个 hunk 状态的临时可变结构。
 */
private data class MutableGitHunk(
    val oldStart: Int?,
    val oldLineCount: Int?,
    val newStart: Int?,
    val newLineCount: Int?,
    val header: String,
    val lines: MutableList<String> = mutableListOf(),
) {
    /**
     * 转换为不可变 [GitHunk]。
     */
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
