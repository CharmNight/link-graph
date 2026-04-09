package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * 负责把 XML 文件当前位置解码为 XML、配置项或 MyBatis 资源主题。
 */
class XmlSubjectDecoder(
    /** 保存资源锚点解析器。 */
    private val anchorParser: ResourceAnchorParser = ResourceAnchorParser(),
) : ResourceDecoder {
    /** 匹配 XML 中的 property 配置项。 */
    private val xmlPropertyRegex = Regex(
        "<property\\b[^>]*name\\s*=\\s*\"([^\"]+)\"[^>]*value\\s*=\\s*\"([^\"]+)\"",
        RegexOption.IGNORE_CASE,
    )
    /** 匹配 MyBatis 语句节点。 */
    private val xmlStatementRegex = Regex(
        """<(select|insert|update|delete|sql|resultMap)\b[^>]*id\s*=\s*"([^"]+)"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    /** 匹配 MyBatis mapper 的 namespace。 */
    private val mapperNamespaceRegex = Regex(
        "<mapper\\b[^>]*namespace\\s*=\\s*\"([^\"]+)\"",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 判断当前文件是否为 XML 文件。
     */
    override fun supports(file: PsiFile): Boolean = file.virtualFile?.extension?.lowercase() == "xml"

    /**
     * XML 当前阶段允许整文件预览。
     */
    override fun preview(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): Boolean = true

    /**
     * 根据当前位置优先解析 MyBatis 语句、配置项，否则回退为普通 XML 资源。
     */
    override fun decode(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): ResourceSubjectHandle? {
        val text = file.text
        val path = sourcePathOf(file)
        // 优先识别更具体的 MyBatis 语句资源。
        resolveMyBatisStatement(file, text, path, caretOffset)?.let { return it }
        xmlPropertyRegex.findAll(text)
            .firstOrNull { match -> caretOffset in match.range }
            ?.let { match ->
                // 光标命中 property 节点时，解析为配置项主题。
                val key = match.groupValues[1]
                val value = match.groupValues[2].trim()
                return ResourceSubjectHandle(
                    subjectId = stableSubjectId("resource-config", "$path:$key"),
                    sourcePath = path,
                    sourceRange = sourceRangeOf(file, TextRange(match.range.first, match.range.last + 1)),
                    displayName = key,
                    kind = ResourceSubjectKind.CONFIG_ITEM,
                    resourceAnchor = anchorParser.parse(value),
                    attributes = mapOf(
                        "key" to key,
                        "value" to value,
                    ),
                )
            }
        // 其他 XML 位置统一回退为普通 XML 资源主题。
        val fallbackRange = lineRange(editor.document, caretOffset) ?: TextRange(0, file.textLength.coerceAtLeast(0))
        return ResourceSubjectHandle(
            subjectId = stableSubjectId("resource-xml", path),
            sourcePath = path,
            sourceRange = sourceRangeOf(file, fallbackRange),
            displayName = file.name,
            kind = ResourceSubjectKind.XML_RESOURCE,
            attributes = mapOf("path" to path),
        )
    }

    /**
     * 解析光标所在位置是否命中 MyBatis 语句节点。
     */
    private fun resolveMyBatisStatement(
        file: PsiFile,
        text: String,
        path: String,
        caretOffset: Int,
    ): ResourceSubjectHandle? {
        // 没有 namespace 时说明当前 XML 不是标准 MyBatis mapper。
        val namespace = mapperNamespaceRegex.find(text)?.groupValues?.getOrNull(1) ?: return null
        val statementMatch = xmlStatementRegex.findAll(text)
            .firstOrNull { match -> xmlStatementContainsOffset(match, text, caretOffset) }
            ?: return null
        // 命中语句时构造 MyBatis 语句主题，并把 namespace 与 statementId 写入属性。
        val statementType = statementMatch.groupValues[1].lowercase()
        val statementId = statementMatch.groupValues[2]
        val ownerName = namespace.substringAfterLast('.')
        return ResourceSubjectHandle(
            subjectId = stableSubjectId("resource-mybatis", "$namespace.$statementId"),
            sourcePath = path,
            sourceRange = sourceRangeOf(file, TextRange(statementMatch.range.first, statementMatch.range.last + 1)),
            displayName = "SQL $ownerName.$statementId",
            kind = ResourceSubjectKind.MYBATIS_STATEMENT,
            resourceAnchor = ResourceAnchor(
                ownerName = namespace,
                methodName = statementId,
            ),
            attributes = mapOf(
                "namespace" to namespace,
                "statementId" to statementId,
                "statementType" to statementType,
            ),
        )
    }

    /**
     * 判断光标是否位于某个 XML 语句块内。
     */
    private fun xmlStatementContainsOffset(
        match: MatchResult,
        text: String,
        offset: Int,
    ): Boolean {
        // 光标落在开始标签内时直接算命中。
        val openRange = match.range
        if (offset in openRange) {
            return true
        }
        // 否则继续找对应结束标签，判断是否位于完整语句块范围内。
        val statementType = match.groupValues[1]
        val closeTagRegex = Regex("""</${Regex.escape(statementType)}\s*>""", RegexOption.IGNORE_CASE)
        val closeMatch = closeTagRegex.find(text, openRange.last + 1) ?: return false
        return offset in openRange.first..closeMatch.range.last
    }
}
