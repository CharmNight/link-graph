package com.charmnight.linkgraph.usage

import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.jvm.index.methodSignature
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * 类用法搜索的可调参数：限制分组与条目数量，并决定是否纳入 import 语句作为用法。
 */
data class ClassUsageSearchOptions(
    // 返回结果中保留的最大分组数，超出部分会被截断并在汇总中标记可加载更多
    val maxUsageGroups: Int = ClassUsageSearchLimits.DEFAULT_USAGE_GROUPS,
    // 返回结果中保留的最大原始用法条目数，用于控制整体规模与 UI 渲染压力
    val maxUsageEntries: Int = ClassUsageSearchLimits.DEFAULT_USAGE_ENTRIES,
    // 是否把 import 语句也视为一类用法，默认排除以减少噪音
    val includeImports: Boolean = false,
)

/**
 * 调用方仅持有目标类的部分线索（如全限定名、节点 ID、源文件位置）时使用的提示对象，
 * 服务会据此自行解析出真实的 PsiClass 再执行搜索。
 */
data class ClassUsageSearchTargetHint(
    // 目标类的全限定名，是定位 PsiClass 的首选依据
    val qualifiedName: String? = null,
    // 图谱节点 ID，若未提供则由全限定名推导出稳定 JVM 标识
    val nodeId: String? = qualifiedName?.let { stableJvmId("class", it) },
    // 目标所在虚拟文件的 URL，用于辅助解析
    val sourceVirtualFileUrl: String? = null,
    // 目标源文件的本地路径，便于在索引缺失时回退定位
    val sourcePath: String? = null,
)

/**
 * 类用法搜索服务：基于 IntelliJ 的引用与继承索引，收集某个目标类被使用、被继承或被实现的所有位置，
 * 并按文件/Owner 归类输出，同时维护分组与条目上限、汇总信息等，供图谱面板展示。
 */
class ClassUsageSearchService(
    // 当前工程实例，用于访问 PSI、搜索作用域等平台能力
    private val project: Project,
    // 负责把单条引用元素归类为 IMPORT/EXTENDS/IMPLEMENTS 等具体用法类型
    private val classifier: ClassUsageClassifier = ClassUsageClassifier,
    // 负责把扁平的用法条目按 Owner 聚合成可视化分组
    private val grouper: ClassUsageResultGrouper = ClassUsageResultGrouper(),
    // 负责将外部提示（如全限定名、源文件）解析为可搜索的 PsiClass
    private val targetResolver: ClassUsageTargetResolver = ClassUsageTargetResolver(project),
) {
    /**
     * 以已解析的 PsiClass 为入口执行搜索，默认不启用基于单词索引的回退策略。
     */
    fun search(
        targetClass: PsiClass,
        targetNodeId: String,
        options: ClassUsageSearchOptions = ClassUsageSearchOptions(),
    ): ClassUsageSearchResult {
        return searchResolved(
            targetClass = targetClass,
            targetNodeId = targetNodeId,
            options = options,
            allowWordIndexFallback = false,
        )
    }

    /**
     * 搜索的核心实现：先按引用索引收集条目，必要时叠加继承/实现条目，
     * 再统一排序、分组、截断并产出汇总信息。
     */
    private fun searchResolved(
        targetClass: PsiClass,
        targetNodeId: String,
        options: ClassUsageSearchOptions = ClassUsageSearchOptions(),
        allowWordIndexFallback: Boolean,
    ): ClassUsageSearchResult {
        val effectiveOptions = ClassUsageSearchLimits.normalize(options)
        val targetQualifiedName = targetClass.qualifiedName ?: targetClass.name ?: targetNodeId
        val maxUsageEntries = effectiveOptions.maxUsageEntries
        val maxUsageGroups = effectiveOptions.maxUsageGroups
        val collectionLimit = maxUsageEntries.sentinelLimit()
        val shouldUseFallbackSearch = allowWordIndexFallback || targetClass.requiresWordIndexFallback()
        val allEntries = collectEntries(
            targetClass = targetClass,
            targetQualifiedName = targetQualifiedName,
            includeImports = effectiveOptions.includeImports,
            limit = collectionLimit,
            allowWordIndexFallback = shouldUseFallbackSearch,
        ).let { entries ->
            if (entries.size >= collectionLimit) {
                entries
            } else {
                entries + collectInheritorEntries(
                    targetClass = targetClass,
                    targetQualifiedName = targetQualifiedName,
                    existingIds = entries.mapTo(mutableSetOf(), ClassUsageEntry::id),
                    limit = collectionLimit - entries.size,
                    allowNonSourceEntries = shouldUseFallbackSearch,
                )
            }
        }
            .take(collectionLimit)
            .sortedWith(compareBy({ it.filePath }, { it.line }, { it.column }, { it.kind.name }, { it.id }))
        val limitedEntries = allEntries.take(maxUsageEntries)
        val allGroups = grouper.group(allEntries)
        val groups = grouper.group(limitedEntries)
            .take(maxUsageGroups)
        val visibleUsageCount = groups.sumOf { group -> group.usages.size }
        val truncated = allEntries.size > visibleUsageCount ||
            allGroups.size > groups.size
        return ClassUsageSearchResult(
            target = ClassUsageTarget(
                nodeId = targetNodeId,
                qualifiedName = targetQualifiedName,
                displayName = targetClass.name ?: targetQualifiedName.substringAfterLast('.'),
            ),
            groups = groups,
            summary = ClassUsageSummary(
                targetNodeId = targetNodeId,
                targetQualifiedName = targetQualifiedName,
                groupCount = allGroups.size,
                usageCount = allEntries.size,
                visibleGroupCount = groups.size,
                visibleUsageCount = visibleUsageCount,
                truncated = truncated,
                maxUsageGroups = maxUsageGroups,
                maxUsageEntries = maxUsageEntries,
                includeImports = effectiveOptions.includeImports,
                canRequestMore = ClassUsageSearchLimits.canRequestMore(
                    truncated = truncated,
                    maxUsageGroups = maxUsageGroups,
                    maxUsageEntries = maxUsageEntries,
                ),
            ),
        )
    }

    /**
     * 通过目标提示对象触发搜索：先解析出 PsiClass，再决定是否启用回退策略，
     * 解析失败时返回 null 表示调用方提供的目标不可达。
     */
    fun search(
        target: ClassUsageSearchTargetHint,
        options: ClassUsageSearchOptions = ClassUsageSearchOptions(),
    ): ClassUsageSearchResult? {
        val resolution = targetResolver.resolve(target) ?: return null
        val targetQualifiedName = resolution.targetClass.qualifiedName ?: target.qualifiedName ?: return null
        val targetNodeId = target.nodeId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: stableJvmId("class", targetQualifiedName)
        return searchResolved(
            targetClass = resolution.targetClass,
            targetNodeId = targetNodeId,
            options = options,
            allowWordIndexFallback = resolution.allowWordIndexFallback,
        )
    }

    /**
     * 仅凭全限定名触发搜索的便捷重载，内部转换为提示对象后委托给统一入口。
     */
    fun search(
        qualifiedName: String,
        targetNodeId: String = stableJvmId("class", qualifiedName),
        options: ClassUsageSearchOptions = ClassUsageSearchOptions(),
    ): ClassUsageSearchResult? {
        return search(
            target = ClassUsageSearchTargetHint(
                qualifiedName = qualifiedName,
                nodeId = targetNodeId,
            ),
            options = options,
        )
    }

    /**
     * 通过引用搜索收集目标类被显式引用的所有位置；当常规索引不足时，
     * 可启用基于单词索引或全工程 Java 文件扫描的回退策略以补充结果。
     */
    private fun collectEntries(
        targetClass: PsiClass,
        targetQualifiedName: String,
        includeImports: Boolean,
        limit: Int,
        allowWordIndexFallback: Boolean,
    ): List<ClassUsageEntry> {
        if (limit <= 0) {
            return emptyList()
        }
        val scope = targetClass.usageSearchScope()
        val entries = mutableListOf<ClassUsageEntry>()
        val seenEntryIds = mutableSetOf<String>()
        ReferencesSearch.search(targetClass, scope).forEach(
            Processor { reference ->
                ProgressManager.checkCanceled()
                if (entries.size >= limit) {
                    return@Processor false
                }
                val element = reference.element ?: return@Processor true
                addUsageEntry(
                    element = element,
                    targetQualifiedName = targetQualifiedName,
                    includeImports = includeImports,
                    entries = entries,
                    seenEntryIds = seenEntryIds,
                    limit = limit,
                    allowNonSourceEntries = allowWordIndexFallback,
                )
                entries.size < limit
            },
        )
        if (allowWordIndexFallback && entries.size < limit) {
            collectWordIndexEntries(
                targetClass = targetClass,
                targetQualifiedName = targetQualifiedName,
                includeImports = includeImports,
                limit = limit,
                entries = entries,
                seenEntryIds = seenEntryIds,
            )
        }
        if (allowWordIndexFallback && entries.isEmpty() && entries.size < limit) {
            collectProjectJavaFileEntries(
                targetClass = targetClass,
                targetQualifiedName = targetQualifiedName,
                includeImports = includeImports,
                limit = limit,
                entries = entries,
                seenEntryIds = seenEntryIds,
            )
        }
        return entries
    }

    /**
     * 回退策略之一：扫描工程中所有包含目标简单类名的文件，逐个判断 Java 引用是否真的指向目标类，
     * 适用于常规引用索引未覆盖到的场景（如某些库类）。
     */
    private fun collectWordIndexEntries(
        targetClass: PsiClass,
        targetQualifiedName: String,
        includeImports: Boolean,
        limit: Int,
        entries: MutableList<ClassUsageEntry>,
        seenEntryIds: MutableSet<String>,
    ) {
        val simpleName = targetClass.name
            ?: targetQualifiedName.substringAfterLast('.').substringAfterLast('$').takeIf(String::isNotBlank)
            ?: return
        val packageName = targetQualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(
            simpleName,
            GlobalSearchScope.projectScope(project),
            Processor { file ->
                ProgressManager.checkCanceled()
                if (entries.size >= limit) {
                    return@Processor false
                }
                val javaFile = file as? PsiJavaFile ?: return@Processor true
                val references = PsiTreeUtil.findChildrenOfType(
                    javaFile,
                    PsiJavaCodeReferenceElement::class.java,
                )
                for (referenceElement in references) {
                    ProgressManager.checkCanceled()
                    if (entries.size >= limit) {
                        return@Processor false
                    }
                    if (!referenceElement.matchesTargetClass(targetClass, targetQualifiedName, packageName, simpleName)) {
                        continue
                    }
                    addUsageEntry(
                        element = referenceElement,
                        targetQualifiedName = targetQualifiedName,
                        includeImports = includeImports,
                        entries = entries,
                        seenEntryIds = seenEntryIds,
                        limit = limit,
                        allowNonSourceEntries = true,
                    )
                }
                entries.size < limit
            },
            true,
        )
    }

    /**
     * 终极回退策略：当单词索引仍未命中任何条目时，直接遍历工程内所有 Java 文件，
     * 逐个解析其中的引用元素，保证即使索引损坏也能给出最小可用结果。
     *
     * 实现要点：使用 `PsiTreeUtil.processElements` 的 predicate 形式做按文件粒度的惰性遍历，
     * predicate 返回 false 即时终止当前文件遍历；外层 Sequence 在 `forEach` 中检测到 entries 已满
     * 时通过非局部 return 退出整个函数，避免一次性把整库 `PsiJavaCodeReferenceElement`
     * 物化到集合（旧实现的 `findChildrenOfType(...).asSequence()` 会按文件粒度全量物化，
     * 大型工程可能 OOM 或卡死 read action）。
     */
    private fun collectProjectJavaFileEntries(
        targetClass: PsiClass,
        targetQualifiedName: String,
        includeImports: Boolean,
        limit: Int,
        entries: MutableList<ClassUsageEntry>,
        seenEntryIds: MutableSet<String>,
    ) {
        val simpleName = targetClass.name
            ?: targetQualifiedName.substringAfterLast('.').substringAfterLast('$').takeIf(String::isNotBlank)
            ?: return
        val packageName = targetQualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
        val psiManager = PsiManager.getInstance(project)
        fallbackJavaVirtualFiles(targetClass)
            .sortedBy { file -> file.path }
            .mapNotNull { file -> psiManager.findFile(file) as? PsiJavaFile }
            .forEach { javaFile ->
                if (entries.size >= limit) {
                    return
                }
                ProgressManager.checkCanceled()
                PsiTreeUtil.processElements(javaFile, PsiJavaCodeReferenceElement::class.java) { referenceElement ->
                    if (entries.size >= limit) {
                        return@processElements false
                    }
                    if (!referenceElement.matchesTargetClass(targetClass, targetQualifiedName, packageName, simpleName)) {
                        return@processElements true
                    }
                    addUsageEntry(
                        element = referenceElement,
                        targetQualifiedName = targetQualifiedName,
                        includeImports = includeImports,
                        entries = entries,
                        seenEntryIds = seenEntryIds,
                        limit = limit,
                        allowNonSourceEntries = true,
                    )
                    true
                }
            }
    }

    /**
     * 汇总回退扫描所需的 Java 虚拟文件序列：工程范围内所有 .java 文件，
     * 加上目标类所在目录下的 Java 文件，按 URL 去重后供回退策略使用。
     */
    private fun fallbackJavaVirtualFiles(targetClass: PsiClass): Sequence<VirtualFile> =
        (
            FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project)).asSequence() +
                (targetClass.containingFile?.virtualFile?.parent?.javaDescendants() ?: emptySequence())
            )
            .distinctBy { file -> file.url }

    /**
     * 将一个引用元素转换为可放入结果的用法条目：分类、计算坐标、提取所在类与方法签名，
     * 并通过稳定 ID 去重，最后追加到目标集合。
     */
    private fun addUsageEntry(
        element: PsiElement,
        targetQualifiedName: String,
        includeImports: Boolean,
        entries: MutableList<ClassUsageEntry>,
        seenEntryIds: MutableSet<String>,
        limit: Int,
        allowNonSourceEntries: Boolean,
    ) {
        if (entries.size >= limit) {
            return
        }
        val kind = classifier.classify(element)
        if (!includeImports && kind == ClassUsageKind.IMPORT) {
            return
        }
        val file = element.containingFile ?: return
        if (!allowNonSourceEntries) {
            val virtualFile = file.virtualFile ?: return
            if (!virtualFile.isStandardClassUsageSourceFile(project)) {
                return
            }
        }
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        val offset = element.textRange?.startOffset ?: return
        val lineIndex = document?.getLineNumber(offset) ?: 0
        val lineStart = document?.getLineStartOffset(lineIndex) ?: offset
        val lineEnd = document?.getLineEndOffset(lineIndex) ?: offset
        val lineText = document?.text?.substring(lineStart, lineEnd)?.trim().orEmpty()
        val ownerClass = ownerClass(element, file)
        val ownerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        val filePath = file.virtualFile?.path ?: file.name
        val ownerId = ownerClass?.qualifiedName
            ?.let { stableJvmId("class", it) }
            ?: stableJvmId("file", filePath)
        val entryId = stableJvmId(
            "usage",
            listOf(
                targetQualifiedName,
                filePath,
                lineIndex + 1,
                (offset - lineStart) + 1,
                kind.name,
            ).joinToString(":"),
        )
        if (!seenEntryIds.add(entryId)) {
            return
        }
        entries += ClassUsageEntry(
            id = entryId,
            ownerId = ownerId,
            kind = kind,
            filePath = filePath,
            line = lineIndex + 1,
            column = (offset - lineStart) + 1,
            text = lineText,
            virtualFileUrl = file.virtualFile?.url,
            ownerQualifiedName = ownerClass?.qualifiedName,
            ownerMethodSignature = ownerMethod?.let(::methodSignature),
        )
    }

    /**
     * 通过继承索引收集目标类的子类或实现类，分别按 EXTENDS/IMPLEMENTS 类型记录用法，
     * 与引用条目合并后即可覆盖“谁继承/实现了目标类”这一类用法关系。
     */
    private fun collectInheritorEntries(
        targetClass: PsiClass,
        targetQualifiedName: String,
        existingIds: MutableSet<String> = mutableSetOf(),
        limit: Int = Int.MAX_VALUE,
        allowNonSourceEntries: Boolean,
    ): List<ClassUsageEntry> {
        if (limit <= 0) {
            return emptyList()
        }
        val scope = targetClass.usageSearchScope()
        val entries = mutableListOf<ClassUsageEntry>()
        for (inheritor in ClassInheritorsSearch.search(targetClass, scope, true).asIterable()) {
            ProgressManager.checkCanceled()
            val qualifiedName = inheritor.qualifiedName?.takeIf(String::isNotBlank) ?: continue
            val file = inheritor.containingFile ?: continue
            val virtualFile = file.virtualFile
            if (!allowNonSourceEntries && (virtualFile == null || !virtualFile.isStandardClassUsageSourceFile(project))) {
                continue
            }
            val anchorElement = inheritor.extendsList?.referenceElements?.firstOrNull()
                ?: inheritor.implementsList?.referenceElements?.firstOrNull()
                ?: inheritor.nameIdentifier
                ?: inheritor
            val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
            val offset = anchorElement.textRange?.startOffset ?: continue
            val lineIndex = document?.getLineNumber(offset) ?: 0
            val lineStart = document?.getLineStartOffset(lineIndex) ?: offset
            val lineEnd = document?.getLineEndOffset(lineIndex) ?: offset
            val kind = if (targetClass.isInterface) ClassUsageKind.IMPLEMENTS else ClassUsageKind.EXTENDS
            val entryId = stableJvmId(
                "usage",
                listOf(
                    targetQualifiedName,
                    file.virtualFile?.path ?: file.name,
                    lineIndex + 1,
                    (offset - lineStart) + 1,
                    kind.name,
                ).joinToString(":"),
            )
            val entry = ClassUsageEntry(
                id = entryId,
                ownerId = stableJvmId("class", qualifiedName),
                kind = kind,
                filePath = file.virtualFile?.path ?: file.name,
                line = lineIndex + 1,
                column = (offset - lineStart) + 1,
                text = document?.text?.substring(lineStart, lineEnd)?.trim().orEmpty(),
                virtualFileUrl = file.virtualFile?.url,
                ownerQualifiedName = qualifiedName,
            )
            if (existingIds.add(entry.id)) {
                entries += entry
            }
            if (entries.size >= limit) {
                break
            }
        }
        return entries
    }

    /**
     * 推断引用元素所属的命名类：取最近的具名外层类。
     *
     * 若元素本身不处于任何具名类内部（例如位于 file 顶层 import 段），返回 null ——
     * 旧实现会回退到 "文件中第一个顶层类"，导致 usage 在第二个类内时被错误归属到第一个类。
     */
    private fun ownerClass(
        element: PsiElement,
        file: com.intellij.psi.PsiFile,
    ): PsiClass? =
        PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
            ?.takeUnless { it is PsiAnonymousClass }
            ?.takeIf { !it.qualifiedName.isNullOrBlank() }

    /**
     * 判断目标类是否需要启用回退策略：当目标所在文件不属于标准源码文件（例如来自 JAR）时，
     * 常规引用索引可能无法命中，需要走更宽松的搜索路径。
     */
    private fun PsiClass.requiresWordIndexFallback(): Boolean {
        val file = containingFile?.virtualFile ?: return true
        return !file.isStandardClassUsageSourceFile(project)
    }

    /**
     * 深度遍历虚拟文件树，仅产出 Java 文件，目录会按名称排序后递归，
     * 供回退策略在目标类同级目录附近补充候选源文件。
     */
    private fun VirtualFile.javaDescendants(): Sequence<VirtualFile> =
        sequence {
            if (!isValid) {
                return@sequence
            }
            if (isDirectory) {
                children.sortedBy { child -> child.name }.forEach { child ->
                    yieldAll(child.javaDescendants())
                }
            } else if (extension.equals("java", ignoreCase = true)) {
                yield(this@javaDescendants)
            }
        }
}

/**
 * 判断一个 Java 引用元素是否真的指向目标类：先比对简单名，再尝试解析，
 * 若解析失败则回退到包名/import 语句匹配，用于在索引残缺时仍能定位真实用法。
 */
private fun PsiJavaCodeReferenceElement.matchesTargetClass(
    targetClass: PsiClass,
    targetQualifiedName: String,
    targetPackageName: String,
    targetSimpleName: String,
): Boolean {
    val referenceName = referenceNameElement?.text ?: text.substringAfterLast('.').substringAfterLast('$')
    if (referenceName != targetSimpleName) {
        return false
    }
    val resolved = runCatching { resolve() }.getOrNull()
    if (resolved == targetClass || (resolved as? PsiClass)?.matchesUsageQualifiedName(targetQualifiedName) == true) {
        return true
    }
    val referenceQualifiedName = qualifiedName
    if (referenceQualifiedName == targetQualifiedName || text == targetQualifiedName) {
        return true
    }
    if (isQualified) {
        return false
    }
    val javaFile = containingFile as? PsiJavaFile ?: return false
    if (javaFile.packageName == targetPackageName) {
        return true
    }
    val importStatements = javaFile.importList?.allImportStatements ?: return false
    return importStatements.any { importStatement ->
        if (importStatement.isOnDemand) {
            importStatement.importReference?.qualifiedName == targetPackageName
        } else {
            importStatement.importReference?.qualifiedName == targetQualifiedName
        }
    }
}

/**
 * 取目标类在平台中适用的搜索作用域，若未显式声明则退回全工程作用域以保证可搜到引用。
 */
private fun PsiClass.usageSearchScope(): GlobalSearchScope =
    (useScope as? GlobalSearchScope) ?: GlobalSearchScope.allScope(project)

/**
 * 在用户配置的上限基础上额外加 1，作为“是否还存在更多结果”的探测哨兵值；
 * 当 limit 已经是 Int 最大值时直接返回以避免溢出。
 */
private fun Int.sentinelLimit(): Int =
    if (this == Int.MAX_VALUE) {
        Int.MAX_VALUE
    } else {
        this + 1
    }
