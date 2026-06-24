package com.charmnight.linkgraph.jvm.relation

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

/**
 * PSI 事实索引：仅保留按需重解析所需的键（symbolId → QN/方法签名/文件 URL），
 * 不持有任何 PSI 元素，避免 VFS 变更后出现 PsiInvalidElementAccessException。
 *
 * 调用方需要 PsiClass / PsiMethod / KtFile 时通过 [lookupPsiClass] / [lookupPsiMethod] / [lookupKotlinFiles]
 * 在使用时从 JavaPsiFacade / PsiManager 重新解析，确保拿到的 PSI 与当前 VFS 状态一致。
 */
internal data class JvmPsiFactIndex(
    val classBySymbolId: Map<String, String>,
    val methodBySymbolId: Map<String, JvmMethodLookup>,
    val kotlinFileUrls: List<String>,
) {
    /** 按需重解析符号 ID 对应的 PSI 类；查不到或 PSI 已失效时返回 null。 */
    fun lookupPsiClass(project: Project, symbolId: String): PsiClass? {
        val qualifiedName = classBySymbolId[symbolId] ?: return null
        val facade = JavaPsiFacade.getInstance(project)
        return facade.findClass(qualifiedName, GlobalSearchScope.projectScope(project))
    }

    /** 按需重解析符号 ID 对应的 PSI 方法；通过 owner QN 找类，再按签名匹配方法。 */
    fun lookupPsiMethod(project: Project, symbolId: String): PsiMethod? {
        val lookup = methodBySymbolId[symbolId] ?: return null
        val facade = JavaPsiFacade.getInstance(project)
        val ownerClass = facade.findClass(lookup.ownerQualifiedName, GlobalSearchScope.projectScope(project))
            ?: return null
        return ownerClass.methods.firstOrNull { method -> methodSignature(method) == lookup.signature }
    }

    /** 按需重解析所有 Kotlin 文件；每次调用都返回当前 VFS 状态下的 KtFile 实例。 */
    fun lookupKotlinFiles(project: Project): List<KtFile> {
        val psiManager = PsiManager.getInstance(project)
        val fileIndex = ProjectFileIndex.getInstance(project)
        val vfs = VirtualFileManager.getInstance()
        return kotlinFileUrls.mapNotNull { url ->
            vfs.findFileByUrl(url)
                ?.takeIf { file -> file.extension?.lowercase() in setOf("kt", "kts") && fileIndex.isInContent(file) }
                ?.let { file -> psiManager.findFile(file) as? KtFile }
        }
    }

    companion object {
        /** 基于符号索引构建 PSI 事实索引；只收集 QN/签名/URL，不物化 PSI。 */
        fun build(
            project: Project,
            symbolIndex: JvmSymbolIndex,
        ): JvmPsiFactIndex {
            val classesBySymbolId = linkedMapOf<String, String>()
            symbolIndex.classesByQualifiedName.values
                .filterNot { symbol -> symbol.external || symbol.library || symbol.jdk }
                .forEach { symbol ->
                    classesBySymbolId[symbol.id] = symbol.qualifiedName
                }

            val methodsBySymbolId = linkedMapOf<String, JvmMethodLookup>()
            symbolIndex.methodsBySignature.values.forEach { method ->
                val ownerClass = symbolIndex.classesByQualifiedName[method.ownerClassName] ?: return@forEach
                if (ownerClass.external || ownerClass.library || ownerClass.jdk) return@forEach
                methodsBySymbolId[method.id] = JvmMethodLookup(
                    ownerQualifiedName = method.ownerClassName,
                    signature = method.signature,
                )
            }

            val kotlinFileUrls = symbolIndex.classesByQualifiedName.values
                .asSequence()
                .filterNot { symbol -> symbol.external || symbol.library || symbol.jdk }
                .mapNotNull { symbol -> symbol.source?.virtualFileUrl }
                .distinct()
                // 文件 URL 已是 VFS 标识，无需在此触发同步 refresh；不在 VFS 中的 URL 会在 lookup 时跳过。
                .filter { url -> url.endsWith(".kt", ignoreCase = true) || url.endsWith(".kts", ignoreCase = true) }
                .toList()

            return JvmPsiFactIndex(classesBySymbolId, methodsBySymbolId, kotlinFileUrls)
        }
    }
}

/** 方法重解析所需的键：所属类限定名 + 规范签名。 */
internal data class JvmMethodLookup(
    val ownerQualifiedName: String,
    val signature: String,
)
