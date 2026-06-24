package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.workbench.QaModeClassifier
import com.charmnight.linkgraph.workbench.QaModeContext
import com.charmnight.linkgraph.workbench.ReplayableQaRequest

/**
 * 问答模式分类：把可重放请求 + 用户问题分类成一次 [QaModeContext]。
 *
 * 从 ReviewWorkflow 抽出（P2-1）：薄薄一层包装 [QaModeClassifier.classify]，
 * 让 ReviewWorkflow 不再持有"如何构造 QaModeContext"的细节。
 */

/** 把可重放问答请求分类为完整的 mode context（含 requestedMode + effectiveMode + 选中节点等）。 */
internal fun resolveQaModeContext(
    request: ReplayableQaRequest,
    classifier: QaModeClassifier,
): QaModeContext =
    QaModeContext(
        request = request,
        effectiveMode = classifier.classify(
            requestedMode = request.mode,
            question = request.question,
            sourceThreadId = request.sourceThreadId,
        ),
    )
