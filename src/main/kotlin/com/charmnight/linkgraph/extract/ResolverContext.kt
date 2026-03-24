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
    private val xmlFiles by lazy {
        FilenameIndex.getAllFilesByExt(project, "xml", projectScope)
            .mapNotNull(psiManager::findFile)
            .sortedBy { it.virtualFile?.path ?: it.name }
    }

    fun allProjectMethods(): List<PsiMethod> = methods

    fun allXmlFiles(): List<PsiFile> = xmlFiles
}
