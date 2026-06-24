import type { CandidateDraftChange, ResultEvidenceFinding } from "../types";

/**
 * 取出候选变更所附带的证据列表。
 * 没有证据时返回空数组，调用方无需做空判断。
 */
export function candidateEvidence(change: CandidateDraftChange): ResultEvidenceFinding[] {
  return change.evidence ?? [];
}

/**
 * 判断一个候选变更是否满足"可确认"的最小证据要求。
 *
 * 必须至少有一条 DIRECT_SOURCE 或 DIRECT_GRAPH 级别的证据，
 * 间接证据（CALLSITE_ONLY/NOT_OBSERVED）不足以支持确认。
 * 这种约束防止用户基于弱证据误采纳变更。
 */
export function candidateCanConfirm(change: CandidateDraftChange): boolean {
  return candidateEvidence(change).some((finding) =>
    finding.evidenceLevel === "DIRECT_SOURCE" || finding.evidenceLevel === "DIRECT_GRAPH");
}
