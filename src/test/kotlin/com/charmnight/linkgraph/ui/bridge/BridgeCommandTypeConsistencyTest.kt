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
 * 扩展校验 payload key 集合——Kotlin parser 在每个 when 分支里通过
 * `payload.string("xxx")` / `payload.requiredString("xxx", ...)` / `payload.stringList("xxx")`
 * / `payload.enum("xxx")` 等读 payload 字段；如果某次重构漏改或新增字段未同步 TS，
 * 就会出现「TS 发的 key Kotlin 不读」或「Kotlin 期待 key TS 不发」。
 * 本测试从 parseMessage 函数体抽取每条命令实际读到的 payload key 集合，
 * 与 BridgeCommandPayloadContract.kt 里显式声明的契约做 diff，让 payload 演化可见、可审。
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
     * 每个命令在 Kotlin parser 中实际读到的 payload key 集合，必须与
     * [BridgeCommandPayloadContract] 显式声明的契约匹配。
     *
     * 契约文件是手动维护的文档；本测试用 regex 从 parser 源码提取实际 key，
     * 把两份手维护列表做 diff，让 payload 演化强制走「改契约 + 改 parser」双路径。
     */
    @Test
    fun kotlinParserPayloadKeysMatchContract() {
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val kotlinSource = Files.readString(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/bridge/BridgeCommandParser.kt"),
        )
        val actualKeysByCommand = extractKotlinPayloadKeysByCommand(kotlinSource)
        assertTrue(
            actualKeysByCommand.isNotEmpty(),
            "应至少提取到一条命令的 payload key；提取失败时检查 BridgeCommandParser.kt 结构。",
        )

        val contractKeysByCommand = BridgeCommandPayloadContract.expectedPayloadKeysByCommand
        // 契约里声明了某命令，但 parser 没读到任何 key —— 契约过时或 parser 退化
        val contractButNotParsed = contractKeysByCommand.filter { (cmd, _) -> cmd !in actualKeysByCommand }
        assertTrue(
            contractButNotParsed.isEmpty(),
            "契约中声明了 ${contractButNotParsed.size} 个命令但 parser 没读到对应分支：$contractButNotParsed",
        )

        // 命令两侧声明的 key 集合不同——契约或 parser 之一改了但没同步另一边
        val mismatches = mutableMapOf<String, Pair<Set<String>, Set<String>>>()
        actualKeysByCommand.forEach { (cmd, actualKeys) ->
            val contractKeys = contractKeysByCommand[cmd].orEmpty()
            if (actualKeys != contractKeys) {
                mismatches[cmd] = actualKeys to contractKeys
            }
        }
        assertTrue(
            mismatches.isEmpty(),
            buildString {
                appendLine("Parser 实际读到的 payload key 与契约不一致：")
                mismatches.forEach { (cmd, pair) ->
                    val (actual, contract) = pair
                    appendLine("  $cmd:")
                    appendLine("    parser 实际: ${actual.sorted()}")
                    appendLine("    契约声明  : ${contract.sorted()}")
                    appendLine("    仅 parser : ${(actual - contract).sorted()}")
                    appendLine("    仅契约    : ${(contract - actual).sorted()}")
                }
                appendLine("改 payload 时必须同时改 parser 与 BridgeCommandPayloadContract.kt。")
            },
        )
    }

    /**
     * 从 BridgeCommandParser.kt 提取 parseMessage 函数体内的 when 分支字面量。
     * 必须先把 parseMessage 函数体切出来，否则会误匹配 parseAssistantComposerTarget 等同文件
     * 其他 when 分支里的 PascalCase 字面量（如 "NewTask"、"QaRecovery"）。
     *
     * 函数体用 [KotlinSourceExtractor.extractFunction] 按括号配对提取，不依赖缩进格式——
     * ktlint 改 2-space / 函数变 top-level 都不影响。
     */
    private fun extractKotlinWhenBranchTypes(source: String): Set<String> {
        val body = KotlinSourceExtractor.extractFunction(source, "parseMessage") ?: return emptySet()
        val pattern = Regex("""^\s*"([a-zA-Z]+)"\s*->""", RegexOption.MULTILINE)
        return pattern.findAll(body).map { it.groupValues[1] }.toSet()
    }

    /**
     * 从 parseMessage 函数体抽取每个命令分支实际读到的 payload key 集合。
     *
     * 匹配的 key 提取模式（覆盖 parser 当前所有读 payload 的方式）：
     * - `payload.string("xxx")` / `payload.requiredString("xxx", ...)`
     * - `payload.stringList("xxx")`
     * - `payload.enum<...>("xxx")` / `payload.enum("xxx")` / `payload.enumOrDefault("xxx", ...)`
     * - `payload.enumOrNull<...>("xxx")`
     * - `payload["xxx"]`（如 raw map 访问）
     *
     * 每个命令分支用 `"cmd" ->` 到下一个 `"cmd" ->`（或函数结束）之间限定。
     */
    private fun extractKotlinPayloadKeysByCommand(source: String): Map<String, Set<String>> {
        val body = KotlinSourceExtractor.extractFunction(source, "parseMessage") ?: return emptyMap()

        val branchPattern = Regex("""^\s*"([a-zA-Z]+)"\s*->""", RegexOption.MULTILINE)
        val branches = branchPattern.findAll(body).toList()
        if (branches.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, Set<String>>()
        // 命中各种 payload key 访问形式，提取第一个字符串字面量作为 key
        val keyAccessPattern = Regex(
            """payload\.(?:string|requiredString|stringList|enum|enumOrDefault|enumOrNull)(?:<[^>]+>)?\(\s*"?([a-zA-Z][a-zA-Z0-9_]*)"?\s*[,)]""",
        )
        val rawAccessPattern = Regex("""payload\[\s*"([a-zA-Z][a-zA-Z0-9_]*)"\s*]""")

        for (i in branches.indices) {
            val cmd = branches[i].groupValues[1]
            val branchStart = branches[i].range.last + 1
            val branchEnd = if (i + 1 < branches.size) branches[i + 1].range.first else body.length
            val branchBody = body.substring(branchStart, branchEnd)
            val keys = mutableSetOf<String>()
            keyAccessPattern.findAll(branchBody).mapTo(keys) { it.groupValues[1] }
            rawAccessPattern.findAll(branchBody).mapTo(keys) { it.groupValues[1] }
            // 仅记录至少读了 1 个 key 的命令——某些命令（如 ExportMermaid）确实无 payload，不进契约
            if (keys.isNotEmpty()) {
                result[cmd] = keys
            }
        }
        return result
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
