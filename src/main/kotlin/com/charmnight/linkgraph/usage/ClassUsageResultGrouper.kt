package com.charmnight.linkgraph.usage

/**
 * 把"类使用位置"的扁平条目按归属（owner）分组。
 *
 * 一次类使用搜索可能返回成百上千条结果，逐条展示对用户无意义。
 * 本类把同一归属（同一个类或同一个文件）下的使用合并为一组，
 * 并在组内按文件+行+列排序，让 UI 能给出更结构化的展示。
 */
class ClassUsageResultGrouper {
    /**
     * 按归属分组并整理使用条目。
     *
     * 分组规则：按 ownerId 聚合；
     * 组内选择"最早出现的位置"作为代表（filePath/line/column 字典序最小）；
     * 组间按 qualifiedName 或 filePath 排序，保证多次刷新顺序稳定。
     *
     * @param entries 原始使用条目列表
     * @return 排序后的使用分组列表
     */
    fun group(entries: List<ClassUsageEntry>): List<ClassUsageGroup> {
        return entries
            .groupBy(ClassUsageEntry::ownerId)
            .map { (ownerId, ownerEntries) ->
                // 选出组内最早位置作为代表
                val first = ownerEntries.minWith(compareBy({ it.filePath }, { it.line }, { it.column }))
                ClassUsageGroup(
                    id = "class-usage-owner:$ownerId",
                    // 没有限定名时无法定位到具体类，ownerId 仅做文件级聚合
                    ownerNodeId = ownerId.takeIf { first.ownerQualifiedName != null },
                    // 有限定名视为类级归属，否则视为文件级归属
                    ownerKind = if (first.ownerQualifiedName != null) ClassUsageOwnerKind.CLASS else ClassUsageOwnerKind.FILE,
                    // 标题：优先用类简单名，缺失时回退到文件名
                    title = first.ownerQualifiedName?.substringAfterLast('.')
                        ?: first.filePath.substringAfterLast('/'),
                    qualifiedName = first.ownerQualifiedName,
                    filePath = first.filePath,
                    virtualFileUrl = first.virtualFileUrl,
                    // 组内使用按字典序稳定排序
                    usages = ownerEntries.sortedWith(compareBy({ it.filePath }, { it.line }, { it.column })),
                )
            }
            // 组间排序：先按限定名（缺失则用文件路径），再按 id 兜底保证稳定
            .sortedWith(compareBy({ it.qualifiedName ?: it.filePath.orEmpty() }, { it.id }))
    }
}
