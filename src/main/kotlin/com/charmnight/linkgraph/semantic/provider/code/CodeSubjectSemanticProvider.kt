package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.provider.SemanticProvider
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 代码主题的语义分析 Provider 接口。
 *
 * 在 [SemanticProvider] 基础上增加"按代码种类过滤"的默认实现，
 * 子类只需声明自己支持的 [supportedKinds]，无须重写 [supports]。
 */
interface CodeSubjectSemanticProvider : SemanticProvider {
    /** 本 Provider 支持的代码主题种类集合。 */
    val supportedKinds: Set<CodeSubjectKind>

    /**
     * 默认实现：当主题是 [CodeSubjectHandle] 且种类属于 [supportedKinds] 时支持。
     * 子类通常不需要重写此方法。
     */
    override fun supports(handle: SubjectHandle): Boolean {
        return handle is CodeSubjectHandle && handle.kind in supportedKinds
    }
}
