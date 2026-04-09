package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch

/**
 * 表示单个解析器返回的节点、边和补充方法集合。
 */
data class ResolverOutput(
    /** 保存解析得到的节点列表。 */
    val nodes: List<GraphNode> = emptyList(),
    /** 保存解析得到的边列表。 */
    val edges: List<GraphEdge> = emptyList(),
    /** 保存解析过程中补充发现的方法列表。 */
    val additionalMethods: List<PsiMethod> = emptyList(),
)

/**
 * 为 legacy extract 解析器提供统一的项目查询上下文。
 */
class ResolverContext(
    /** 保存当前项目实例。 */
    private val project: Project,
) {
    /** 保存项目级搜索范围。 */
    private val projectScope by lazy { GlobalSearchScope.projectScope(project) }
    /** 保存 PSI 管理器。 */
    private val psiManager by lazy { PsiManager.getInstance(project) }
    /** 延迟加载项目中的全部类。 */
    private val classes by lazy {
        AllClassesSearch.search(projectScope, project)
            .findAll()
            .sortedBy { it.qualifiedName ?: it.name ?: "" }
    }
    /** 延迟加载项目中的全部方法。 */
    private val methods by lazy {
        classes.flatMap { psiClass -> psiClass.methods.asList() }
    }
    /** 缓存不同扩展名对应的文件列表。 */
    private val filesByExtension = mutableMapOf<String, List<PsiFile>>()
    /** 延迟加载项目中的全部 XML 文件。 */
    private val xmlFiles by lazy {
        FilenameIndex.getAllFilesByExt(project, "xml", projectScope)
            .mapNotNull(psiManager::findFile)
            .sortedBy { it.virtualFile?.path ?: it.name }
    }

    /**
     * 返回项目中的全部类。
     */
    fun allProjectClasses() = classes

    /**
     * 返回项目中的全部方法。
     */
    fun allProjectMethods(): List<PsiMethod> = methods

    /**
     * 返回项目中的全部 XML 文件。
     */
    fun allXmlFiles(): List<PsiFile> = xmlFiles

    /**
     * 返回项目中的配置文件集合。
     */
    fun allConfigFiles(): List<PsiFile> {
        // 统一聚合常见配置扩展名，并按路径去重后排序。
        return listOf("yml", "yaml", "properties", "xml")
            .flatMap(::filesByExtension)
            .distinctBy { it.virtualFile?.path ?: it.name }
            .sortedBy { it.virtualFile?.path ?: it.name }
    }

    /**
     * 返回项目中的全部 Markdown 文件。
     */
    fun allMarkdownFiles(): List<PsiFile> = filesByExtension("md")

    /**
     * 按文件名查找匹配文件。
     */
    fun filesNamed(name: String): List<PsiFile> {
        return FilenameIndex.getVirtualFilesByName(project, name, projectScope)
            .mapNotNull(psiManager::findFile)
            .sortedBy { it.virtualFile?.path ?: it.name }
    }

    /**
     * 按扩展名查询并缓存文件列表。
     */
    private fun filesByExtension(extension: String): List<PsiFile> {
        return filesByExtension.getOrPut(extension) {
            // 同一扩展名的查询结果会被缓存，减少重复索引访问。
            FilenameIndex.getAllFilesByExt(project, extension, projectScope)
                .mapNotNull(psiManager::findFile)
                .sortedBy { it.virtualFile?.path ?: it.name }
        }
    }
}
