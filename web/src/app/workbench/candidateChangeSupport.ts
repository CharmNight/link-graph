import type { CandidateDraftChange, ResultEvidenceFinding } from "../types";

export function candidateEvidence(change: CandidateDraftChange): ResultEvidenceFinding[] {
  return change.evidence ?? [];
}

export function candidateCanConfirm(change: CandidateDraftChange): boolean {
  return candidateEvidence(change).some((finding) =>
    finding.evidenceLevel === "DIRECT_SOURCE" || finding.evidenceLevel === "DIRECT_GRAPH");
}
