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

class IdeSourceContentResolver(
    private val project: Project,
    private val accessPolicy: SourceContentAccessPolicy = SourceContentAccessPolicy.DEFAULT,
) : SourceContentResolver {
    @Volatile
    var lastUnavailableReason: String? = null
        private set

    override fun readByVirtualFileUrl(url: String): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readByVirtualFileUrlInReadAction(url)
        }

    override fun readByPath(path: String): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readByPathInReadAction(path)
        }

    override fun readSnippetByPath(path: String, startLine: Int?, endLine: Int?): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readSnippetByPathInReadAction(path, startLine, endLine)
        }

    override fun readClassByQualifiedName(qualifiedName: String): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readClassByQualifiedNameInReadAction(qualifiedName)
        }

    override fun readResourceByPath(resourcePath: String): SourceContent? =
        ReadAction.compute<SourceContent?, RuntimeException> {
            lastUnavailableReason = null
            readResourceByPathInReadAction(resourcePath)
        }

    private fun readByVirtualFileUrlInReadAction(url: String): SourceContent? {
        val file = VirtualFileManager.getInstance().findFileByUrl(url)
            ?: return null
        return readVirtualFile(file)
    }

    private fun readByPathInReadAction(path: String): SourceContent? {
        val file = resolveVirtualFile(path) ?: return null
        return readVirtualFile(file)
    }

    private fun readSnippetByPathInReadAction(path: String, startLine: Int?, endLine: Int?): SourceContent? {
        val full = readByPathInReadAction(path) ?: return null
        if (startLine == null || endLine == null) {
            return full
        }
        val lines = full.text.lines()
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

    private fun readClassByQualifiedNameInReadAction(qualifiedName: String): SourceContent? {
        if (isJdkQualifiedName(qualifiedName) && !accessPolicy.allowJdk) {
            return null
        }
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(qualifiedName, GlobalSearchScope.allScope(project))
            ?: return readJdkClassFallback(qualifiedName)
        val navigationFile = psiClass.navigationElement?.containingFile?.virtualFile
            ?: psiClass.containingFile?.virtualFile
            ?: return null
        val full = readVirtualFile(navigationFile) ?: return null
        val range = psiClass.navigationElement?.textRange ?: psiClass.textRange
        val document = FileDocumentManager.getInstance().getDocument(navigationFile)
        val startLine = document?.getLineNumber(range.startOffset)?.plus(1)
        val endLine = document?.getLineNumber(range.endOffset.coerceAtLeast(range.startOffset))?.plus(1)
        return full.copy(startLine = startLine, endLine = endLine)
    }

    private fun readResourceByPathInReadAction(resourcePath: String): SourceContent? {
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

    private fun jdkSourceZipCandidates(): List<Path> {
        val javaHome = jdkHomePath()
        val home = runCatching { Path.of(javaHome) }.getOrNull() ?: return emptyList()
        return listOf(
            home.resolve("lib/src.zip"),
            home.resolve("src.zip"),
            home.parent?.resolve("src.zip"),
        ).filterNotNull().distinct()
    }

    private fun jdkHomePath(): String = System.getProperty("java.home").orEmpty()

    private fun isJdkQualifiedName(qualifiedName: String): Boolean =
        qualifiedName.startsWith("java.") ||
            qualifiedName.startsWith("javax.") ||
            qualifiedName.startsWith("jdk.") ||
            qualifiedName.startsWith("sun.") ||
            qualifiedName.startsWith("com.sun.")

    private fun decompiledText(file: VirtualFile): String? {
        val result = ClassFileSourceDecompiler.decompileVirtualFileWithDiagnostic(file)
        if (result.text == null) {
            lastUnavailableReason = result.diagnostic
        }
        return result.text
    }
}
