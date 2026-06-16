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

data class ClassUsageSearchOptions(
    val maxUsageGroups: Int = ClassUsageSearchLimits.DEFAULT_USAGE_GROUPS,
    val maxUsageEntries: Int = ClassUsageSearchLimits.DEFAULT_USAGE_ENTRIES,
    val includeImports: Boolean = false,
)

data class ClassUsageSearchTargetHint(
    val qualifiedName: String? = null,
    val nodeId: String? = qualifiedName?.let { stableJvmId("class", it) },
    val sourceVirtualFileUrl: String? = null,
    val sourcePath: String? = null,
)

class ClassUsageSearchService(
    private val project: Project,
    private val classifier: ClassUsageClassifier = ClassUsageClassifier,
    private val grouper: ClassUsageResultGrouper = ClassUsageResultGrouper(),
    private val targetResolver: ClassUsageTargetResolver = ClassUsageTargetResolver(project),
) {
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
            .flatMap { javaFile ->
                PsiTreeUtil.findChildrenOfType(javaFile, PsiJavaCodeReferenceElement::class.java).asSequence()
            }
            .forEach { referenceElement ->
                ProgressManager.checkCanceled()
                if (entries.size >= limit) {
                    return
                }
                if (!referenceElement.matchesTargetClass(targetClass, targetQualifiedName, packageName, simpleName)) {
                    return@forEach
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
    }

    private fun fallbackJavaVirtualFiles(targetClass: PsiClass): Sequence<VirtualFile> =
        (
            FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project)).asSequence() +
                (targetClass.containingFile?.virtualFile?.parent?.javaDescendants() ?: emptySequence())
            )
            .distinctBy { file -> file.url }

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

    private fun ownerClass(
        element: PsiElement,
        file: com.intellij.psi.PsiFile,
    ): PsiClass? =
        PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
            ?.takeUnless { it is PsiAnonymousClass }
            ?.takeIf { !it.qualifiedName.isNullOrBlank() }
            ?: PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)
                .firstOrNull { psiClass ->
                    psiClass.containingClass == null &&
                        psiClass !is PsiAnonymousClass &&
                        !psiClass.qualifiedName.isNullOrBlank()
                }

    private fun PsiClass.requiresWordIndexFallback(): Boolean {
        val file = containingFile?.virtualFile ?: return true
        return !file.isStandardClassUsageSourceFile(project)
    }

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

private fun PsiClass.usageSearchScope(): GlobalSearchScope =
    (useScope as? GlobalSearchScope) ?: GlobalSearchScope.allScope(project)

private fun Int.sentinelLimit(): Int =
    if (this == Int.MAX_VALUE) {
        Int.MAX_VALUE
    } else {
        this + 1
    }
