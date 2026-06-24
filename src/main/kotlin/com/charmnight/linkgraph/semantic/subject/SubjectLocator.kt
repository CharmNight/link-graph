package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/** 主题预览种类：用于右键菜单等场景快速判断"当前是什么类型的主题"。 */
enum class SubjectPreviewKind {
    /** 表示当前位置是代码主题。 */
    CODE_SUBJECT,
    /** 表示当前位置是资源主题。 */
    RESOURCE_SUBJECT,
}

/**
 * 负责从项目和编辑器上下文中定位主题句柄。
 *
 * 主题（Subject）是语义分析的入口：定位到主题后才能进入对应的 Provider 流程。
 * 不同实现可能从光标位置、当前文件、当前选中元素等不同来源定位。
 */
interface SubjectLocator {
    /**
     * 执行完整主题定位。
     *
     * @param project 当前项目
     * @param editor 当前编辑器；为 null 时定位器按"项目级"主题处理
     * @param commitDocument 是否在定位前提交编辑器文档（让未保存改动生效）
     * @return 主题句柄；找不到时返回 null
     */
    fun locate(
        project: Project,
        editor: Editor? = null,
        commitDocument: Boolean = true,
    ): SubjectHandle?

    /**
     * 右键菜单预览只需要知道"当前是不是代码主体/资源主体"，不应该强制触发完整解析。
     * 默认实现直接复用 [locate]，具体 locator 可以按需提供更轻量的 dumb-safe 实现。
     *
     * @return 预览种类；找不到时返回 null
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
