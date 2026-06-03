package com.charmnight.linkgraph.jvm.index

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod

data class JvmSourceTextSymbol(
    val kind: String,
    val qualifiedName: String,
    val startLine: Int,
    val endLine: Int,
)

fun interface JvmSourceTextSymbolExtractor {
    fun extract(path: String, text: String): List<JvmSourceTextSymbol>
}

class PsiJvmSourceTextSymbolExtractor(
    private val project: Project,
) : JvmSourceTextSymbolExtractor {
    override fun extract(path: String, text: String): List<JvmSourceTextSymbol> {
        if (!path.endsWith(".java", ignoreCase = true)) {
            return emptyList()
        }
        val fileName = path.substringAfterLast('/').ifBlank { "Baseline.java" }
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(fileName, JavaFileType.INSTANCE, text) as? PsiJavaFile
            ?: return emptyList()
        val symbols = mutableListOf<JvmSourceTextSymbol>()
        psiFile.classes.flatMap(::flattenPsiClasses).forEach { psiClass ->
            val className = psiClass.qualifiedName?.takeIf(String::isNotBlank)
                ?: psiClass.name?.takeIf(String::isNotBlank)?.let { simpleName ->
                    listOf(psiFile.packageName, simpleName)
                        .filter(String::isNotBlank)
                        .joinToString(".")
                }
                ?: return@forEach
            symbols += psiClass.toSourceTextSymbol(
                kind = "class",
                qualifiedName = className,
                source = text,
            )
            psiClass.fields.forEach { field ->
                field.toSourceTextSymbol(
                    kind = "field",
                    qualifiedName = "$className.${field.name}",
                    source = text,
                )?.let(symbols::add)
            }
            psiClass.methods.forEach { method ->
                method.toSourceTextSymbol(
                    kind = "method",
                    qualifiedName = methodSignature(method),
                    source = text,
                )?.let(symbols::add)
            }
        }
        return symbols
            .distinctBy { symbol -> symbol.kind to symbol.qualifiedName to symbol.startLine }
            .sortedWith(compareBy<JvmSourceTextSymbol> { it.startLine }.thenBy { it.qualifiedName })
    }

    private fun flattenPsiClasses(psiClass: PsiClass): List<PsiClass> =
        listOf(psiClass) + psiClass.innerClasses.flatMap(::flattenPsiClasses)

    private fun PsiClass.toSourceTextSymbol(
        kind: String,
        qualifiedName: String,
        source: String,
    ): JvmSourceTextSymbol =
        JvmSourceTextSymbol(
            kind = kind,
            qualifiedName = qualifiedName,
            startLine = lineNumber(source, textRange.startOffset),
            endLine = lineNumber(source, textRange.endOffset.coerceAtLeast(textRange.startOffset)),
        )

    private fun PsiField.toSourceTextSymbol(
        kind: String,
        qualifiedName: String,
        source: String,
    ): JvmSourceTextSymbol? =
        textRange?.let { range ->
            JvmSourceTextSymbol(
                kind = kind,
                qualifiedName = qualifiedName,
                startLine = lineNumber(source, range.startOffset),
                endLine = lineNumber(source, range.endOffset.coerceAtLeast(range.startOffset)),
            )
        }

    private fun PsiMethod.toSourceTextSymbol(
        kind: String,
        qualifiedName: String,
        source: String,
    ): JvmSourceTextSymbol? =
        textRange?.let { range ->
            JvmSourceTextSymbol(
                kind = kind,
                qualifiedName = qualifiedName,
                startLine = lineNumber(source, range.startOffset),
                endLine = lineNumber(source, range.endOffset.coerceAtLeast(range.startOffset)),
            )
        }

    private fun lineNumber(text: String, offset: Int): Int =
        text.take(offset.coerceIn(0, text.length)).count { char -> char == '\n' } + 1
}
