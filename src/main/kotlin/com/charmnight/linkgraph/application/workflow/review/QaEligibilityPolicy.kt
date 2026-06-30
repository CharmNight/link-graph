package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.toRiskResolutionSnapshot
import com.charmnight.linkgraph.application.request.remoteConnectionOrNull
import com.charmnight.linkgraph.application.request.usesRemoteProvider
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

/**
 * 问答 eligibility 判定：从快照推导风险决议 + 阶段准入；从设置推导是否远程 / 是否流式。
 *
 * 从 ReviewWorkflow 抽出（P2-1）：原文件 778 行，本组逻辑 ~15 行，独立性强、无状态。
 * ReviewWorkflow 内保留 thin delegate 把同类调用集中，方便阅读和测试 mock。
 */

/** 推导当前快照的草稿校验状态 + 是否允许进入 code 阶段。 */
fun evaluateQaEligibility(
    snapshot: WorkflowEditorSnapshot,
    riskResolutionService: RiskResolutionService,
): Pair<DraftValidationState, StageEligibilityDecision> {
    val riskSnapshot = snapshot.toApplicationSnapshot().toRiskResolutionSnapshot()
    return riskResolutionService.evaluateDraftValidation(riskSnapshot) to
        riskResolutionService.evaluateCodeEligibility(riskSnapshot)
}

/** 当前设置是否选了远程 provider。 */
fun effectiveRemoteRequested(settings: LinkGraphSettingsState): Boolean =
    settings.usesRemoteProvider()

/** 当前远程连接是否支持流式输出；非远程或未配置时返回 false。 */
fun effectiveStreamingSupported(settings: LinkGraphSettingsState): Boolean =
    settings.remoteConnectionOrNull()?.preset?.capabilities?.supportsStreaming == true
