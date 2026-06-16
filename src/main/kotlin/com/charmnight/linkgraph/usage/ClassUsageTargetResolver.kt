package com.charmnight.linkgraph.usage

import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal data class ClassUsageTargetResolution(
    val targetClass: PsiClass,
    val allowWordIndexFallback: Boolean,
)

class ClassUsageTargetResolver(
    private val project: Project,
) {
    internal fun resolve(target: ClassUsageSearchTargetHint): ClassUsageTargetResolution? {
        val qualifiedName = target.qualifiedName
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (qualifiedName != null) {
            findClassBySourceHint(qualifiedName, target)?.let { return it }
            findClassByQualifiedName(qualifiedName)?.let { return it }
        }
        return target.nodeId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::findClassByStableNodeId)
    }

    private fun findClassByQualifiedName(qualifiedName: String): ClassUsageTargetResolution? {
        val scopes = searchScopes()
        val facade = JavaPsiFacade.getInstance(project)
        scopes.forEach { scope ->
            facade.findClass(qualifiedName, scope)?.let { psiClass ->
                return ClassUsageTargetResolution(psiClass, allowWordIndexFallback = false)
            }
        }

        val simpleName = qualifiedName.substringAfterLast('.').substringAfterLast('$')
        val shortNamesCache = PsiShortNamesCache.getInstance(project)
        scopes.forEach { scope ->
            shortNamesCache.getClassesByName(simpleName, scope)
                .firstOrNull { psiClass -> psiClass.matchesUsageQualifiedName(qualifiedName) }
                ?.let { psiClass -> return ClassUsageTargetResolution(psiClass, allowWordIndexFallback = true) }
        }

        findClassInJavaFiles(
            candidateFiles = filenameIndexJavaFilesForSimpleName(simpleName, scopes) +
                projectBaseJavaFilesForStableKey(stableClassKey(simpleName)),
            matcher = { javaFile -> javaFile.findUsageClass(qualifiedName) },
        )?.let { psiClass ->
            return ClassUsageTargetResolution(psiClass, allowWordIndexFallback = true)
        }

        return null
    }

    private fun findClassByStableNodeId(nodeId: String): ClassUsageTargetResolution? {
        if (!nodeId.startsWith("jvm:class:")) {
            return null
        }
        val targetKey = nodeId.substringAfter("jvm:class:").takeIf(String::isNotBlank) ?: return null
        val scopes = searchScopes()

        findClassByStableNodeIdFromShortNames(nodeId, targetKey, scopes)?.let { return it }
        findClassInJavaFiles(
            candidateFiles = filenameIndexJavaFilesForStableKey(targetKey, scopes) +
                projectBaseJavaFilesForStableKey(targetKey),
            matcher = { javaFile -> javaFile.findUsageClassByStableNodeId(nodeId) },
        )?.let { psiClass ->
            return ClassUsageTargetResolution(psiClass, allowWordIndexFallback = true)
        }

        return null
    }

    private fun findClassByStableNodeIdFromShortNames(
        nodeId: String,
        targetKey: String,
        scopes: List<GlobalSearchScope>,
    ): ClassUsageTargetResolution? {
        val shortNamesCache = PsiShortNamesCache.getInstance(project)
        val candidateClassNames = shortNamesCache.allClassNames
            .asSequence()
            .filter { className -> targetKey.matchesStableClassNameCandidate(className) }
            .distinct()
            .toList()
        scopes.forEach { scope ->
            candidateClassNames.forEach { className ->
                shortNamesCache.getClassesByName(className, scope)
                    .firstOrNull { psiClass ->
                        val qualifiedName = psiClass.qualifiedName?.takeIf(String::isNotBlank) ?: return@firstOrNull false
                        stableJvmId("class", qualifiedName) == nodeId
                    }
                    ?.let { psiClass ->
                        return ClassUsageTargetResolution(psiClass, allowWordIndexFallback = true)
                    }
            }
        }
        return null
    }

    private fun findClassBySourceHint(
        qualifiedName: String,
        target: ClassUsageSearchTargetHint,
    ): ClassUsageTargetResolution? {
        val psiManager = PsiManager.getInstance(project)
        return target.hintedVirtualFiles()
            .filter { file -> file.isValid && !file.isDirectory && file.extension.equals("java", ignoreCase = true) }
            .distinctBy { file -> file.url }
            .mapNotNull { file ->
                file.toPsiJavaFile(psiManager)
                    ?.findUsageClass(qualifiedName)
                    ?.let { psiClass ->
                        ClassUsageTargetResolution(
                            targetClass = psiClass,
                            allowWordIndexFallback = !file.isStandardClassUsageSourceFile(project),
                        )
                    }
            }
            .firstOrNull()
    }

    private fun findClassInJavaFiles(
        candidateFiles: Sequence<VirtualFile>,
        matcher: (PsiJavaFile) -> PsiClass?,
    ): PsiClass? {
        val psiManager = PsiManager.getInstance(project)
        return candidateFiles
            .filter { file -> file.isValid && !file.isDirectory && file.extension.equals("java", ignoreCase = true) }
            .distinctBy { file -> file.url }
            .mapNotNull { file -> file.toPsiJavaFile(psiManager) }
            .mapNotNull(matcher)
            .firstOrNull()
    }

    private fun VirtualFile.toPsiJavaFile(psiManager: PsiManager): PsiJavaFile? {
        (psiManager.findFile(this) as? PsiJavaFile)?.let { return it }
        val sourceText = runCatching { String(contentsToByteArray(), charset) }.getOrNull() ?: return null
        return PsiFileFactory.getInstance(project)
            .createFileFromText(name, JavaFileType.INSTANCE, sourceText) as? PsiJavaFile
    }

    private fun filenameIndexJavaFilesForSimpleName(
        simpleName: String,
        scopes: List<GlobalSearchScope>,
    ): Sequence<VirtualFile> =
        scopes.asSequence()
            .flatMap { scope -> FilenameIndex.getVirtualFilesByName("$simpleName.java", scope).asSequence() }

    private fun filenameIndexJavaFilesForStableKey(
        targetKey: String,
        scopes: List<GlobalSearchScope>,
    ): Sequence<VirtualFile> =
        scopes.asSequence()
            .flatMap { scope -> FilenameIndex.getAllFilesByExt(project, "java", scope).asSequence() }
            .filter { file -> targetKey.matchesStableClassNameCandidate(file.nameWithoutExtension) }

    private fun projectBaseJavaFilesForStableKey(targetKey: String): Sequence<VirtualFile> =
        sequence {
            val basePath = project.basePath
                ?.takeIf(String::isNotBlank)
                ?.let { path -> runCatching { Path.of(path).normalize() }.getOrNull() }
                ?: return@sequence
            if (!Files.isDirectory(basePath)) {
                return@sequence
            }
            val localFileSystem = LocalFileSystem.getInstance()
            val paths = runCatching { Files.walk(basePath) }.getOrNull() ?: return@sequence
            try {
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    val path = iterator.next()
                    if (!Files.isRegularFile(path)) {
                        continue
                    }
                    val relativePath = runCatching {
                        basePath.relativize(path.normalize()).toString().replace('\\', '/')
                    }.getOrNull()?.takeIf(String::isNotBlank) ?: continue
                    if (!relativePath.isClassUsageJavaCandidatePath()) {
                        continue
                    }
                    val fileName = path.fileName?.toString().orEmpty()
                    val baseName = fileName.removeJavaExtensionOrNull() ?: continue
                    if (!targetKey.matchesStableClassNameCandidate(baseName)) {
                        continue
                    }
                    localFileSystem.refreshAndFindFileByNioFile(path)
                        ?.takeIf { file -> !file.isDirectory }
                        ?.let { file -> yield(file) }
                }
            } finally {
                paths.close()
            }
        }

    private fun ClassUsageSearchTargetHint.hintedVirtualFiles(): Sequence<VirtualFile> =
        sequenceOf(sourceVirtualFileUrl, sourcePath)
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .flatMap { hint -> hint.resolveVirtualFilesFromHint() }
            .distinctBy { file -> file.url }

    private fun String.resolveVirtualFilesFromHint(): Sequence<VirtualFile> =
        sequence {
            VirtualFileManager.getInstance().findFileByUrl(this@resolveVirtualFilesFromHint)?.let { yield(it) }
            if ("://" !in this@resolveVirtualFilesFromHint) {
                LocalFileSystem.getInstance().findFileByPath(this@resolveVirtualFilesFromHint)?.let { yield(it) }
                val basePath = project.basePath
                if (basePath != null) {
                    val absolutePath = runCatching {
                        Paths.get(basePath).resolve(this@resolveVirtualFilesFromHint).normalize().toString()
                    }.getOrNull()
                    absolutePath
                        ?.let { path -> LocalFileSystem.getInstance().findFileByPath(path) }
                        ?.let { yield(it) }
                }
            }
        }

    private fun searchScopes(): List<GlobalSearchScope> =
        listOf(
            GlobalSearchScope.projectScope(project),
            GlobalSearchScope.allScope(project),
        ).distinct()
}

internal fun PsiJavaFile.findUsageClass(qualifiedName: String): PsiClass? =
    classes.asSequence()
        .flatMap { psiClass -> psiClass.withUsageInnerClasses() }
        .firstOrNull { psiClass -> psiClass.matchesUsageQualifiedName(qualifiedName) }

private fun PsiJavaFile.findUsageClassByStableNodeId(nodeId: String): PsiClass? =
    classes.asSequence()
        .flatMap { psiClass -> psiClass.withUsageInnerClasses() }
        .firstOrNull { psiClass ->
            val qualifiedName = psiClass.qualifiedName?.takeIf(String::isNotBlank) ?: return@firstOrNull false
            stableJvmId("class", qualifiedName) == nodeId
        }

private fun PsiClass.withUsageInnerClasses(): Sequence<PsiClass> =
    sequence {
        yield(this@withUsageInnerClasses)
        innerClasses.forEach { innerClass -> yieldAll(innerClass.withUsageInnerClasses()) }
    }

internal fun PsiClass.matchesUsageQualifiedName(qualifiedName: String): Boolean {
    val actual = this.qualifiedName ?: return false
    return actual == qualifiedName || actual.replace('$', '.') == qualifiedName.replace('$', '.')
}

private fun String.removeJavaExtensionOrNull(): String? =
    takeIf { fileName -> fileName.endsWith(".java", ignoreCase = true) }
        ?.dropLast(".java".length)
        ?.takeIf(String::isNotBlank)

private fun String.isClassUsageJavaCandidatePath(): Boolean {
    val normalized = replace('\\', '/').trim('/').takeIf(String::isNotBlank) ?: return false
    if (normalized.hasClassUsageExcludedPathSegment()) {
        return false
    }
    return normalized.endsWith(".java", ignoreCase = true)
}

private fun String.hasClassUsageExcludedPathSegment(): Boolean {
    val segments = split('/').filter(String::isNotBlank)
    return segments.withIndex().any { (index, segment) ->
        segment in classUsageAlwaysExcludedPathSegments ||
            segment in classUsageGeneratedPathSegments && "src" !in segments.take(index)
    }
}

private fun stableClassKey(rawKey: String): String =
    stableJvmId("class", rawKey).substringAfterLast(':')

private fun String.matchesStableClassNameCandidate(candidateName: String): Boolean {
    val classKey = stableClassKey(candidateName)
    return this == classKey || endsWith("-$classKey") || contains("-$classKey-")
}

internal fun VirtualFile.isStandardClassUsageSourceFile(project: Project): Boolean {
    if (!ProjectFileIndex.getInstance(project).isInSourceContent(this)) {
        return false
    }
    val normalizedPath = path.replace('\\', '/')
    return standardClassUsageSourcePathMarkers.any { marker -> marker in normalizedPath }
}

private val standardClassUsageSourcePathMarkers = listOf(
    "/src/main/java/",
    "/src/test/java/",
    "/src/jmh/java/",
    "/src/integrationTest/java/",
    "/src/main/kotlin/",
    "/src/test/kotlin/",
    "/src/jmh/kotlin/",
    "/src/integrationTest/kotlin/",
)

private val classUsageAlwaysExcludedPathSegments = setOf(
    ".cache",
    ".git",
    ".gradle",
    ".idea",
    ".next",
    ".nuxt",
    ".parcel-cache",
    "build-idea-sandbox",
    "node_modules",
)

private val classUsageGeneratedPathSegments = setOf(
    "build",
    "coverage",
    "dist",
    "out",
    "target",
    "temp",
    "tmp",
)
