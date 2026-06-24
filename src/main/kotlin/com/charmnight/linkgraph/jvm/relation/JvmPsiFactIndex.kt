package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.methodSignature
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtFile

/** PSI 事实索引：把符号 ID 映射到对应的 PSI 类/方法以及 Kotlin 文件，供解析器快速查询。 */
internal data class JvmPsiFactIndex(
    val classBySymbolId: Map<String, PsiClass>,
    val methodBySymbolId: Map<String, PsiMethod>,
    val kotlinFiles: List<KtFile>,
) {
    companion object {
        /** 基于符号索引构建 PSI 事实索引。 */
        fun build(
            project: Project,
            symbolIndex: JvmSymbolIndex,
        ): JvmPsiFactIndex {
            val classes = linkedMapOf<String, PsiClass>()
            val facade = JavaPsiFacade.getInstance(project)
            symbolIndex.classesByQualifiedName.values
                .filterNot { symbol -> symbol.external || symbol.library || symbol.jdk }
                .forEach { symbol ->
                    findPsiClass(project, facade, symbol)?.let { psiClass ->
                        classes[symbol.id] = psiClass
                    }
                }
            val kotlinFiles = projectKotlinFiles(project, symbolIndex)
            val methods = linkedMapOf<String, PsiMethod>()
            symbolIndex.methodsBySignature.values.forEach { method ->
                val owner = symbolIndex.classesByQualifiedName[method.ownerClassName]
                    ?.let { ownerClass -> classes[ownerClass.id] }
                    ?: return@forEach
                owner.methods
                    .firstOrNull { psiMethod -> methodSignature(psiMethod) == method.signature }
                    ?.let { psiMethod -> methods[method.id] = psiMethod }
            }
            return JvmPsiFactIndex(classes, methods, kotlinFiles)
        }

        private fun findPsiClass(
            project: Project,
            facade: JavaPsiFacade,
            symbol: JvmClassSymbol,
        ): PsiClass? =
            symbol.source
                ?.virtualFileUrl
                ?.let { url -> VirtualFileManager.getInstance().findFileByUrl(url) }
                ?.let { file -> PsiManager.getInstance(project).findFile(file) as? PsiJavaFile }
                ?.classes
                ?.flatMap(::flattenPsiClasses)
                ?.firstOrNull { psiClass -> psiClass.qualifiedName == symbol.qualifiedName }
                ?: facade.findClass(symbol.qualifiedName, GlobalSearchScope.projectScope(project))

        private fun projectKotlinFiles(
            project: Project,
            symbolIndex: JvmSymbolIndex,
        ): List<KtFile> {
            val psiManager = PsiManager.getInstance(project)
            val fileIndex = ProjectFileIndex.getInstance(project)
            return symbolIndex.classesByQualifiedName.values
                .asSequence()
                .filterNot { symbol -> symbol.external || symbol.library || symbol.jdk }
                .mapNotNull { symbol -> symbol.source?.virtualFileUrl }
                .distinct()
                // 不在循环里调用 refreshAndFindFileByUrl —— 同步 VFS refresh 会阻塞 EDT 并对每个 url 触发 IO；
                // 文件不存在时直接跳过即可（索引通常是 VFS 已知文件）。
                .mapNotNull { url -> VirtualFileManager.getInstance().findFileByUrl(url) }
                .filter { file -> file.extension?.lowercase() in setOf("kt", "kts") && fileIndex.isInContent(file) }
                .mapNotNull { file -> psiManager.findFile(file) as? KtFile }
                .distinctBy { file -> file.virtualFile?.url ?: file.name }
                .toList()
        }

        private fun flattenPsiClasses(psiClass: PsiClass): List<PsiClass> =
            listOf(psiClass) + psiClass.innerClasses.flatMap(::flattenPsiClasses)
    }
}
