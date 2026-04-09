package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.getAccessorLightMethods
import org.jetbrains.kotlin.asJava.getRepresentativeLightMethod
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor

/**
 * 基于当前光标位置定位代码主题或资源主题。
 */
class CaretSubjectLocator(
    /** 保存按顺序尝试的资源解码器列表。 */
    private val resourceDecoders: List<ResourceDecoder> = listOf(
        YamlPropertiesSubjectDecoder(),
        XmlSubjectDecoder(),
        MarkdownSubjectDecoder(),
        SqlSubjectDecoder(),
    ),
    /** 保存代码主题句柄工厂。 */
    private val codeSubjectHandleFactory: CodeSubjectHandleFactory = CodeSubjectHandleFactory(),
) : SubjectLocator {
    /**
     * 只预览当前位置属于代码主题还是资源主题。
     */
    override fun previewKind(
        project: Project,
        editor: Editor?,
        commitDocument: Boolean,
    ): SubjectPreviewKind? {
        // 未显式传入编辑器时，默认使用当前选中的文本编辑器。
        val targetEditor = editor ?: FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return ReadAction.compute<SubjectPreviewKind?, RuntimeException> {
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            if (commitDocument) {
                psiDocumentManager.commitDocument(targetEditor.document)
            }
            // 先尝试判断当前元素是否能映射为代码主题。
            val psiFile = psiDocumentManager.getPsiFile(targetEditor.document) ?: return@compute null
            val caretOffset = safeCaretOffset(targetEditor, psiFile)
            val element = psiFile.findElementAt(caretOffset)
            if (hasCodeSubjectPreview(element)) {
                return@compute SubjectPreviewKind.CODE_SUBJECT
            }
            // 代码主题未命中时，再按资源解码器顺序尝试资源主题预览。
            resourceDecoders
                .asSequence()
                .filter { decoder -> decoder.supports(psiFile) }
                .firstOrNull { decoder -> decoder.preview(psiFile, targetEditor, caretOffset) }
                ?.let { return@compute SubjectPreviewKind.RESOURCE_SUBJECT }
            null
        }
    }

    /**
     * 定位当前位置对应的完整主题句柄。
     */
    override fun locate(
        project: Project,
        editor: Editor?,
        commitDocument: Boolean,
    ): SubjectHandle? {
        val targetEditor = editor ?: FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return ReadAction.compute<SubjectHandle?, RuntimeException> {
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            if (commitDocument) {
                psiDocumentManager.commitDocument(targetEditor.document)
            }
            val psiFile = psiDocumentManager.getPsiFile(targetEditor.document) ?: return@compute null
            val caretOffset = safeCaretOffset(targetEditor, psiFile)
            val element = psiFile.findElementAt(caretOffset)
            // 优先尝试解析代码主题，命中后直接返回代码句柄。
            resolveMethod(element)?.let { method ->
                return@compute codeSubjectHandleFactory.create(psiFile, method)
            }
            // 代码主题未命中时，再依次尝试资源解码器。
            resourceDecoders
                .asSequence()
                .filter { decoder -> decoder.supports(psiFile) }
                .mapNotNull { decoder -> decoder.decode(psiFile, targetEditor, caretOffset) }
                .firstOrNull()
        }
    }

    /**
     * 从当前 PSI 元素向上回溯最合适的代码方法语义入口。
     */
    private fun resolveMethod(element: PsiElement?): PsiMethod? {
        element ?: return null
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)?.getRepresentativeLightMethod()
            ?: PsiTreeUtil.getParentOfType(element, KtPropertyAccessor::class.java, false)?.let { accessor ->
                // Kotlin 属性访问器需要转成对应的 getter/setter light method。
                val accessors = accessor.property.getAccessorLightMethods()
                if (accessor.isGetter) accessors.getter else accessors.setter
            }
            ?: PsiTreeUtil.getParentOfType(element, KtPrimaryConstructor::class.java, false)
                ?.toLightMethods()
                ?.firstOrNull()
            ?: PsiTreeUtil.getParentOfType(element, KtSecondaryConstructor::class.java, false)
                ?.toLightMethods()
                ?.firstOrNull()
            ?: resolvePrimaryConstructorInitializerMethod(element)
    }

    /**
     * 快速判断当前位置是否存在代码主题预览。
     */
    private fun hasCodeSubjectPreview(element: PsiElement?): Boolean {
        element ?: return false
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(element, KtPropertyAccessor::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(element, KtPrimaryConstructor::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(element, KtSecondaryConstructor::class.java, false) != null ||
            primaryConstructorOwnerClass(element)?.primaryConstructor != null
    }

    /**
     * 尝试把主构造器属性初始化或 init 块中的位置映射到主构造函数。
     */
    private fun resolvePrimaryConstructorInitializerMethod(element: PsiElement): PsiMethod? {
        val owningClass = primaryConstructorOwnerClass(element)
            ?: return null
        return owningClass.primaryConstructor
            ?.toLightMethods()
            ?.firstOrNull()
    }

    /**
     * 查找当前元素所属的主构造函数宿主类。
     */
    private fun primaryConstructorOwnerClass(element: PsiElement): KtClass? {
        return PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
            // 只有落在属性初始化表达式中的位置才视为主构造器初始化语义。
            ?.takeIf { property -> property.initializer?.textRange?.contains(element.textRange.startOffset) == true }
            ?.let { property -> PsiTreeUtil.getParentOfType(property, KtClass::class.java, false) }
            ?: PsiTreeUtil.getParentOfType(element, KtAnonymousInitializer::class.java, false)
                ?.let { initializer -> PsiTreeUtil.getParentOfType(initializer, KtClass::class.java, false) }
    }
}
