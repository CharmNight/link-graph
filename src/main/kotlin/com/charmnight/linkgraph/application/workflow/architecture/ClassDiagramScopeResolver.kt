package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClass
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ClassDiagramScopeResolver(
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider? = null,
) {
    fun resolve(requestedScopeNodeId: String?): String? {
        requestedScopeNodeId?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        return currentEditorClassName()?.let(::classNodeId)
            ?: snapshotProvider
                ?.snapshot()
                ?.selectedMethodSignature
                ?.let(::ownerClassName)
                ?.let(::classNodeId)
    }

    private fun currentEditorClassName(): String? =
        computeOnIdeThread {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ?: return@computeOnIdeThread null
            ReadAction.compute<String?, RuntimeException> {
                val psiDocumentManager = PsiDocumentManager.getInstance(project)
                psiDocumentManager.commitDocument(editor.document)
                val psiFile = psiDocumentManager.getPsiFile(editor.document)
                    ?: return@compute null
                val offset = editor.caretModel.offset.coerceIn(0, psiFile.textLength.coerceAtLeast(0))
                val element = psiFile.findElementAt(offset)
                    ?: psiFile.findElementAt((offset - 1).coerceAtLeast(0))
                    ?: return@compute fallbackTopLevelClassName(psiFile)
                classNameFor(element) ?: fallbackTopLevelClassName(psiFile)
            }
        }

    private fun classNameFor(element: PsiElement): String? =
        PsiTreeUtil.getParentOfType(element, KtClass::class.java, false)
            ?.toLightClass()
            ?.qualifiedName
            ?.takeIf(String::isNotBlank)
            ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
                ?.takeUnless { psiClass -> psiClass is PsiAnonymousClass }
                ?.qualifiedName
                ?.takeIf(String::isNotBlank)

    private fun fallbackTopLevelClassName(file: PsiFile): String? =
        PsiTreeUtil.findChildrenOfType(file, KtClass::class.java)
            .firstOrNull { ktClass -> PsiTreeUtil.getParentOfType(ktClass, KtClass::class.java, true) == null }
            ?.toLightClass()
            ?.qualifiedName
            ?.takeIf(String::isNotBlank)
            ?: PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)
                .firstOrNull { psiClass -> psiClass.containingClass == null && psiClass !is PsiAnonymousClass }
                ?.qualifiedName
                ?.takeIf(String::isNotBlank)

    private fun ownerClassName(methodSignature: String): String? {
        val argumentsStart = methodSignature.indexOf('(')
        if (argumentsStart <= 0) {
            return null
        }
        return methodSignature
            .substring(0, argumentsStart)
            .substringBeforeLast('.', missingDelimiterValue = "")
            .takeIf(String::isNotBlank)
    }

    private fun classNodeId(qualifiedName: String): String =
        stableJvmId("class", qualifiedName)

    private fun <T> computeOnIdeThread(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return action()
        }
        val completed = AtomicBoolean(false)
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        application.invokeAndWait(
            {
                try {
                    result.set(action())
                    completed.set(true)
                } catch (throwable: Throwable) {
                    error.set(throwable)
                }
            },
            ModalityState.defaultModalityState(),
        )
        error.get()?.let { throw it }
        check(completed.get()) { "未能在 IDEA 线程中完成类图范围解析" }
        return result.get()
    }
}
