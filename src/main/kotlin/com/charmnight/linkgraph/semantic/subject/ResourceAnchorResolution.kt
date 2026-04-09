package com.charmnight.linkgraph.semantic.subject

/**
 * 表示资源锚点解析后的结果。
 */
data class ResourceAnchorResolution(
    /** 保存成功匹配到的代码主题句柄。 */
    val handle: CodeSubjectHandle? = null,
    /** 保存解析状态。 */
    val state: String? = null,
    /** 保存解析提示信息。 */
    val message: String? = null,
    /** 保存候选签名列表。 */
    val candidateSignatures: List<String> = emptyList(),
) {
    /**
     * 将解析结果转换为可挂到图元素上的元数据。
     */
    fun metadata(): Map<String, String> {
        // 没有附加状态、提示和候选时，无需生成任何元数据。
        if (state == null && message == null && candidateSignatures.isEmpty()) {
            return emptyMap()
        }
        // 统一把解析结果写入固定键名，便于前端和调试工具读取。
        return buildMap {
            state?.let { put("linkGraph.anchorResolutionState", it) }
            message?.let { put("linkGraph.anchorResolutionHint", it) }
            if (candidateSignatures.isNotEmpty()) {
                put("linkGraph.anchorCandidates", candidateSignatures.joinToString("\n"))
                put("linkGraph.anchorCandidateCount", candidateSignatures.size.toString())
            }
        }
    }
}
