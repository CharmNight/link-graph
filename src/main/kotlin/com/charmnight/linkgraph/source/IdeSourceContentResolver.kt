package com.charmnight.linkgraph.source

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * IDE 源码内容解析器：基于 IntelliJ PSI 与 VFS 读取项目/库/JDK 源码，
 * 支持虚拟文件 URL、路径、限定名、资源路径等多种寻址方式。
 */
class IdeSourceContentResolver(
    /** 当前项目实例。 */
    private val project: Project,
    /** 访问策略，限定可读取的来源。 */
    private val accessPolicy: SourceContentAccessPolicy = SourceContentAccessPolicy.DEFAULT,
) : SourceContentResolver {
    /** 最近一次内容无法读取时的诊断说明，便于上层向用户提示原因；无失败时为 null。 */
    @Volatile
    var lastUnavailableReason: String? = null
        private set

    /** 按虚拟文件 URL 读取源码内容，统一在读操作中执行并清空失败原因。 */
    override fun readByVirtualFileUrl(url: String): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readByVirtualFileUrlInReadAction(url)
        }

    /** 按路径读取完整源码内容，支持项目内、绝对路径、相对路径等多种形式。 */
    override fun readByPath(path: String): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readByPathInReadAction(path)
        }

    /** 读取指定路径下的代码片段，按起止行号裁切；缺省行号时返回整篇内容。 */
    override fun readSnippetByPath(path: String, startLine: Int?, endLine: Int?): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readSnippetByPathInReadAction(path, startLine, endLine)
        }

    /** 按类的全限定名定位源码或反编译产物，受访问策略限制是否允许读取 JDK 类。 */
    override fun readClassByQualifiedName(qualifiedName: String): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readClassByQualifiedNameInReadAction(qualifiedName)
        }

    /** 按资源相对路径在内容根下递归查找资源文件并返回其内容，找不到时再走通用路径解析。 */
    override fun readResourceByPath(resourcePath: String): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readResourceByPathInReadAction(resourcePath)
        }

    /** 读操作内部实现：通过虚拟文件管理器解析 URL 并读取文件。 */
    private fun readByVirtualFileUrlInReadAction(url: String): SourceContent? {
        val file = VirtualFileManager.getInstance().findFileByUrl(url)
            ?: return null
        return readVirtualFile(file)
    }

    /** 读操作内部实现：把输入路径解析为虚拟文件后读取内容。 */
    private fun readByPathInReadAction(path: String): SourceContent? {
        val file = resolveVirtualFile(path) ?: return null
        return readVirtualFile(file)
    }

    /** 读操作内部实现：基于完整内容按行切片，越界或反向区间直接返回 null。 */
    private fun readSnippetByPathInReadAction(path: String, startLine: Int?, endLine: Int?): SourceContent? {
        val full = readByPathInReadAction(path) ?: return null
        if (startLine == null || endLine == null) {
            return full
        }
        val lines = full.text.lines()
        // 行号转换为 0 基索引，并约束在合法范围内避免越界
        val fromIndex = (startLine - 1).coerceAtLeast(0)
        val toIndex = endLine.coerceAtMost(lines.size)
        if (fromIndex >= toIndex) {
            return null
        }
        return full.copy(
            text = lines.subList(fromIndex, toIndex).joinToString("\n"),
            startLine = startLine,
            endLine = endLine,
        )
    }

    /** 读操作内部实现：通过 PSI 定位类元素并计算其在文件中的行范围；JDK 类走兜底逻辑。 */
    private fun readClassByQualifiedNameInReadAction(qualifiedName: String): SourceContent? {
        if (isJdkQualifiedName(qualifiedName) && !accessPolicy.allowJdk) {
            return null
        }
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(qualifiedName, GlobalSearchScope.allScope(project))
            ?: return readJdkClassFallback(qualifiedName)
        // 优先用导航元素解析到真正的源码位置（处理委托/继承场景）
        val navigationFile = psiClass.navigationElement?.containingFile?.virtualFile
            ?: psiClass.containingFile?.virtualFile
            ?: return null
        val full = readVirtualFile(navigationFile) ?: return null
        val range = psiClass.navigationElement?.textRange ?: psiClass.textRange
        val document = FileDocumentManager.getInstance().getDocument(navigationFile)
        // 将字符偏移转换为以 1 起始的行号，便于在 UI 上展示和跳转
        val startLine = document?.getLineNumber(range.startOffset)?.plus(1)
        val endLine = document?.getLineNumber(range.endOffset.coerceAtLeast(range.startOffset))?.plus(1)
        return full.copy(startLine = startLine, endLine = endLine)
    }

    /** 读操作内部实现：在内容根下逐个尝试匹配资源文件，命中后委托通用文件读取。 */
    private fun readResourceByPathInReadAction(resourcePath: String): SourceContent? {
        // 统一使用正斜杠并去掉前导斜杠，避免在 VFS 下出现绝对路径误判
        val normalized = resourcePath.trim().replace('\\', '/').removePrefix("/")
        val roots = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots
        roots.forEach { root ->
            val file = root.findFileByRelativePath(normalized)
            if (file != null && !file.isDirectory) {
                return readVirtualFile(file)
            }
        }
        return readByPathInReadAction(resourcePath)
    }

    /**
     * 把多种形式的路径解析为虚拟文件：支持 VFS URL、jar 内条目、绝对本地路径、
     * 项目内容根相对路径等，并按访问策略过滤。
     */
    private fun resolveVirtualFile(path: String): VirtualFile? {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            return null
        }
        if (trimmed.contains("://")) {
            return VirtualFileManager.getInstance().findFileByUrl(trimmed)
        }
        if (trimmed.contains("!/")) {
            val archivePath = trimmed.removePrefix("jar://")
            return StandardFileSystems.jar().findFileByPath(archivePath)
        }
        val localPath = runCatching { Path.of(trimmed) }.getOrNull()
        if (localPath != null && localPath.isAbsolute) {
            return LocalFileSystem.getInstance().findFileByNioFile(localPath)
                ?.takeIf(::isReadableProjectFile)
        }
        val relativePath = trimmed.removePrefix("./").replace('\\', '/')
        val roots = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots
        roots.forEach { root ->
            val file = root.findFileByRelativePath(relativePath)
            if (file != null && isReadableProjectFile(file)) {
                return file
            }
        }
        val basePath = project.basePath ?: return null
        return runCatching { Path.of(basePath).resolve(relativePath).normalize() }
            .getOrNull()
            ?.let { candidate -> LocalFileSystem.getInstance().findFileByNioFile(candidate) }
            ?.takeIf(::isReadableProjectFile)
    }

    /** 实际读取虚拟文件内容，处理目录过滤、来源策略、文档/反编译/原始字节三种文本获取方式。 */
    private fun readVirtualFile(file: VirtualFile): SourceContent? {
        if (file.isDirectory || !isReadableProjectFile(file)) {
            return null
        }
        val document = FileDocumentManager.getInstance().getDocument(file)
        val origin = originOf(file)
        if (!accessPolicy.allowsOrigin(origin)) {
            return null
        }
        val rawText = runCatching {
            when {
                document != null -> document.text
                file.extension?.equals("class", ignoreCase = true) == true -> decompiledText(file) ?: return null
                else -> String(file.contentsToByteArray(), file.charset)
            }
        }.getOrNull() ?: return null
        return SourceContent(
            text = rawText,
            displayPath = displayPath(file),
            virtualFileUrl = file.url,
            origin = origin,
            language = languageOf(file),
            startLine = if (document != null && document.lineCount > 0) 1 else null,
            endLine = document?.lineCount?.coerceAtLeast(1) ?: rawText.lineSequence().count().coerceAtLeast(1),
            decompiled = origin in setOf(SourceOrigin.LIBRARY_CLASS_JAR, SourceOrigin.JDK_CLASS, SourceOrigin.DECOMPILED),
        )
    }

    /** 判断虚拟文件是否在当前访问策略允许的范围内（项目内容、库源码、库 class 等）。 */
    private fun isReadableProjectFile(file: VirtualFile): Boolean {
        val fileIndex = ProjectFileIndex.getInstance(project)
        if (file.isDirectory) {
            return false
        }
        if (file.url.startsWith("jrt://")) {
            return accessPolicy.allowJdk
        }
        val inLibrarySource = fileIndex.isInLibrarySource(file)
        val inLibraryClasses = fileIndex.isInLibraryClasses(file)
        if ((inLibrarySource || inLibraryClasses) && !accessPolicy.allowExternalLibraries) {
            return false
        }
        return fileIndex.isInContent(file) ||
            fileIndex.isInSource(file) ||
            inLibrarySource ||
            inLibraryClasses
    }

    /** 推断虚拟文件的内容来源（项目源码、内容根、库源码、JDK、库 class、本地文件等）。 */
    private fun originOf(file: VirtualFile): SourceOrigin {
        val fileIndex = ProjectFileIndex.getInstance(project)
        return when {
            fileIndex.isInSource(file) -> SourceOrigin.PROJECT_SOURCE
            fileIndex.isInContent(file) -> SourceOrigin.CONTENT_ROOT
            fileIndex.isInLibrarySource(file) -> SourceOrigin.LIBRARY_SOURCE_JAR
            file.url.startsWith("jrt://") && file.extension?.equals("java", ignoreCase = true) == true -> SourceOrigin.JDK_SOURCE
            file.url.startsWith("jrt://") -> SourceOrigin.JDK_CLASS
            fileIndex.isInLibraryClasses(file) -> SourceOrigin.LIBRARY_CLASS_JAR
            file.path.contains(".jar!/") && file.extension?.equals("java", ignoreCase = true) == true -> SourceOrigin.LIBRARY_SOURCE_JAR
            file.path.contains(".jar!/") -> SourceOrigin.LIBRARY_CLASS_JAR
            VfsUtilCore.isUnder(
                file,
                com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots.toSet(),
            ) ->
                SourceOrigin.CONTENT_ROOT
            else -> SourceOrigin.LOCAL_FILE
        }
    }

    /** 根据扩展名推断文件所属语言，用于在 UI 和分析层选择合适的处理策略。 */
    private fun languageOf(file: VirtualFile): String? {
        return when (file.extension?.lowercase()) {
            "java" -> "JAVA"
            "class" -> "JAVA"
            "kt", "kts" -> "KOTLIN"
            "xml" -> "XML"
            "yml", "yaml" -> "YAML"
            "properties" -> "PROPERTIES"
            "sql" -> "SQL"
            "md" -> "MARKDOWN"
            else -> file.extension?.uppercase()
        }
    }

    /** 生成相对项目根目录的展示路径，路径不在项目内时回退为绝对路径。 */
    private fun displayPath(file: VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        val base = runCatching { Path.of(basePath).normalize() }.getOrNull() ?: return file.path
        val path = runCatching { Path.of(file.path).normalize() }.getOrNull() ?: return file.path
        return if (path.startsWith(base)) {
            base.relativize(path).toString().replace('\\', '/')
        } else {
            file.path
        }
    }

    /** 当 PSI 找不到 JDK 类时的兜底：先尝试读取 src.zip 中的源码，再回退到 jrt 协议读取 class。 */
    private fun readJdkClassFallback(qualifiedName: String): SourceContent? {
        if (!accessPolicy.allowJdk || !isJdkQualifiedName(qualifiedName)) {
            return null
        }
        val entryName = qualifiedName.replace('.', '/') + ".java"
        jdkSourceZipCandidates().firstNotNullOfOrNull { srcZip ->
            readJdkSourceZip(srcZip, entryName)
        }?.let { return it }
        val jrtUrl = "jrt://${jdkHomePath()}!/java.base/${qualifiedName.replace('.', '/')}.class"
        return readByVirtualFileUrlInReadAction(jrtUrl)
    }

    /** 从 JDK 源码 zip 中读取指定条目并构造源码内容；文件不存在或读取失败时返回 null。 */
    private fun readJdkSourceZip(srcZip: Path, entryName: String): SourceContent? {
        if (!java.nio.file.Files.isRegularFile(srcZip)) {
            return null
        }
        return runCatching {
            JarFile(srcZip.toFile()).use { jar ->
                val entry = jar.getJarEntry(entryName) ?: return null
                val text = jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
                SourceContent(
                    text = text,
                    displayPath = "$srcZip!/$entryName",
                    virtualFileUrl = "jar://$srcZip!/$entryName",
                    origin = SourceOrigin.JDK_SOURCE,
                    language = "JAVA",
                    startLine = 1,
                    endLine = text.lineSequence().count().coerceAtLeast(1),
                    decompiled = false,
                )
            }
        }.getOrNull()
    }

    /** 列出 JDK 安装目录下可能存在的源码 zip 候选路径，按可能性排序并去重。 */
    private fun jdkSourceZipCandidates(): List<Path> {
        val javaHome = jdkHomePath()
        val home = runCatching { Path.of(javaHome) }.getOrNull() ?: return emptyList()
        return listOf(
            home.resolve("lib/src.zip"),
            home.resolve("src.zip"),
            home.parent?.resolve("src.zip"),
        ).filterNotNull().distinct()
    }

    /** 获取当前运行环境的 JDK 主目录路径。 */
    private fun jdkHomePath(): String = System.getProperty("java.home").orEmpty()

    /** 判定一个全限定名是否属于 JDK 内部包，用于决定是否走 JDK 读取分支。 */
    private fun isJdkQualifiedName(qualifiedName: String): Boolean =
        qualifiedName.startsWith("java.") ||
            qualifiedName.startsWith("javax.") ||
            qualifiedName.startsWith("jdk.") ||
            qualifiedName.startsWith("sun.") ||
            qualifiedName.startsWith("com.sun.")

    /** 调用反编译器把 class 文件转换为可读源码文本；失败时把诊断信息暴露给上层。 */
    private fun decompiledText(file: VirtualFile): String? {
        val result = ClassFileSourceDecompiler.decompileVirtualFileWithDiagnostic(file)
        if (result.text == null) {
            lastUnavailableReason = result.diagnostic
        }
        return result.text
    }
}
