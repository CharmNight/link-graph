package com.charmnight.linkgraph.usage

class ClassUsageResultGrouper {
    fun group(entries: List<ClassUsageEntry>): List<ClassUsageGroup> {
        return entries
            .groupBy(ClassUsageEntry::ownerId)
            .map { (ownerId, ownerEntries) ->
                val first = ownerEntries.minWith(compareBy({ it.filePath }, { it.line }, { it.column }))
                ClassUsageGroup(
                    id = "class-usage-owner:$ownerId",
                    ownerNodeId = ownerId.takeIf { first.ownerQualifiedName != null },
                    ownerKind = if (first.ownerQualifiedName != null) ClassUsageOwnerKind.CLASS else ClassUsageOwnerKind.FILE,
                    title = first.ownerQualifiedName?.substringAfterLast('.')
                        ?: first.filePath.substringAfterLast('/'),
                    qualifiedName = first.ownerQualifiedName,
                    filePath = first.filePath,
                    virtualFileUrl = first.virtualFileUrl,
                    usages = ownerEntries.sortedWith(compareBy({ it.filePath }, { it.line }, { it.column })),
                )
            }
            .sortedWith(compareBy({ it.qualifiedName ?: it.filePath.orEmpty() }, { it.id }))
    }
}
