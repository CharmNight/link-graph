package com.charmnight.linkgraph.extract

import com.intellij.psi.PsiMethod

data class GraphExtractionRequest(
    val entryMethods: List<PsiMethod>,
)
