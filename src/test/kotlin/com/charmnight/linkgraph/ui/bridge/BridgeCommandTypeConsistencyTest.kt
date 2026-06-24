package com.charmnight.linkgraph.ui.bridge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2-5：桥接命令类型在 TS 端（[BridgeCommandType] union）和 Kotlin 端
 * （[BridgeCommandParser.parseMessage] when 分支）目前是两份手维护的字面量，
 * 任一方新增命令忘了同步另一方就会导致"前端发了 IDE 不认识"或"IDE 等得到前端不发"。
 *
 * 本测试以静态文本扫描方式校验两侧命令集合一致，避免 silent drift。
 *
 * 长期方案是从 protocol/graph-editor-transport-contract.json 双端生成类型（见 P2-5 doc），
 * 但当前 contract 文件只覆盖 incremental transport，未覆盖 bridge command；在 contract 扩展前
 * 用本一致性测试作为最低成本防线。
 */
class BridgeCommandTypeConsistencyTest {
    @Test
    fun tsBridgeCommandTypeUnionMatchesKotlinParseMessageBranches() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val kotlinSource = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/bridge/BridgeCommandParser.kt"),
        )
        val tsSource = Files.readString(
            projectRoot.resolve("web/src/app/api.ts"),
        )

        val kotlinCommandTypes = extractKotlinWhenBranchTypes(kotlinSource)
        val tsCommandTypes = extractTsUnionMembers(tsSource, "BridgeCommandType")

        assertTrue(
            kotlinCommandTypes.isNotEmpty(),
            "应至少提取到 Kotlin parseMessage 的 when 分支类型；提取失败时检查 BridgeCommandParser.kt 结构。",
        )
        assertTrue(
            tsCommandTypes.isNotEmpty(),
            "应至少提取到 TS BridgeCommandType union 成员；提取失败时检查 api.ts 结构。",
        )

        assertEquals(
            kotlinCommandTypes.sorted(),
            tsCommandTypes.sorted(),
            "BridgeCommandType 在 Kotlin 与 TS 两端必须保持一致；差异见上。",
        )
    }

    /**
     * 从 BridgeCommandParser.kt 提取 parseMessage 函数体内的 when 分支字面量。
     * 必须先把 parseMessage 函数体切出来，否则会误匹配 parseAssistantComposerTarget 等同文件
     * 其他 when 分支里的 PascalCase 字面量（如 "NewTask"、"QaRecovery"）。
     */
    private fun extractKotlinWhenBranchTypes(source: String): Set<String> {
        val fnStart = source.indexOf("fun parseMessage(")
        if (fnStart < 0) return emptySet()
        val fnEnd = source.indexOf("\n    private fun ", fnStart + 1)
        val body = if (fnEnd < 0) source.substring(fnStart) else source.substring(fnStart, fnEnd)
        val pattern = Regex("""^\s*"([a-zA-Z]+)"\s*->""", RegexOption.MULTILINE)
        return pattern.findAll(body).map { it.groupValues[1] }.toSet()
    }

    /**
     * 从 api.ts 提取指定 type alias 的字面量 union 成员。
     * 匹配 `export type X =\n  | "a"\n  | "b"\n  ...;` 形式。
     * 要求 `|` 必须在行首（前面只有空白），避免误匹配对象类型里的 union（如 preset: "A" | "B"）。
     */
    private fun extractTsUnionMembers(source: String, typeName: String): Set<String> {
        val anchor = "export type $typeName ="
        val startIndex = source.indexOf(anchor)
        if (startIndex < 0) return emptySet()
        val endIndex = source.indexOf(';', startIndex)
        if (endIndex < 0) return emptySet()
        val body = source.substring(startIndex, endIndex)
        val pattern = Regex("""^\s*\|\s*"([a-zA-Z]+)""", RegexOption.MULTILINE)
        return pattern.findAll(body).map { it.groupValues[1] }.toSet()
    }
}
