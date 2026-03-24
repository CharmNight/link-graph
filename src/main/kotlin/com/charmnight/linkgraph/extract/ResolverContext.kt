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

data class ResolverOutput(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    val additionalMethods: List<PsiMethod> = emptyList(),
)

class ResolverContext(
    private val project: Project,
) {
    private val projectScope by lazy { GlobalSearchScope.projectScope(project) }
    private val psiManager by lazy { PsiManager.getInstance(project) }
    private val classes by lazy {
        AllClassesSearch.search(projectScope, project)
            .findAll()
            .sortedBy { it.qualifiedName ?: it.name ?: "" }
    }
    private val methods by lazy {
        classes.flatMap { psiClass -> psiClass.methods.asList() }
    }
    private val filesByExtension = mutableMapOf<String, List<PsiFile>>()
    private val xmlFiles by lazy {
        FilenameIndex.getAllFilesByExt(project, "xml", projectScope)
            .mapNotNull(psiManager::findFile)
            .sortedBy { it.virtualFile?.path ?: it.name }
    }

    fun allProjectClasses() = classes

    fun allProjectMethods(): List<PsiMethod> = methods

    fun allXmlFiles(): List<PsiFile> = xmlFiles

    fun allConfigFiles(): List<PsiFile> {
        return listOf("yml", "yaml", "properties", "xml")
            .flatMap(::filesByExtension)
            .distinctBy { it.virtualFile?.path ?: it.name }
            .sortedBy { it.virtualFile?.path ?: it.name }
    }

    fun allMarkdownFiles(): List<PsiFile> = filesByExtension("md")

    fun filesNamed(name: String): List<PsiFile> {
        return FilenameIndex.getVirtualFilesByName(project, name, projectScope)
            .mapNotNull(psiManager::findFile)
            .sortedBy { it.virtualFile?.path ?: it.name }
    }

    private fun filesByExtension(extension: String): List<PsiFile> {
        return filesByExtension.getOrPut(extension) {
            FilenameIndex.getAllFilesByExt(project, extension, projectScope)
                .mapNotNull(psiManager::findFile)
                .sortedBy { it.virtualFile?.path ?: it.name }
        }
    }
}
