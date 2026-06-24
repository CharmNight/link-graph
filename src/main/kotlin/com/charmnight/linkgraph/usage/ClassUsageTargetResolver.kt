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

/** 类用法解析结果，包装解析得到的 PsiClass 以及是否允许在词项索引降级匹配。 */
internal data class ClassUsageTargetResolution(
    /** 最终定位到的 Java/Kotlin 类的 PSI 句柄。 */
    val targetClass: PsiClass,
    /** 为 true 时表示来源不够权威，允许在后续用法搜索时回退到词项索引以放宽匹配。 */
    val allowWordIndexFallback: Boolean,
)

/**
 * 类用法目标解析器。
 *
 * 用于把上层传入的"类查找线索"（如全限定名、稳定 JVM 节点 ID、源文件位置等）转换为可被
 * IntelliJ 用法搜索消费的 PsiClass，避免在 dumb 模式或索引未就绪时直接失败。
 */
class ClassUsageTargetResolver(
    /** 当前 IntelliJ 项目句柄，用于访问 PSI、文件索引等平台能力。 */
    private val project: Project,
) {
    /** 根据上层提供的多种线索尝试定位类，依次尝试源文件提示、全限定名和稳定节点 ID。 */
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

    /** 通过全限定名定位类，依次尝试精确查找、短名缓存兜底以及 Java 文件内容匹配。 */
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

    /** 通过 `jvm:class:` 前缀的稳定节点 ID 定位类，避免依赖完整全限定名。 */
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

    /** 在短名缓存中扫描名字匹配稳定键的候选类，逐一比对生成的稳定节点 ID。 */
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

    /** 基于上层提供的源文件 URL/路径提示直接打开文件并匹配其中的目标类。 */
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

    /** 在候选 Java 文件中按匹配器逐一查找，返回首个命中的 PsiClass。 */
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

    /** 把虚拟文件转换为 PsiJavaFile；若 PSI 不存在则按文本内容现场构造一个轻量 PSI。 */
    private fun VirtualFile.toPsiJavaFile(psiManager: PsiManager): PsiJavaFile? {
        (psiManager.findFile(this) as? PsiJavaFile)?.let { return it }
        val sourceText = runCatching { String(contentsToByteArray(), charset) }.getOrNull() ?: return null
        return PsiFileFactory.getInstance(project)
            .createFileFromText(name, JavaFileType.INSTANCE, sourceText) as? PsiJavaFile
    }

    /** 通过文件名索引（如 Foo.java）查找候选 Java 文件，常作为全限定名兜底匹配。 */
    private fun filenameIndexJavaFilesForSimpleName(
        simpleName: String,
        scopes: List<GlobalSearchScope>,
    ): Sequence<VirtualFile> =
        scopes.asSequence()
            .flatMap { scope -> FilenameIndex.getVirtualFilesByName("$simpleName.java", scope).asSequence() }

    /** 通过扩展名索引枚举所有 Java 文件，再按稳定键过滤候选；适合无完整全限定名的场景。 */
    private fun filenameIndexJavaFilesForStableKey(
        targetKey: String,
        scopes: List<GlobalSearchScope>,
    ): Sequence<VirtualFile> =
        scopes.asSequence()
            .flatMap { scope -> FilenameIndex.getAllFilesByExt(project, "java", scope).asSequence() }
            .filter { file -> targetKey.matchesStableClassNameCandidate(file.nameWithoutExtension) }

    /** 兜底方案：直接遍历项目根目录下的 Java 文件，绕过索引以应对索引尚未建立的情况。 */
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
            // 旧实现用 Files.walk 全量遍历，对 node_modules、build、target 等大目录也会递归进入；
            // 改用 walkFileTree + preVisitDirectory 提前剪枝，避免不必要的 IO。
            val collected = mutableListOf<VirtualFile>()
            val visitor = object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    val name = dir.fileName?.toString() ?: return java.nio.file.FileVisitResult.CONTINUE
                    if (name in classUsagePrunedDirectoryNames) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    if (!attrs.isRegularFile) {
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    val relativePath = runCatching {
                        basePath.relativize(file.normalize()).toString().replace('\\', '/')
                    }.getOrNull()?.takeIf(String::isNotBlank)
                        ?: return java.nio.file.FileVisitResult.CONTINUE
                    if (!relativePath.isClassUsageJavaCandidatePath()) {
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    val fileName = file.fileName?.toString().orEmpty()
                    val baseName = fileName.removeJavaExtensionOrNull()
                        ?: return java.nio.file.FileVisitResult.CONTINUE
                    if (!targetKey.matchesStableClassNameCandidate(baseName)) {
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    localFileSystem.refreshAndFindFileByNioFile(file)
                        ?.takeIf { f -> !f.isDirectory }
                        ?.let(collected::add)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            }
            runCatching {
                java.nio.file.Files.walkFileTree(basePath, visitor)
            }
            collected.forEach { file -> yield(file) }
        }

    /** 把上层提供的源文件 URL 和路径线索展开为候选 VirtualFile 序列，并去重。 */
    private fun ClassUsageSearchTargetHint.hintedVirtualFiles(): Sequence<VirtualFile> =
        sequenceOf(sourceVirtualFileUrl, sourcePath)
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .flatMap { hint -> hint.resolveVirtualFilesFromHint() }
            .distinctBy { file -> file.url }

    /** 将单个线索字符串解析为 VirtualFile，支持 URL、绝对路径以及项目根下的相对路径。 */
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

    /** 返回项目范围 + 全局范围的搜索域列表，用于在 PSI 查找时按优先级尝试。 */
    private fun searchScopes(): List<GlobalSearchScope> =
        listOf(
            GlobalSearchScope.projectScope(project),
            GlobalSearchScope.allScope(project),
        ).distinct()
}

/** 在 PsiJavaFile 中按全限定名查找顶层及内部类。 */
internal fun PsiJavaFile.findUsageClass(qualifiedName: String): PsiClass? =
    classes.asSequence()
        .flatMap { psiClass -> psiClass.withUsageInnerClasses() }
        .firstOrNull { psiClass -> psiClass.matchesUsageQualifiedName(qualifiedName) }

/** 在 PsiJavaFile 中按稳定 JVM 节点 ID 查找顶层及内部类。 */
private fun PsiJavaFile.findUsageClassByStableNodeId(nodeId: String): PsiClass? =
    classes.asSequence()
        .flatMap { psiClass -> psiClass.withUsageInnerClasses() }
        .firstOrNull { psiClass ->
            val qualifiedName = psiClass.qualifiedName?.takeIf(String::isNotBlank) ?: return@firstOrNull false
            stableJvmId("class", qualifiedName) == nodeId
        }

/** 把当前类与其所有内部类（递归）展开为一个序列，便于按统一规则匹配。 */
private fun PsiClass.withUsageInnerClasses(): Sequence<PsiClass> =
    sequence {
        yield(this@withUsageInnerClasses)
        innerClasses.forEach { innerClass -> yieldAll(innerClass.withUsageInnerClasses()) }
    }

/** 判断 PsiClass 的全限定名是否与目标匹配，兼容 `$`/`.` 混用的内部类写法。 */
internal fun PsiClass.matchesUsageQualifiedName(qualifiedName: String): Boolean {
    val actual = this.qualifiedName ?: return false
    return actual == qualifiedName || actual.replace('$', '.') == qualifiedName.replace('$', '.')
}

/** 若文件名以 `.java` 结尾则去掉扩展名并返回基名，否则返回 null。 */
private fun String.removeJavaExtensionOrNull(): String? =
    takeIf { fileName -> fileName.endsWith(".java", ignoreCase = true) }
        ?.dropLast(".java".length)
        ?.takeIf(String::isNotBlank)

/** 判断相对路径是否为候选 Java 文件，过滤掉包含构建产物等被排除路径的文件。 */
private fun String.isClassUsageJavaCandidatePath(): Boolean {
    val normalized = replace('\\', '/').trim('/').takeIf(String::isNotBlank) ?: return false
    if (normalized.hasClassUsageExcludedPathSegment()) {
        return false
    }
    return normalized.endsWith(".java", ignoreCase = true)
}

/** 判断路径中是否包含永远排除的目录段，或在 src 之外出现的生成代码段。 */
private fun String.hasClassUsageExcludedPathSegment(): Boolean {
    val segments = split('/').filter(String::isNotBlank)
    return segments.withIndex().any { (index, segment) ->
        segment in classUsageAlwaysExcludedPathSegments ||
            segment in classUsageGeneratedPathSegments && "src" !in segments.take(index)
    }
}

/** 将原始类名转换为稳定 JVM ID 中冒号后的键部分，用于跨索引匹配。 */
private fun stableClassKey(rawKey: String): String =
    stableJvmId("class", rawKey).substringAfterLast(':')

/** 判断候选类名是否在稳定键意义上与当前键相符（精确匹配、后缀匹配或中段匹配）。 */
private fun String.matchesStableClassNameCandidate(candidateName: String): Boolean {
    val classKey = stableClassKey(candidateName)
    return this == classKey || endsWith("-$classKey") || contains("-$classKey-")
}

/** 判断 VirtualFile 是否位于项目源代码内容中且路径符合标准源码目录结构。 */
internal fun VirtualFile.isStandardClassUsageSourceFile(project: Project): Boolean {
    if (!ProjectFileIndex.getInstance(project).isInSourceContent(this)) {
        return false
    }
    val normalizedPath = path.replace('\\', '/')
    return standardClassUsageSourcePathMarkers.any { marker -> marker in normalizedPath }
}

/** 标准源码目录路径标记，命中其中之一即认为是规范项目源码位置。 */
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

/** 永远排除的路径段集合，通常为缓存、版本控制或构建沙箱目录。 */
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

/** walkFileTree 预剪枝目录名集合：进入这些目录前直接 SKIP_SUBTREE，避免无谓 IO。 */
private val classUsagePrunedDirectoryNames = setOf(
    "node_modules",
    "build",
    "target",
    "out",
    "dist",
    ".git",
    ".gradle",
    ".idea",
    ".cache",
    ".next",
    ".nuxt",
    ".parcel-cache",
    "build-idea-sandbox",
)

/** 生成代码所在路径段集合；若出现在 src 目录之前才认为是非源代码而排除。 */
private val classUsageGeneratedPathSegments = setOf(
    "build",
    "coverage",
    "dist",
    "out",
    "target",
    "temp",
    "tmp",
)
