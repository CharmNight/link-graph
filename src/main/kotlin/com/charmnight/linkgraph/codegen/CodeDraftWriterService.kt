package com.charmnight.linkgraph.codegen

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * 把生成好的代码草案显式写入项目目录。
 * 一期默认只创建不存在的文件，避免静默覆盖用户已有代码。
 */
class CodeDraftWriterService(
    project: Project? = null,
) {
    private val codeEditApplyService = project?.let { CodeEditApplyService(it) }

    /** 把生成的代码草稿写入项目目录，并汇总写入结果。 */
    fun writeDrafts(
        projectBasePath: String?,
        drafts: List<GeneratedCodeDraft>,
    ): GeneratedCodeDraftWriteReport {
        if (projectBasePath.isNullOrBlank()) {
            return GeneratedCodeDraftWriteReport(
                warnings = listOf("项目根路径不可用，无法写入草稿。"),
            )
        }

        /** 项目根目录的标准化路径。 */
        val basePath = Path.of(projectBasePath).normalize()
        /** 实际写入成功的文件列表。 */
        val writtenFiles = mutableListOf<String>()
        /** 因安全或冲突原因被跳过的文件列表。 */
        val skippedFiles = mutableListOf<String>()
        /** 写入阶段产生的警告信息。 */
        val warnings = mutableListOf<String>()

        drafts.forEach { draft ->
            val normalizedDraft = ProjectPathNormalizer.normalizeDraft(draft, projectBasePath)
            /** 当前草稿解析出的目标文件路径。 */
            val target = basePath.resolve(normalizedDraft.targetPath).normalize()
            if (!target.startsWith(basePath)) {
                skippedFiles += normalizedDraft.targetPath
                warnings += "已跳过 '${normalizedDraft.targetPath}'，因为它解析到了项目目录之外。"
                return@forEach
            }
            if (Files.exists(target)) {
                if (normalizedDraft.content != null && Files.isRegularFile(target) && Files.readString(target) == normalizedDraft.content) {
                    refreshFile(target)
                    writtenFiles += normalizedDraft.targetPath
                    return@forEach
                }
                if (normalizedDraft.editOperations.isNotEmpty()) {
                    val beforeText = Files.readString(target)
                    val applyService = codeEditApplyService
                    if (applyService == null) {
                        skippedFiles += normalizedDraft.targetPath
                        warnings += "已跳过 '${normalizedDraft.targetPath}'，因为 scope-safe apply 需要 IDE project 上下文。"
                        return@forEach
                    }
                    val applyResult = applyService.applyToText(
                        filePath = normalizedDraft.targetPath,
                        beforeText = beforeText,
                        operations = normalizedDraft.editOperations,
                        editScopes = normalizedDraft.editScopes,
                    )
                    warnings += applyResult.warnings
                    if (!applyResult.applied) {
                        skippedFiles += normalizedDraft.targetPath
                        return@forEach
                    }
                    Files.writeString(target, applyResult.updatedText)
                    refreshFile(target)
                    writtenFiles += normalizedDraft.targetPath
                    return@forEach
                }
                skippedFiles += normalizedDraft.targetPath
                warnings += if (normalizedDraft.editScopes.isEmpty()) {
                    "已跳过 '${normalizedDraft.targetPath}'，因为 existing-file writeback 缺少 validated scope。"
                } else {
                    "已跳过 '${normalizedDraft.targetPath}'，因为 scope-safe existing-file writeback 尚未启用。"
                }
                return@forEach
            }
            target.parent?.let(Files::createDirectories)
            Files.writeString(target, normalizedDraft.content ?: "")
            refreshFile(target)
            writtenFiles += normalizedDraft.targetPath
        }

        return GeneratedCodeDraftWriteReport(
            writtenFiles = writtenFiles,
            skippedFiles = skippedFiles,
            warnings = warnings,
        )
    }

    /** 将新增 import 合并进文件头部，并保持排序。 */
    private fun mergeImports(
        content: String,
        importsToAdd: List<String>,
    ): String {
        if (importsToAdd.isEmpty()) {
            return content
        }
        /** 合并去重后的 import 列表。 */
        val mergedImports = (extractImportLines(content) + importsToAdd).distinct().sorted()
        /** 原文件中已有的 import 片段。 */
        val importMatches = IMPORT_LINE.findAll(content).toList()
        return if (importMatches.isNotEmpty()) {
            /** 原 import 区块起始位置。 */
            val start = importMatches.first().range.first
            /** 原 import 区块结束位置。 */
            val end = importMatches.last().range.last + 1
            buildString(content.length + importsToAdd.sumOf { it.length + 1 }) {
                append(content.substring(0, start).trimEnd())
                append("\n\n")
                append(mergedImports.joinToString("\n"))
                append("\n\n")
                append(content.substring(end).trimStart('\n', '\r'))
            }
        } else {
            /** package 声明所在位置，用于决定插入点。 */
            val packageMatch = PACKAGE_LINE.find(content)
            /** import 新区块的插入位置。 */
            val insertionPoint = packageMatch?.range?.last?.plus(1) ?: 0
            buildString(content.length + mergedImports.sumOf { it.length + 1 } + 4) {
                if (insertionPoint > 0) {
                    append(content.substring(0, insertionPoint).trimEnd())
                    append("\n\n")
                }
                append(mergedImports.joinToString("\n"))
                append("\n\n")
                append(content.substring(insertionPoint).trimStart('\n', '\r'))
            }
        }
    }

    /** 提取文件中的所有 import 行。 */
    private fun extractImportLines(content: String): List<String> {
        return IMPORT_LINE.findAll(content).map { it.value.trim() }.toList()
    }

    /** 在类声明前插入 Javadoc。 */
    private fun insertClassDoc(
        content: String,
        declarationStart: Int,
        docBlock: String,
    ): String {
        return buildString(content.length + docBlock.length + 2) {
            append(content.substring(0, declarationStart).trimEnd())
            append("\n\n")
            append(docBlock.trim())
            append("\n")
            append(content.substring(declarationStart))
        }
    }

    /** 把新增字段和方法插入到顶层类型内部。 */
    private fun mergeMembersIntoType(
        content: String,
        fieldBlocks: List<String>,
        methodBlocks: List<String>,
    ): String {
        /** 目标文件中的顶层类型块。 */
        val typeBlock = findPrimaryTypeBlock(content) ?: return content
        /** 类型体起始位置。 */
        val bodyStart = typeBlock.bodyStart + 1
        /** 类型体结束位置。 */
        val bodyEnd = typeBlock.bodyEnd
        /** 原有类型体内容。 */
        val existingBody = content.substring(bodyStart, bodyEnd).trim('\n', '\r')
        /** 需要拼接进类型体的分段内容。 */
        val sections = buildList {
            if (fieldBlocks.isNotEmpty()) {
                add(fieldBlocks.joinToString("\n\n") { indentMemberBlock(it) })
            }
            if (existingBody.isNotEmpty()) {
                add(existingBody)
            }
            if (methodBlocks.isNotEmpty()) {
                add(methodBlocks.joinToString("\n\n") { indentMemberBlock(it) })
            }
        }
        /** 合并后的类型体内容。 */
        val mergedBody = if (sections.isEmpty()) {
            "\n"
        } else {
            "\n" + sections.joinToString("\n\n") + "\n"
        }
        return buildString(content.length + fieldBlocks.sumOf { it.length } + methodBlocks.sumOf { it.length } + 8) {
            append(content.substring(0, bodyStart))
            append(mergedBody)
            append(content.substring(bodyEnd))
        }
    }

    /** 从顶层类型体中提取字段和方法成员。 */
    private fun extractTopLevelMembers(content: String): List<JavaMember> {
        /** 当前文件中的顶层类型块。 */
        val typeBlock = findPrimaryTypeBlock(content) ?: return emptyList()
        /** 只截取顶层类型的大括号内部内容。 */
        val body = content.substring(typeBlock.bodyStart + 1, typeBlock.bodyEnd)
        /** 识别出的成员列表。 */
        val members = mutableListOf<JavaMember>()
        /** 当前成员块的起始下标。 */
        var memberStart = -1
        /** 花括号嵌套深度。 */
        var depth = 0
        /** 是否正在行注释中。 */
        var inLineComment = false
        /** 是否正在块注释中。 */
        var inBlockComment = false
        /** 是否正在字符串字面量中。 */
        var inString = false
        /** 是否正在字符字面量中。 */
        var inChar = false
        /** 当前扫描游标。 */
        var index = 0
        while (index < body.length) {
            /** 当前字符。 */
            val char = body[index]
            /** 当前字符的下一个字符。 */
            val next = body.getOrNull(index + 1)

            if (inLineComment) {
                if (char == '\n') {
                    inLineComment = false
                }
                index++
                continue
            }
            if (inBlockComment) {
                if (char == '*' && next == '/') {
                    inBlockComment = false
                    index += 2
                    continue
                }
                index++
                continue
            }
            if (inString) {
                if (char == '"' && body.getOrNull(index - 1) != '\\') {
                    inString = false
                }
                index++
                continue
            }
            if (inChar) {
                if (char == '\'' && body.getOrNull(index - 1) != '\\') {
                    inChar = false
                }
                index++
                continue
            }
            if (char == '/' && next == '/') {
                if (depth == 0 && memberStart < 0) {
                    memberStart = index
                }
                inLineComment = true
                index += 2
                continue
            }
            if (char == '/' && next == '*') {
                if (depth == 0 && memberStart < 0) {
                    memberStart = index
                }
                inBlockComment = true
                index += 2
                continue
            }
            if (char == '"') {
                inString = true
                index++
                continue
            }
            if (char == '\'') {
                inChar = true
                index++
                continue
            }
            if (depth == 0 && memberStart < 0 && !char.isWhitespace()) {
                memberStart = index
            }

            when (char) {
                '{' -> depth++
                '}' -> depth--
                ';' -> {
                    if (depth == 0 && memberStart >= 0) {
                        createMember(
                            rawBlock = body.substring(memberStart, index + 1),
                            startIndex = typeBlock.bodyStart + 1 + memberStart,
                            endIndexExclusive = typeBlock.bodyStart + 1 + index + 1,
                        )?.let(members::add)
                        memberStart = -1
                    }
                }
            }

            if (char == '}' && depth == 0 && memberStart >= 0) {
                createMember(
                    rawBlock = body.substring(memberStart, index + 1),
                    startIndex = typeBlock.bodyStart + 1 + memberStart,
                    endIndexExclusive = typeBlock.bodyStart + 1 + index + 1,
                )?.let(members::add)
                memberStart = -1
            }
            index++
        }
        return members
    }

    /** 根据原始代码块识别其属于字段还是方法成员。 */
    private fun createMember(
        rawBlock: String,
        startIndex: Int,
        endIndexExclusive: Int,
    ): JavaMember? {
        /** 去除首尾空白后的成员文本。 */
        val trimmed = rawBlock.trim()
        if (trimmed.isBlank() || trimmed.startsWith("static {") || trimmed == "{") {
            return null
        }
        if (TYPE_DECLARATION_IN_MEMBER.containsMatchIn(trimmed)) {
            return null
        }
        return when {
            trimmed.endsWith("}") -> methodSignatureKey(trimmed)?.let {
                JavaMember(trimmed, JavaMemberKind.METHOD, it, startIndex, endIndexExclusive)
            }
            trimmed.endsWith(";") -> fieldKey(trimmed)?.let {
                JavaMember(trimmed, JavaMemberKind.FIELD, it, startIndex, endIndexExclusive)
            }
            else -> null
        }
    }

    /** 提取字段声明的稳定键。 */
    private fun fieldKey(block: String): String? {
        /** 字段声明的最后一行文本。 */
        val declarationLine = block
            .substringBefore('=')
            .lines()
            .map(String::trim)
            .lastOrNull { line ->
                line.isNotBlank() && !line.startsWith("@") && !line.startsWith("*") && !line.startsWith("/*")
            }
            ?.removeSuffix(";")
            ?.trim()
            ?: return null
        /** 字段名候选值。 */
        val candidate = declarationLine.substringAfterLast(' ').substringAfterLast('.')
        return candidate.takeIf { it.matches(IDENTIFIER) }
    }

    /** 提取方法头并规范化为稳定键。 */
    private fun methodSignatureKey(block: String): String? {
        /** 正则命中的方法头文本。 */
        val header = METHOD_HEADER.find(block)?.value ?: return null
        return header.replace(Regex("\\s+"), " ").trim()
    }

    /** 为待插入的成员块补齐一级缩进。 */
    private fun indentMemberBlock(block: String): String {
        return block.lines().joinToString("\n") { line ->
            if (line.isBlank()) {
                ""
            } else {
                "    ${line.trimEnd()}"
            }
        }
    }

    /** 按成员范围替换已有 Java 成员块。 */
    private fun replaceMembersInContent(
        content: String,
        replacements: List<Pair<JavaMember, String>>,
    ): String {
        var result = content
        replacements
            .sortedByDescending { (member, _) -> member.startIndex }
            .forEach { (member, replacement) ->
                result = buildString(result.length - (member.endIndexExclusive - member.startIndex) + replacement.length) {
                    append(result.substring(0, member.startIndex))
                    append(indentMemberBlock(replacement))
                    append(result.substring(member.endIndexExclusive))
                }
            }
        return result
    }

    /** 在文件中定位第一个顶层类型块。 */
    private fun findPrimaryTypeBlock(content: String): TypeBlock? {
        /** 顶层类型声明的正则匹配结果。 */
        val declaration = TYPE_DECLARATION.find(content) ?: return null
        /** 类型体起始花括号位置。 */
        val bodyStart = content.indexOf('{', declaration.range.first)
        if (bodyStart < 0) {
            return null
        }
        /** 当前花括号嵌套深度。 */
        var depth = 0
        for (index in bodyStart until content.length) {
            when (content[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return TypeBlock(
                            declarationStart = declaration.range.first,
                            bodyStart = bodyStart,
                            bodyEnd = index,
                            kind = declaration.groupValues[3],
                        )
                    }
                }
            }
        }
        return null
    }

    /** 提取声明前紧邻的 Javadoc 注释块。 */
    private fun extractLeadingJavadoc(
        content: String,
        declarationStart: Int,
    ): String? {
        /** 声明前面的全部文本。 */
        val prefix = content.substring(0, declarationStart)
        /** 最近一个注释结束位置。 */
        val docEnd = prefix.lastIndexOf("*/")
        if (docEnd < 0 || prefix.substring(docEnd + 2).isNotBlank()) {
            return null
        }
        /** 最近一个 Javadoc 起始位置。 */
        val docStart = prefix.lastIndexOf("/**", docEnd)
        if (docStart < 0) {
            return null
        }
        return prefix.substring(docStart, docEnd + 2).trim()
    }

    companion object {
        /** 识别 Java 方法头的正则表达式。 */
        private val METHOD_HEADER = Regex("""(?m)^\s*(public|protected|private)\s+[^{;]+?\)\s*\{""")
        /** 识别 package 声明的正则表达式。 */
        private val PACKAGE_LINE = Regex("""(?m)^\s*package\s+[^;]+;""")
        /** 识别 import 声明的正则表达式。 */
        private val IMPORT_LINE = Regex("""(?m)^\s*import\s+[^;]+;""")
        /** 识别顶层类型声明的正则表达式。 */
        private val TYPE_DECLARATION = Regex("""(?m)^\s*(public\s+|protected\s+|private\s+)?(abstract\s+|final\s+)?(class|interface|enum|record)\s+\w+[^{]*\{""")
        /** 排除成员内部再次声明类型的正则表达式。 */
        private val TYPE_DECLARATION_IN_MEMBER = Regex("""\b(class|interface|enum|record)\b""")
        /** Java 标识符校验正则表达式。 */
        private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    }

    /** 通知 IDE 刷新指定文件。 */
    private fun refreshFile(target: Path) {
        runCatching {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        }
    }

    /** 判断目标文件内容是否与草稿在语义上等价。 */
    private fun hasEquivalentContent(
        target: Path,
        draftContent: String,
    ): Boolean {
        return normalizeText(Files.readString(target)) == normalizeText(draftContent)
    }

    /** 统一文本换行并去除首尾空白。 */
    private fun normalizeText(content: String): String {
        return content.replace("\r\n", "\n").trim()
    }

    /** 描述顶层类型块的位置信息。 */
    private data class TypeBlock(
        /** 类型声明起始位置。 */
        val declarationStart: Int,
        /** 类型体起始花括号位置。 */
        val bodyStart: Int,
        /** 类型体结束花括号位置。 */
        val bodyEnd: Int,
        /** 类型关键字，如 class 或 interface。 */
        val kind: String,
    )

    /** 描述提取出的 Java 成员块。 */
    private data class JavaMember(
        /** 成员的原始文本块。 */
        val rawBlock: String,
        /** 成员分类。 */
        val kind: JavaMemberKind,
        /** 用于去重的稳定键。 */
        val key: String?,
        /** 成员在完整文件中的起始下标。 */
        val startIndex: Int,
        /** 成员在完整文件中的结束下标（开区间）。 */
        val endIndexExclusive: Int,
    )

    /** Java 顶层成员分类。 */
    private enum class JavaMemberKind {
        FIELD,
        METHOD,
    }
}
