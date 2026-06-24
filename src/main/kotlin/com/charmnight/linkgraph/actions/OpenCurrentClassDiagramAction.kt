package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.charmnight.linkgraph.application.indexed.requestClassDiagramRequest
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowSession
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClass

/**
 * 从 IDEA 编辑器右键菜单直接打开当前类的类图。
 */
class OpenCurrentClassDiagramAction : DumbAwareAction(
    LinkGraphBundle.message("action.open-current-class-diagram.text"),
    LinkGraphBundle.message("action.open-current-class-diagram.description"),
    null,
) {
    /** 指定动作更新在后台线程执行，避免阻塞 EDT。 */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * 根据当前光标位置能否解析出有效类来动态调整菜单可用性。
     * 在编辑器右键菜单中还会同时控制可见性。
     */
    override fun update(event: AnActionEvent) {
        event.presentation.text = LinkGraphBundle.message("action.open-current-class-diagram.text")
        event.presentation.description = LinkGraphBundle.message("action.open-current-class-diagram.description")
        val project = event.project
        val editor = event.getData(CommonDataKeys.EDITOR)
        val canResolveCurrentClass = project != null &&
            editor != null &&
            resolveCurrentClassNodeId(event, commitDocument = false) != null
        if (event.place == ActionPlaces.EDITOR_POPUP) {
            event.presentation.isEnabledAndVisible = canResolveCurrentClass
        } else {
            event.presentation.isEnabled = canResolveCurrentClass
        }
    }

    /**
     * 用户触发动作时执行：解析当前光标所在类的稳定节点 ID，
     * 打开图谱工具窗口并通过命令通道发起该类的类图请求。
     */
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val classNodeId = resolveCurrentClassNodeId(event, commitDocument = true) ?: return
        project.getService(LinkGraphToolWindowSession::class.java).openToolWindow()
        project.getService(GraphEditorApplicationService::class.java)
            .commandDispatcher
            .dispatch(ApplicationCommand.RequestIndexedGraph(requestClassDiagramRequest(classNodeId)))
    }

    /**
     * 在读动作中解析当前光标位置对应类的稳定节点 ID。
     * 可选择先提交文档以保证拿到最新的 PSI 状态，
     * 解析失败时返回 null。
     */
    private fun resolveCurrentClassNodeId(
        event: AnActionEvent,
        commitDocument: Boolean,
    ): String? {
        val project = event.project ?: return null
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        return runCatching {
            ReadAction.compute<String?, RuntimeException> {
                val psiDocumentManager = PsiDocumentManager.getInstance(project)
                if (commitDocument) {
                    psiDocumentManager.commitDocument(editor.document)
                }
                val psiFile = psiDocumentManager.getPsiFile(editor.document)
                    ?: return@compute null
                val offset = editor.caretModel.offset.coerceIn(0, psiFile.textLength.coerceAtLeast(0))
                val element = psiFile.findElementAt(offset)
                    ?: psiFile.findElementAt((offset - 1).coerceAtLeast(0))
                    ?: return@compute fallbackTopLevelClassName(psiFile)?.let(::classNodeId)
                (classNameFor(element) ?: fallbackTopLevelClassName(psiFile))?.let(::classNodeId)
            }
        }.getOrNull()
    }

    /**
     * 从给定元素向上查找最近的具名类声明，返回其全限定名。
     * 优先匹配 Kotlin 类，其次匹配非匿名 Java 类，匿名类与空名跳过。
     */
    private fun classNameFor(element: PsiElement): String? =
        PsiTreeUtil.getParentOfType(element, KtClass::class.java, false)
            ?.toLightClass()
            ?.qualifiedName
            ?.takeIf(String::isNotBlank)
            ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
                ?.takeUnless { psiClass -> psiClass is PsiAnonymousClass }
                ?.qualifiedName
                ?.takeIf(String::isNotBlank)

    /**
     * 当光标处无法定位到具体类时的兜底策略：
     * 取文件中第一个顶层类（不嵌套在其他类内部）作为目标。
     */
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

    /** 根据类的全限定名生成跨会话稳定的节点 ID。 */
    private fun classNodeId(qualifiedName: String): String =
        stableJvmId("class", qualifiedName)
}
