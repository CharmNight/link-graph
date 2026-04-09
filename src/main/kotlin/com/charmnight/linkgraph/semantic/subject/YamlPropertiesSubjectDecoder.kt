package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.util.ArrayDeque

/**
 * 负责把 Properties/YAML 文件当前位置解码为配置项资源主题。
 */
class YamlPropertiesSubjectDecoder(
    /** 保存资源锚点解析器。 */
    private val anchorParser: ResourceAnchorParser = ResourceAnchorParser(),
) : ResourceDecoder {
    /** 匹配扁平配置文件中的键值对。 */
    private val configEntryRegex = Regex("""^\s*([A-Za-z0-9_.-]+)\s*[:=]\s*(.+?)\s*$""")
    /** 匹配 YAML 的普通键值行。 */
    private val yamlEntryRegex = Regex("""^(\s*)("[^"]+"|'[^']+'|[A-Za-z0-9_.-]+)\s*:\s*(.*?)\s*$""")
    /** 匹配 YAML 的列表项。 */
    private val yamlListItemRegex = Regex("""^(\s*)-\s*(.*?)\s*$""")

    /**
     * 判断当前文件是否为 Properties 或 YAML 文件。
     */
    override fun supports(file: PsiFile): Boolean {
        return when (file.virtualFile?.extension?.lowercase()) {
            "properties", "yml", "yaml" -> true
            else -> false
        }
    }

    /**
     * 判断当前光标位置是否能解析出配置项。
     */
    override fun preview(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): Boolean {
        return when (file.virtualFile?.extension?.lowercase()) {
            "yml", "yaml" -> resolveYamlConfigEntry(editor, caretOffset) != null
            "properties" -> resolveFlatConfigEntry(editor, caretOffset) != null
            else -> false
        }
    }

    /**
     * 将当前位置解析为配置项主题句柄。
     */
    override fun decode(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): ResourceSubjectHandle? {
        val path = sourcePathOf(file)
        val extension = file.virtualFile?.extension?.lowercase()
        // YAML 与扁平配置的解析路径不同，先按扩展名分流。
        val entry = when (extension) {
            "yml", "yaml" -> resolveYamlConfigEntry(editor, caretOffset)
            else -> resolveFlatConfigEntry(editor, caretOffset)
        } ?: return null
        val range = lineRange(editor.document, caretOffset) ?: return null
        return ResourceSubjectHandle(
            subjectId = stableSubjectId("resource-config", "$path:${entry.key}"),
            sourcePath = path,
            sourceRange = sourceRangeOf(file, range),
            displayName = entry.key,
            kind = ResourceSubjectKind.CONFIG_ITEM,
            resourceAnchor = anchorParser.parse(entry.value),
            attributes = mapOf(
                "key" to entry.key,
                "value" to entry.value,
            ),
        )
    }

    /**
     * 解析扁平配置文件中的当前行。
     */
    private fun resolveFlatConfigEntry(
        editor: Editor,
        caretOffset: Int,
    ): ConfigEntry? {
        val document = editor.document
        val range = lineRange(document, caretOffset) ?: return null
        val lineText = document.getText(range)
        // 只有完整匹配 key=value 或 key: value 才视为配置项。
        val match = configEntryRegex.matchEntire(lineText) ?: return null
        return ConfigEntry(
            key = match.groupValues[1],
            value = match.groupValues[2].trim(),
        )
    }

    /**
     * 解析 YAML 中光标所在位置的配置路径和值。
     */
    private fun resolveYamlConfigEntry(
        editor: Editor,
        caretOffset: Int,
    ): ConfigEntry? {
        val document = editor.document
        if (document.lineCount <= 0) {
            return null
        }
        // 只需要扫描到当前行，逐步维护 YAML 路径栈即可。
        val lineIndex = document.getLineNumber(caretOffset.coerceIn(0, document.textLength.coerceAtLeast(1) - 1))
        val pathFrames = ArrayDeque<YamlPathFrame>()
        val listItemIndexes = linkedMapOf<String, Int>()
        for (index in 0..lineIndex) {
            val lineText = document.getText(
                TextRange(
                    document.getLineStartOffset(index),
                    document.getLineEndOffset(index),
                ),
            )
            val listItemMatch = yamlListItemRegex.matchEntire(lineText)
            if (listItemMatch != null) {
                // 列表项根据缩进更新路径栈，并生成 `[index]` 路径片段。
                val indent = yamlIndentWidth(listItemMatch.groupValues[1])
                val inlineContent = listItemMatch.groupValues[2].trim()
                while (pathFrames.isNotEmpty() && indent <= pathFrames.last().indent) {
                    pathFrames.removeLast()
                }
                val parentPath = composeYamlPath(pathFrames.asSequence().map(YamlPathFrame::segment).toList())
                val itemIndex = listItemIndexes[parentPath] ?: 0
                listItemIndexes[parentPath] = itemIndex + 1
                val itemSegment = "[$itemIndex]"
                pathFrames.addLast(YamlPathFrame(indent, itemSegment))
                if (inlineContent.isBlank()) {
                    if (index == lineIndex) {
                        return ConfigEntry(
                            key = appendYamlSegment(parentPath, itemSegment),
                            value = "",
                        )
                    }
                    continue
                }
                val inlineEntry = yamlEntryRegex.matchEntire("  $inlineContent")
                if (inlineEntry != null) {
                    // 列表项中内联对象时，同时补上对象键路径。
                    val key = normalizeYamlKey(inlineEntry.groupValues[2])
                    val value = inlineEntry.groupValues[3].trim()
                    if (index == lineIndex) {
                        return ConfigEntry(
                            key = appendYamlSegment(appendYamlSegment(parentPath, itemSegment), key),
                            value = value,
                        )
                    }
                    if (value.isBlank()) {
                        pathFrames.addLast(YamlPathFrame(indent + 1, key))
                    }
                } else if (index == lineIndex) {
                    return ConfigEntry(
                        key = appendYamlSegment(parentPath, itemSegment),
                        value = inlineContent,
                    )
                }
                continue
            }
            val match = yamlEntryRegex.matchEntire(lineText) ?: continue
            val indent = yamlIndentWidth(match.groupValues[1])
            val key = normalizeYamlKey(match.groupValues[2])
            val value = match.groupValues[3].trim()
            // 普通 YAML 键值根据缩进深度维护父路径。
            while (pathFrames.isNotEmpty() && indent <= pathFrames.last().indent) {
                pathFrames.removeLast()
            }
            val parentPath = composeYamlPath(pathFrames.asSequence().map(YamlPathFrame::segment).toList())
            val fullPath = appendYamlSegment(parentPath, key)
            if (index == lineIndex) {
                return ConfigEntry(
                    key = fullPath,
                    value = value,
                )
            }
            pathFrames.addLast(YamlPathFrame(indent, key))
        }
        return null
    }

    /**
     * 计算 YAML 缩进宽度。
     */
    private fun yamlIndentWidth(indentText: String): Int {
        return indentText.fold(0) { total, char -> total + if (char == '\t') 2 else 1 }
    }

    /**
     * 把路径片段列表组合成完整 YAML 路径。
     */
    private fun composeYamlPath(segments: List<String>): String {
        return segments.fold("") { path, segment -> appendYamlSegment(path, segment) }
    }

    /**
     * 去掉 YAML 键名外层引号。
     */
    private fun normalizeYamlKey(rawKey: String): String {
        val trimmed = rawKey.trim()
        if (trimmed.length >= 2) {
            if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith('\'') && trimmed.endsWith('\''))) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }

    /**
     * 把新的路径片段追加到已有路径末尾。
     */
    private fun appendYamlSegment(
        path: String,
        segment: String,
    ): String {
        if (path.isBlank()) {
            return segment
        }
        return if (segment.startsWith("[")) "$path$segment" else "$path.$segment"
    }

    /**
     * 表示解析出的配置项。
     */
    private data class ConfigEntry(
        /** 保存完整配置键路径。 */
        val key: String,
        /** 保存配置值。 */
        val value: String,
    )

    /**
     * 表示 YAML 路径栈中的一个片段。
     */
    private data class YamlPathFrame(
        /** 保存当前片段的缩进宽度。 */
        val indent: Int,
        /** 保存路径片段文本。 */
        val segment: String,
    )
}
