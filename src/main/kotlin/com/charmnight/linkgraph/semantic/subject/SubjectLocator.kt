package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * 定义编辑器上下文到主题句柄的定位接口。
 */
enum class SubjectPreviewKind {
    /** 表示当前位置是代码主题。 */
    CODE_SUBJECT,
    /** 表示当前位置是资源主题。 */
    RESOURCE_SUBJECT,
}

/**
 * 负责从项目和编辑器上下文中定位主题句柄。
 */
interface SubjectLocator {
    /**
     * 执行完整主题定位。
     */
    fun locate(
        project: Project,
        editor: Editor? = null,
        commitDocument: Boolean = true,
    ): SubjectHandle?

    /**
     * 右键菜单预览只需要知道“当前是不是代码主体/资源主体”，不应该强制触发完整解析。
     * 默认实现直接复用 locate()，具体 locator 可以按需提供更轻量的 dumb-safe 实现。
     */
    fun previewKind(
        project: Project,
        editor: Editor? = null,
        commitDocument: Boolean = false,
    ): SubjectPreviewKind? {
        // 复用 locate 的结果推断预览种类，未命中则返回空。
        return when (locate(project, editor, commitDocument)) {
            is CodeSubjectHandle -> SubjectPreviewKind.CODE_SUBJECT
            is ResourceSubjectHandle -> SubjectPreviewKind.RESOURCE_SUBJECT
            null -> null
        }
    }
}
