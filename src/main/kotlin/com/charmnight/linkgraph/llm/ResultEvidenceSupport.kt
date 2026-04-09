package com.charmnight.linkgraph.llm

/**
 * 解析远程结果中的结构化证据条目。
 */
internal fun parseResultEvidenceFindings(raw: Any?): List<ResultEvidenceFinding> {
    // 顶层只接受数组结构，数组中的每一项再按证据条目对象解析。
    return (raw as? List<*>).orEmpty().mapNotNull { item ->
        parseResultEvidenceFinding(item as? Map<*, *>)
    }
}

/**
 * 解析单条证据结论。
 */
private fun parseResultEvidenceFinding(raw: Map<*, *>?): ResultEvidenceFinding? {
    // claim 是最核心字段，缺失时整条证据无效。
    raw ?: return null
    /** 证据结论文本。 */
    val claim = raw["claim"] as? String ?: return null
    /** 证据结论标识。 */
    val id = raw["id"] as? String ?: claim
    /** 证据等级。 */
    val evidenceLevel = enumValueByName<ResultEvidenceLevel>(raw["evidenceLevel"] as? String)
        ?: ResultEvidenceLevel.NOT_OBSERVED
    return ResultEvidenceFinding(
        id = id,
        claim = claim,
        evidenceLevel = evidenceLevel,
        references = (raw["references"] as? List<*>).orEmpty().mapNotNull { reference ->
            parseResultEvidenceReference(reference as? Map<*, *>)
        },
    )
}

/**
 * 解析单条证据引用。
 */
private fun parseResultEvidenceReference(raw: Map<*, *>?): ResultEvidenceReference? {
    raw ?: return null
    // 引用既可能来自节点，也可能来自文件行号，因此字段允许部分缺失。
    /** 关联节点 ID。 */
    val nodeId = raw["nodeId"] as? String
    /** 关联源码文件路径。 */
    val filePath = raw["filePath"] as? String
    /** 起始行号。 */
    val startLine = (raw["startLine"] as? Number)?.toInt() ?: (raw["startLine"] as? String)?.toIntOrNull()
    /** 结束行号。 */
    val endLine = (raw["endLine"] as? Number)?.toInt() ?: (raw["endLine"] as? String)?.toIntOrNull()
    if (nodeId == null && filePath == null && startLine == null && endLine == null) {
        return null
    }
    return ResultEvidenceReference(
        nodeId = nodeId,
        filePath = filePath,
        startLine = startLine,
        endLine = endLine,
    )
}

/**
 * 按名称解析枚举值。
 */
internal inline fun <reified T : Enum<T>> enumValueByName(name: String?): T? {
    return enumValues<T>().firstOrNull { it.name == name }
}
