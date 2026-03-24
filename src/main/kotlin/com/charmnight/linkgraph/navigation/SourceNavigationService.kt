package com.charmnight.linkgraph.navigation

import com.charmnight.linkgraph.model.GraphNode
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class SourceNavigationService(private val project: Project) {
    fun navigate(node: GraphNode): NavigationTarget? {
        val location = node.location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val target = parseLocation(location) ?: return null
        val virtualFile = resolveFile(target.filePath) ?: return null
        FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, virtualFile, target.line - 1, target.column - 1),
            true,
        )
        return target.copy(filePath = virtualFile.path)
    }

    private fun parseLocation(location: String): NavigationTarget? {
        val parts = location.split(':')
        if (parts.isEmpty()) {
            return null
        }

        val column = parts.lastOrNull()?.toIntOrNull()
        val line = parts.getOrNull(parts.lastIndex - 1)?.toIntOrNull()
        return when {
            line != null && column != null -> NavigationTarget(
                filePath = parts.dropLast(2).joinToString(":"),
                line = line.coerceAtLeast(1),
                column = column.coerceAtLeast(1),
            )

            column != null -> NavigationTarget(
                filePath = parts.dropLast(1).joinToString(":"),
                line = column.coerceAtLeast(1),
                column = 1,
            )

            else -> NavigationTarget(
                filePath = location,
                line = 1,
                column = 1,
            )
        }.takeIf { it.filePath.isNotBlank() }
    }

    private fun resolveFile(filePath: String) =
        resolveProjectRelativeFile(filePath) ?: resolveLocalFile(filePath)

    private fun resolveProjectRelativeFile(filePath: String) =
        filePath
            .takeUnless { safePath(it)?.isAbsolute == true }
            ?.removePrefix("./")
            ?.let(::findProjectFileByRelativePath)

    private fun findProjectFileByRelativePath(relativePath: String): VirtualFile? {
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots

        contentRoots
            .asSequence()
            .mapNotNull { root -> root.findFileByRelativePath(relativePath) }
            .firstOrNull()
            ?.let { return it }

        contentRoots.forEach { root ->
            var match: VirtualFile? = null
            VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                if (!file.isDirectory && file.path.endsWith("/$relativePath")) {
                    match = file
                    return@iterateChildrenRecursively false
                }
                true
            }
            if (match != null) {
                return match
            }
        }
        return null
    }

    private fun resolveLocalFile(filePath: String) =
        buildCandidatePaths(filePath)
            .asSequence()
            .mapNotNull { candidate -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(candidate) }
            .firstOrNull()

    private fun buildCandidatePaths(filePath: String): List<Path> {
        val rawPath = safePath(filePath) ?: return emptyList()
        val candidates = linkedSetOf<Path>()
        if (rawPath.isAbsolute) {
            candidates.add(rawPath.normalize())
        } else {
            project.basePath
                ?.let { Path.of(it).resolve(rawPath).normalize() }
                ?.let { candidates.add(it) }
            candidates.add(rawPath.normalize())
        }
        return candidates.toList()
    }

    private fun safePath(filePath: String): Path? {
        return runCatching { Path.of(filePath) }.getOrNull()
    }

    data class NavigationTarget(
        val filePath: String,
        val line: Int,
        val column: Int = 1,
    )
}
