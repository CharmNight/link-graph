package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.runtime.TaskRunner
import com.charmnight.linkgraph.jvm.index.stableJvmId
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

/**
 * 类图作用域解析器。
 *
 * 负责在生成类图时确定目标作用域：优先使用调用方显式传入的节点标识，
 * 其次基于当前编辑器光标所在位置推断出对应的类，
 * 最后退回到编辑器快照里被选中方法所属的类。
 */
internal class ClassDiagramScopeResolver(
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider? = null,
    private val taskRunner: TaskRunner,
) {
    /**
     * 解析出类图所应聚焦的类节点标识。
     *
     * 解析优先级为：显式传入的非空节点 -> 当前编辑器中的类 -> 快照里选中方法所属的类。
     * 若均无法确定则返回 null，由调用方决定是否中止或回退。
     */
    fun resolve(requestedScopeNodeId: String?): String? {
        requestedScopeNodeId?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        return currentEditorClassName()?.let(::classNodeId)
            ?: snapshotProvider
                ?.snapshot()
                ?.selectedMethodSignature
                ?.let(::ownerClassName)
                ?.let(::classNodeId)
    }

    /**
     * 取得当前激活的文本编辑器中光标所在类的全限定名。
     *
     * 必须在 IDEA 的调度线程上执行：先提交文档，再以读操作定位光标处的元素，
     * 若无法精确解析则退化为该文件中的顶层类。
     */
    private fun currentEditorClassName(): String? =
        computeOnIdeThread {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ?: return@computeOnIdeThread null
            taskRunner.read {
                val psiDocumentManager = PsiDocumentManager.getInstance(project)
                psiDocumentManager.commitDocument(editor.document)
                val psiFile = psiDocumentManager.getPsiFile(editor.document)
                    ?: return@read null
                val offset = editor.caretModel.offset.coerceIn(0, psiFile.textLength.coerceAtLeast(0))
                val element = psiFile.findElementAt(offset)
                    ?: psiFile.findElementAt((offset - 1).coerceAtLeast(0))
                    ?: return@read fallbackTopLevelClassName(psiFile)
                classNameFor(element) ?: fallbackTopLevelClassName(psiFile)
            }
        }

    /**
     * 根据给定的 PSI 元素向上查找其所属类的全限定名。
     *
     * 优先匹配 Kotlin 类并转换为对应的轻量 Java 类以获取全限定名；
     * 若不命中则尝试 Java 类，并排除匿名类。
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
     * 当无法从具体元素定位到类时的兜底逻辑：取文件中第一个顶层类的全限定名。
     *
     * 先尝试 Kotlin 顶层类，再退到 Java 顶层非匿名类，确保即使光标位置不在类内部也能给出一个合理作用域。
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

    /**
     * 从方法签名中解析出其所属类的全限定名。
     *
     * 通过查找参数起始括号位置，截取括号前的部分并以最后一个点号分隔，
     * 得到的子串即为方法所属类的全限定名；无法解析时返回 null。
     */
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

    /**
     * 根据类的全限定名生成稳定的图节点标识，确保跨会话保持一致。
     */
    private fun classNodeId(qualifiedName: String): String =
        stableJvmId("class", qualifiedName)

    /**
     * 在 IDEA 调度线程上执行给定动作并同步返回结果。
     *
     * 具体线程切换由 [TaskRunner] 适配到当前平台；workflow 不直接依赖 IntelliJ threading API。
     */
    private fun <T> computeOnIdeThread(action: () -> T): T {
        return taskRunner.ui(TaskRunner.UiPolicy.ANY, action)
    }
}
