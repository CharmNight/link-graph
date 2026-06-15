package com.charmnight.linkgraph.navigation

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.SourceNavigationAnchors
import com.charmnight.linkgraph.model.sourceLocation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import java.nio.file.Path

/**
 * 统一处理链路节点或草案文件的源码跳转。
 * 支持 `path:line:column` 形式的位置串，也支持直接按项目相对路径或绝对路径打开文件。
 */
@Service(Service.Level.PROJECT)
class SourceNavigationService(
    /** 保存当前项目实例。 */
    private val project: Project,
) {
    /**
     * 解析并打开指定节点对应的源码位置。
     */
    fun navigate(node: GraphNode): NavigationTarget? {
        return resolve(node)?.let(::open)
    }

    /**
     * 解析节点对应的导航目标，但不实际打开文件。
     */
    fun resolve(node: GraphNode): NavigationTarget? {
        val locationTarget = node.location
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::parseLocation)
        if (locationTarget != null) {
            return locationTarget
        }
        val sourceLocation = node.sourceLocation()
        sourceLocation.virtualFileUrl
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { url ->
                return NavigationTarget(
                    filePath = sourceLocation.filePath ?: node.location.orEmpty(),
                    line = sourceLocation.startLine ?: 1,
                    column = sourceLocation.column ?: 1,
                    virtualFileUrl = url,
                )
            }
        SourceNavigationAnchors.fromMetadata(node.metadata)?.let { anchor ->
            if (anchor.filePath != null || anchor.virtualFileUrl != null) {
                return NavigationTarget(
                    filePath = anchor.filePath ?: anchor.virtualFileUrl.orEmpty(),
                    line = anchor.line,
                    column = anchor.column,
                    virtualFileUrl = anchor.virtualFileUrl,
                )
            }
            if (anchor.signature != null && SourceNavigationAnchors.isSignatureNavigableType(anchor.nodeType ?: node.type)) {
                return resolveNavigationTargetFromSignature(
                    node.copy(
                        type = anchor.nodeType ?: node.type,
                        signature = anchor.signature,
                    ),
                )?.let { target ->
                    NavigationTarget(
                        filePath = target.virtualFile.path,
                        line = target.line,
                        column = target.column,
                        virtualFileUrl = target.virtualFile.url,
                    )
                }
            }
        }
        // 对 bridge 入口来说，位置串优先于签名推导，避免同名符号导致跳错文件。
        return resolveNavigationTargetFromSignature(node)?.let { target ->
            NavigationTarget(
                filePath = target.virtualFile.path,
                line = target.line,
                column = target.column,
                virtualFileUrl = target.virtualFile.url,
            )
        }
    }

    /**
     * 直接按路径打开文件，供草案写入后自动定位或前端 “Open” 按钮复用。
     */
    fun navigateToPath(
        filePath: String,
        line: Int = 1,
        column: Int = 1,
    ): NavigationTarget? {
        // 直接构造导航目标后复用统一打开逻辑。
        return open(
            NavigationTarget(
                filePath = filePath,
                line = line,
                column = column,
            ),
        )
    }

    /**
     * 仅按项目根目录内路径打开文件，拒绝项目外绝对路径和越界相对路径。
     */
    fun navigateToProjectPath(
        filePath: String,
        line: Int = 1,
        column: Int = 1,
    ): NavigationTarget? {
        val virtualFile = resolveProjectScopedFile(filePath) ?: return null
        return open(virtualFile, line, column)
    }

    /**
     * 打开指定导航目标。
     */
    fun open(target: NavigationTarget): NavigationTarget? {
        // 先把目标中的路径或虚拟文件 URL 解析为实际文件。
        val virtualFile = resolveVirtualFile(target) ?: return null
        return open(virtualFile, target.line, target.column)
    }

    /**
     * 在 IDE 中打开指定虚拟文件并定位到目标行列。
     */
    private fun open(
        virtualFile: VirtualFile,
        line: Int,
        column: Int,
    ): NavigationTarget? {
        // 统一构造真正的编辑器打开动作。
        val openEditor = {
            FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(project, virtualFile, line - 1, column - 1),
                true,
            )
            NavigationTarget(
                filePath = virtualFile.path,
                line = line,
                column = column,
            )
        }
        // UI 线程可直接打开，后台线程则切回 UI 线程执行。
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return openEditor()
        }

        // 通过可变结果变量承接 invokeAndWait 的返回值。
        var result: NavigationTarget? = null
        application.invokeAndWait(
            {
                result = openEditor()
            },
            ModalityState.defaultModalityState(),
        )
        return result
    }

    /**
     * 解析 `path:line:column` 或 `path:line` 形式的位置串。
     */
    private fun parseLocation(location: String): NavigationTarget? {
        // 直接按冒号拆分，兼容绝对路径中的盘符情况由后续拼接兜底。
        val parts = location.split(':')
        if (parts.isEmpty()) {
            return null
        }

        // 最后两段优先尝试解析为列号与行号。
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

    /**
     * 依次尝试解析压缩包路径、jrt 路径、项目相对路径和本地路径。
     */
    private fun resolveFile(filePath: String) =
        resolveArchiveEntry(filePath)
            ?: resolveJrtEntry(filePath)
            ?: resolveProjectRelativeFile(filePath)
            ?: resolveLocalFile(filePath)

    /**
     * 优先使用虚拟文件 URL，否则退回到路径解析。
     */
    private fun resolveVirtualFile(target: NavigationTarget): VirtualFile? {
        target.virtualFileUrl
            ?.let { url ->
                VirtualFileManager.getInstance().findFileByUrl(url)
                    ?: VirtualFileManager.getInstance().refreshAndFindFileByUrl(url)
            }
            ?.let { return it }
        return resolveFile(target.filePath)
    }

    /**
     * 解析 jar/zip 包内条目。
     */
    private fun resolveArchiveEntry(filePath: String): VirtualFile? {
        // 先把显示路径转换为 jar 文件系统可识别的标准格式。
        val archivePath = SourceNavigationPathResolver.normalizeArchiveEntryPath(filePath) ?: return null
        return StandardFileSystems.jar().findFileByPath(archivePath)
            ?: StandardFileSystems.jar().refreshAndFindFileByPath(archivePath)
    }

    /**
     * 解析 JDK jrt 文件系统条目。
     */
    private fun resolveJrtEntry(filePath: String): VirtualFile? {
        // 构造完整的 jrt URL 后再交给虚拟文件系统查找。
        val jrtUrl = SourceNavigationPathResolver.buildJrtUrl(filePath) ?: return null
        return VirtualFileManager.getInstance().findFileByUrl(jrtUrl)
            ?: VirtualFileManager.getInstance().refreshAndFindFileByUrl(jrtUrl)
    }

    /**
     * 尝试按项目相对路径解析文件。
     */
    private fun resolveProjectRelativeFile(filePath: String) =
        filePath
            .takeUnless { safePath(it)?.isAbsolute == true }
            ?.removePrefix("./")
            ?.let(::findProjectFileByRelativePath)

    /**
     * 仅解析当前项目根目录内的本地文件。
     */
    private fun resolveProjectScopedFile(filePath: String): VirtualFile? {
        val rawPath = safePath(filePath)?.normalize() ?: return null
        val allowedRoots = projectScopedRoots()
        if (allowedRoots.isEmpty()) {
            return null
        }
        if (!rawPath.isAbsolute) {
            val relativePath = rawPath.toString().replace('\\', '/').removePrefix("./")
            findProjectFileByRelativePath(relativePath)
                ?.takeUnless(VirtualFile::isDirectory)
                ?.takeIf { file ->
                    val candidatePath = safePath(file.path)
                        ?.normalize()
                    candidatePath != null && allowedRoots.any { root -> candidatePath.startsWith(root) }
                }
                ?.let { return it }
        }
        val resolvedPath = if (rawPath.isAbsolute) {
            rawPath
        } else {
            val baseRoot = allowedRoots.first()
            baseRoot.resolve(rawPath).normalize()
        }
        if (allowedRoots.none { root -> resolvedPath.startsWith(root) }) {
            return null
        }
        return LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(resolvedPath)
            ?.takeUnless(VirtualFile::isDirectory)
    }

    private fun projectScopedRoots(): List<Path> {
        return buildList {
            project.basePath
                ?.let(::safePath)
                ?.takeIf(Path::isAbsolute)
                ?.normalize()
                ?.let(::add)
            ProjectRootManager.getInstance(project).contentRoots
                .asSequence()
                .mapNotNull { root -> safePath(root.path)?.normalize() }
                .forEach(::add)
        }.distinct()
    }

    /**
     * 在项目内容根中查找相对路径对应文件。
     */
    private fun findProjectFileByRelativePath(relativePath: String): VirtualFile? {
        // 先做内容根上的直接相对路径查找，命中时成本最低。
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots

        contentRoots
            .asSequence()
            .mapNotNull { root -> root.findFileByRelativePath(relativePath) }
            .firstOrNull()
            ?.let { return it }

        // 若直接命中失败，再递归扫描内容根，兼容传入的是尾部相对路径。
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

    /**
     * 按本地文件系统路径解析文件。
     */
    private fun resolveLocalFile(filePath: String) =
        buildCandidatePaths(filePath)
            .asSequence()
            .mapNotNull { candidate -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(candidate) }
            .firstOrNull()

    /**
     * 构建用于本地文件查找的候选路径列表。
     */
    private fun buildCandidatePaths(filePath: String): List<Path> {
        // 非法路径文本直接回退为空列表。
        val rawPath = safePath(filePath) ?: return emptyList()
        // 用有序集合去重，避免相对路径和规范化路径重复。
        val candidates = linkedSetOf<Path>()
        if (rawPath.isAbsolute) {
            candidates.add(rawPath.normalize())
        } else {
            // 相对路径优先尝试拼接项目根目录。
            project.basePath
                ?.let { Path.of(it).resolve(rawPath).normalize() }
                ?.let { candidates.add(it) }
            candidates.add(rawPath.normalize())
        }
        return candidates.toList()
    }

    /**
     * 安全地把路径字符串转换为 `Path`。
     */
    private fun safePath(filePath: String): Path? {
        return runCatching { Path.of(filePath) }.getOrNull()
    }

    /**
     * 根据节点类型和签名推导源码导航目标。
     */
    private fun resolveNavigationTargetFromSignature(node: GraphNode): ResolvedVirtualTarget? {
        // 仅当节点携带非空签名时，才有可能根据类型继续解析。
        val signature = node.signature?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (node.type) {
            NodeType.METHOD -> resolveMethodTarget(signature)
            NodeType.CLASS,
            NodeType.INTERFACE,
            NodeType.ENUM,
            NodeType.ANNOTATION,
            NodeType.RECORD,
            NodeType.OBJECT,
            NodeType.EXTERNAL_CLASS,
            -> resolveClassTarget(signature)
            else -> null
        }
    }

    /**
     * 根据方法签名解析方法导航目标。
     */
    private fun resolveMethodTarget(signature: String): ResolvedVirtualTarget? {
        // 先把签名拆解为类名、方法名、参数和返回值。
        val parsed = parseMethodSignature(signature) ?: return null
        return resolveClassCandidates(parsed.ownerQualifiedName)
            .asSequence()
            .mapNotNull { psiClass ->
                // 先在当前类直接方法中查找，再兜底到继承链方法查找。
                psiClass.methods
                    .asSequence()
                    .filter { candidate -> candidate.name == parsed.methodName }
                    .firstOrNull { candidate -> matchesMethodSignature(candidate, parsed) }
                    ?: psiClass.findMethodsByName(parsed.methodName, true)
                        .asSequence()
                        .firstOrNull { candidate -> matchesMethodSignature(candidate, parsed) }
            }
            .mapNotNull { method -> navigationTargetOf(method.navigationElement) }
            .firstOrNull()
    }

    /**
     * 根据类名解析类导航目标。
     */
    private fun resolveClassTarget(signature: String): ResolvedVirtualTarget? {
        return resolveClassCandidates(signature)
            .asSequence()
            .mapNotNull { psiClass -> navigationTargetOf(psiClass.navigationElement) }
            .firstOrNull()
    }

    /**
     * 判断 PSI 方法是否与解析出的签名完全匹配。
     */
    private fun matchesMethodSignature(
        method: PsiMethod,
        parsed: ParsedMethodSignature,
    ): Boolean {
        // 参数类型列表需要逐一标准化后再比较。
        val parameterTypes = method.parameterList.parameters.map { normalizeTypeText(it.type) }
        if (parameterTypes != parsed.parameterTypes) {
            return false
        }
        return normalizeTypeText(method.returnType) == parsed.returnType
    }

    /**
     * 从 PSI 元素提取可用于打开文件的虚拟目标。
     */
    private fun navigationTargetOf(element: PsiElement?): ResolvedVirtualTarget? {
        // 导航元素优先使用其 navigationElement，确保跳转落在真实声明位置。
        val targetElement = element?.navigationElement ?: return null
        // 没有物理文件时无法构建编辑器导航目标。
        val psiFile = targetElement.containingFile ?: return null
        val virtualFile = psiFile.virtualFile ?: return null
        // 文档用于计算精确行列号，拿不到时退回默认值。
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        val offset = targetElement.textRange?.startOffset ?: 0
        val line = document?.getLineNumber(offset)?.plus(1) ?: 1
        val column = document?.let { doc ->
            val lineStartOffset = doc.getLineStartOffset((line - 1).coerceAtLeast(0))
            (offset - lineStartOffset + 1).coerceAtLeast(1)
        } ?: 1
        return ResolvedVirtualTarget(
            virtualFile = virtualFile,
            line = line,
            column = column,
        )
    }

    /**
     * 解析 `Owner.method(args):returnType` 形式的方法签名。
     */
    private fun parseMethodSignature(signature: String): ParsedMethodSignature? {
        // 使用统一正则拆出所属类、方法名、参数列表和返回类型。
        val match = METHOD_SIGNATURE_PATTERN.matchEntire(signature) ?: return null
        val ownerQualifiedName = match.groupValues[1]
        val methodName = match.groupValues[2]
        val rawParameters = match.groupValues[3]
        val returnType = normalizeTypeName(match.groupValues[4])
        return ParsedMethodSignature(
            ownerQualifiedName = ownerQualifiedName,
            methodName = methodName,
            parameterTypes = rawParameters
                .split(',')
                .map { item -> item.trim() }
                .filter { item -> item.isNotEmpty() }
                .map(::normalizeTypeName),
            returnType = returnType,
        )
    }

    /**
     * 标准化 PSI 类型文本。
     */
    private fun normalizeTypeText(type: PsiType?): String {
        val rawText = type?.canonicalText ?: "void"
        return normalizeTypeName(rawText)
    }

    /**
     * 统一常见简写类型名与标准全限定名。
     */
    private fun normalizeTypeName(typeName: String): String {
        return when (typeName) {
            "String" -> "java.lang.String"
            "Object" -> "java.lang.Object"
            "Integer" -> "java.lang.Integer"
            "Long" -> "java.lang.Long"
            "Boolean" -> "java.lang.Boolean"
            "Double" -> "java.lang.Double"
            "Float" -> "java.lang.Float"
            "Short" -> "java.lang.Short"
            "Byte" -> "java.lang.Byte"
            "Character" -> "java.lang.Character"
            "Void" -> "java.lang.Void"
            else -> typeName
        }
    }

    /**
     * 根据类名解析候选 PSI 类集合。
     */
    private fun resolveClassCandidates(className: String): List<com.intellij.psi.PsiClass> {
        // 先按全限定名精确查找，命中时最可靠。
        val scope = GlobalSearchScope.allScope(project)
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        javaPsiFacade.findClass(className, scope)?.let { psiClass ->
            return listOf(psiClass)
        }
        // 精确命中失败后，再按短类名从索引中兜底查找。
        val shortName = className.substringAfterLast('.')
        return PsiShortNamesCache.getInstance(project)
            .getClassesByName(shortName, scope)
            .sortedBy { psiClass -> psiClass.qualifiedName ?: psiClass.name }
            .toList()
    }

    /**
     * 表示解析后得到的方法签名结构。
     */
    private data class ParsedMethodSignature(
        /** 保存所属类全限定名。 */
        val ownerQualifiedName: String,
        /** 保存方法名。 */
        val methodName: String,
        /** 保存参数类型列表。 */
        val parameterTypes: List<String>,
        /** 保存返回类型。 */
        val returnType: String,
    )

    /**
     * 表示已经解析到虚拟文件层的导航目标。
     */
    private data class ResolvedVirtualTarget(
        /** 保存目标虚拟文件。 */
        val virtualFile: VirtualFile,
        /** 保存目标行号。 */
        val line: Int,
        /** 保存目标列号。 */
        val column: Int,
    )

    private companion object {
        /** 匹配标准方法签名的正则表达式。 */
        val METHOD_SIGNATURE_PATTERN = Regex("""^(.*)\.([^.]+)\((.*)\):(.+)$""")
    }

    /**
     * 表示可供前端和服务层传递的导航目标。
     */
    data class NavigationTarget(
        /** 保存目标文件路径。 */
        val filePath: String,
        /** 保存目标行号。 */
        val line: Int,
        /** 保存目标列号。 */
        val column: Int = 1,
        /** 保存可选的虚拟文件 URL。 */
        val virtualFileUrl: String? = null,
    )
}
