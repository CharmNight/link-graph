package com.charmnight.linkgraph.semantic.provider.code.relation

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticUnit
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch

/**
 * 表示单个关系解析器输出的增量结果。
 */
data class RelationExtraction(
    /** 保存解析得到的语义单元。 */
    val semanticUnits: List<SemanticUnit> = emptyList(),
    /** 保存解析得到的语义关系。 */
    val relations: List<SemanticRelation> = emptyList(),
    /** 保存解析过程中发现的附加方法。 */
    val additionalMethods: List<PsiMethod> = emptyList(),
    /** 保存新增源码映射。 */
    val sourceMappings: List<SourceMapping> = emptyList(),
)

/**
 * 为代码关系解析器提供统一的项目查询上下文。
 */
class RelationExtractionContext(
    /** 保存当前项目实例。 */
    private val project: Project,
    /** 按需构建共享架构索引。 */
    private val architectureIndexProvider: (() -> ArchitectureGraphIndex?)? = null,
) {
    /** 保存项目级搜索范围。 */
    private val projectScope = GlobalSearchScope.projectScope(project)
    /** 保存 PSI 管理器。 */
    private val psiManager = PsiManager.getInstance(project)

    /** 延迟加载项目中的全部类。 */
    private val projectClasses by lazy {
        AllClassesSearch.search(projectScope, project)
            .findAll()
            .sortedBy { psiClass -> psiClass.qualifiedName ?: psiClass.name.orEmpty() }
    }

    /** 延迟加载项目中的全部方法。 */
    private val projectMethods by lazy {
        projectClasses.flatMap { psiClass -> psiClass.methods.asList() }
    }
    /** 按方法签名缓存项目方法，供关系解析器统一定位附加方法。 */
    private val projectMethodsBySignature by lazy {
        projectMethods.associateBy(::methodSignature)
    }

    /** 缓存按扩展名查询到的文件集合。 */
    private val filesByExtension = mutableMapOf<String, List<PsiFile>>()
    private var architectureIndexComputed = false
    private var architectureIndex: ArchitectureGraphIndex? = null

    /**
     * 返回项目中的全部方法。
     */
    fun allProjectMethods(): List<PsiMethod> = projectMethods

    /**
     * 按完整方法签名定位项目 PSI 方法。
     */
    fun methodBySignature(signature: String): PsiMethod? =
        projectMethodsBySignature[signature]

    /**
     * 返回项目中的全部 XML 文件。
     */
    fun allXmlFiles(): List<PsiFile> = filesByExtension("xml")

    /**
     * 返回项目中的全部 Markdown 文件。
     */
    fun allMarkdownFiles(): List<PsiFile> = filesByExtension("md")

    /**
     * 返回项目中的常见配置文件。
     */
    fun allConfigFiles(): List<PsiFile> {
        // 聚合常见配置扩展名，并按路径去重后排序。
        return listOf("yml", "yaml", "properties", "xml")
            .flatMap(::filesByExtension)
            .distinctBy { file -> file.virtualFile?.path ?: file.name }
            .sortedBy { file -> file.virtualFile?.path ?: file.name }
    }

    /**
     * 按名称查找文件。
     */
    fun filesNamed(name: String): List<PsiFile> {
        return FilenameIndex.getVirtualFilesByName(name, projectScope)
            .mapNotNull(psiManager::findFile)
            .sortedBy { file -> file.virtualFile?.path ?: file.name }
    }

    fun architectureIndex(): ArchitectureGraphIndex? {
        if (!architectureIndexComputed) {
            architectureIndex = runCatching { architectureIndexProvider?.invoke() }.getOrNull()
            architectureIndexComputed = true
        }
        return architectureIndex
    }

    /**
     * 按扩展名查询并缓存文件。
     */
    private fun filesByExtension(extension: String): List<PsiFile> {
        return filesByExtension.getOrPut(extension) {
            // 命中缓存后可避免重复触发索引查询。
            FilenameIndex.getAllFilesByExt(project, extension, projectScope)
                .mapNotNull(psiManager::findFile)
                .sortedBy { file -> file.virtualFile?.path ?: file.name }
        }
    }
}
