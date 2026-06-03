package com.charmnight.linkgraph.llm.runtime

/**
 * 记录一次 run 的预算上限与当前消耗。
 * 预算必须是可测试、可拦截的真实约束，不能只留在日志里做“软提示”。
 */
data class RunBudget(
    /** 允许的最大 step 数。 */
    val maxSteps: Int = 10,
    /** 允许读取的最大文件数。 */
    val maxFilesRead: Int = 15,
    /** 允许读取的最大 snippet 数。 */
    val maxSnippets: Int = 30,
    /** 单个 snippet 的建议最大行数。 */
    val maxSnippetLines: Int = 120,
    /** 所有 snippet 的累计最大行数。 */
    val maxTotalSnippetLines: Int = 1_200,
    /** 运行最大时长，单位秒。 */
    val maxRuntimeSeconds: Int = 60,
    /** 当前已执行 step 数。 */
    val usedSteps: Int = 0,
    /** 当前已读取文件数。 */
    val filesRead: Int = 0,
    /** 当前已读取 snippet 数。 */
    val snippetsRead: Int = 0,
    /** 当前累计读取源码行数。 */
    val totalSnippetLinesRead: Int = 0,
    /** 当前 run 是否已经读到过超出单片段预算的 snippet。 */
    val snippetLineLimitExceeded: Boolean = false,
    /** run 起始时间，用于超时判断。 */
    val startedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    /** 记录一次 step 消耗。 */
    fun recordStep(): RunBudget = copy(usedSteps = usedSteps + 1)

    /**
     * 记录一次文件读取。
     * 第一阶段文件读取与 snippet 读取一一对应，后续如果一个文件拆多个 snippet，再单独扩展即可。
     */
    fun recordFileRead(snippetLines: Int): RunBudget {
        return copy(
            filesRead = filesRead + 1,
            snippetsRead = snippetsRead + 1,
            totalSnippetLinesRead = totalSnippetLinesRead + snippetLines.coerceAtLeast(0),
            snippetLineLimitExceeded = snippetLineLimitExceeded || snippetLines.coerceAtLeast(0) > maxSnippetLines,
        )
    }

    /** 计算当前已运行秒数。 */
    fun elapsedSeconds(nowEpochMillis: Long): Long {
        val elapsedMillis = (nowEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
        return elapsedMillis / 1_000L
    }
}
