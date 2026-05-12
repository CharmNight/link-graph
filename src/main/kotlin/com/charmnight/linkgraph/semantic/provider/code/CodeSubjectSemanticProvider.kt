package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.provider.SemanticProvider
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

interface CodeSubjectSemanticProvider : SemanticProvider {
    val supportedKinds: Set<CodeSubjectKind>

    override fun supports(handle: SubjectHandle): Boolean {
        return handle is CodeSubjectHandle && handle.kind in supportedKinds
    }
}
