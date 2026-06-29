package com.charmnight.linkgraph.ui.bridge

/**
 * M8：Bridge command payload 字段契约——每条命令在 [BridgeCommandParser.parseMessage] 中
 * **直接读**的 payload key 集合。
 *
 * 维护方式：手动维护一份显式契约，与 parser 实际读 key 行为对照。
 * [BridgeCommandTypeConsistencyTest.kotlinParserPayloadKeysMatchContract] 会从 parser 源码
 * 自动抽取实际读到的 key，与本契约做 diff，让 payload 字段演化强制走双路径同步。
 *
 * 长期方案：把本契约下沉到 `protocol/graph-editor-transport-contract.json`，
 * 双端 codegen 生成 TS 类型 + Kotlin sealed 类型，消除手维护成本。
 *
 * 覆盖范围（**只覆盖 parseMessage 函数体内直接命中**）：
 * - 不含无 payload 的命令（如 `exportMermaid` / `showDiffMode`）
 * - 不含通过 helper 函数读取的命令——这些有自己的 schema，测试覆盖不到：
 *   - `requestArtifact`：在 parse() 外层读 `artifactIds`
 *   - `layoutChanged`：通过 `parseLayoutPositions` 读 `positions` 数组
 *   - `requestAssistantTask`：通过 `parseAssistantTask` 复合读 9 个字段
 *   - `requestIndexedGraph` / `applyGraphEditScript`：通过 `GraphBrowserPayloadParser` 委托
 *
 * helper 函数路径的契约需要另开 helper-level 测试，不在本测试范围。
 */
object BridgeCommandPayloadContract {
    /**
     * 每条命令对应的 payload key 集合（顺序无关）。
     *
     * 来源：直接 grep `payload.string(...)` / `payload.stringList(...)` / `payload.enum(...)` /
     * `payload[...]` 在 parseMessage 各 case 分支里的出现位置。
     */
    val expectedPayloadKeysByCommand: Map<String, Set<String>> = mapOf(
        "importMermaid" to setOf("mermaid"),
        "confirmQaCandidateChange" to setOf("changeId"),
        "unconfirmQaCandidateChange" to setOf("changeId"),
        "resolveInvestigationThread" to setOf("threadId", "resolutionStatus", "note"),
        "applyDraftPatchPreview" to setOf("operationIds"),
        "restoreDraftPatchPreview" to setOf("source"),
        "requestAnalysisDisplayMode" to setOf("displayMode"),
        "applySingleCodeDraft" to setOf("draftId"),
        "openCodeDraftNativeDiff" to setOf("draftId"),
        "requestDraftNavigation" to setOf("targetPath"),
        "frontendReady" to setOf("lastAppliedRevision"),
        "snapshotAck" to setOf("revision"),
        "nodeSelected" to setOf("nodeId"),
        "requestSourceNavigation" to setOf("nodeId"),
        "requestExpandOverflowNode" to setOf("nodeId"),
        "requestExpandInvocation" to setOf("nodeId"),
        "requestRemoveInvocationExpansion" to setOf("expansionId"),
    )
}
