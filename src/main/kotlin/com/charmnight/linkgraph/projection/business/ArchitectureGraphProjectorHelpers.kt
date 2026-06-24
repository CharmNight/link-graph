package com.charmnight.linkgraph.projection.business

/**
 * ArchitectureGraphProjector 的纯展示 / 名称计算 helper（P2-1 拆分）。
 *
 * 这些函数无状态、无 IntelliJ 依赖，与 ArchitectureGraphProjector 的索引查询 /
 * 节点投影主流程解耦后便于复用与单独测试。
 */

/**
 * 计算多段名称（每段是按分隔符切好的部分列表）共享的前缀长度。
 *
 * 用于"最短唯一后缀"算法：当多个节点共享前缀时，需要从共享前缀之后开始展示
 * 才能让每个节点名称在上下文中唯一。
 *
 * 例：`[[com, foo, Order], [com, foo, Payment]]` 共享 `[com, foo]`，返回 2。
 */
internal fun commonRootSize(names: List<List<String>>): Int {
    if (names.isEmpty()) {
        return 0
    }
    val first = names.first()
    var rootSize = 0
    for (index in first.indices) {
        val part = first[index]
        if (names.all { name -> name.getOrNull(index) == part }) {
            rootSize += 1
        } else {
            break
        }
    }
    return rootSize
}

/**
 * 把隐藏桶的 ID 转换为中文展示标签。
 *
 * 桶 ID 形如 "DATA_ACCESS" / "CONTROLLER" 等，对应 [ArchitectureDisplayLayer.laneId]；
 * 未匹配时原样返回。
 */
internal fun architectureBucketLabel(bucket: String): String =
    ArchitectureDisplayLayer.entries.firstOrNull { layer -> layer.laneId == bucket }?.label ?: bucket
