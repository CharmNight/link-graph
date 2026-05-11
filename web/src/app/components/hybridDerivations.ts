import { deriveInvestigationThreads } from "../investigationThreads";
import type {
  AsyncRequestState,
  CandidateDraftChange,
  DraftPatchApplyResult,
  DraftValidationState,
  DraftWorkbenchState,
  EvidenceTraceEntry,
  GraphBeautificationResult,
  GraphPatchResult,
  LinkGraphDocument,
  LinkGraphNode,
  ResultEvidenceFinding,
  ResultEvidenceLevel,
  ResultEvidenceReference,
  SourceSnippetContext,
} from "../types";
import type { WorkflowStage, WorkflowStageStatus } from "../workflow/workflowStage";

export type CodeDiffStatus = "MISSING" | "RUNNING" | "FRESH" | "STALE" | "FAILED";

export interface LinkGraphOutlineMetrics {
  visibleNodeCount: number;
  fullNodeCount: number;
  sourceAnchorCount: number;
  inferredEvidenceCount: number;
  draftImpactCount: number;
  blockingRiskCount: number;
}

export interface LinkGraphOutlineItem {
  id: string;
  label: string;
  kind: string;
  group: "entry" | "criticalPath" | "evidence" | "risk" | "draft";
  badge?: string;
  severity?: "normal" | "info" | "warning" | "danger" | "success";
  meta?: string;
}

export interface EvidenceSummaryItem {
  id: string;
  label: string;
  level: ResultEvidenceLevel;
  references: ResultEvidenceReference[];
}

export interface EvidenceGapItem {
  id: string;
  title: string;
  detail: string;
  targetNodeIds: string[];
  severity: "warning" | "danger";
}

export interface EvidencePanelState {
  selectedNode: LinkGraphNode | null;
  selectedNodeEvidence: EvidenceSummaryItem[];
  evidenceGaps: EvidenceGapItem[];
  sourceSnippets: SourceSnippetContext[];
  evidenceTrace: EvidenceTraceEntry[];
  openRiskThreads: ReturnType<typeof deriveInvestigationThreads>;
  pendingCandidateChanges: CandidateDraftChange[];
}

export interface ChangeTrayState {
  pendingCandidateCount: number;
  confirmedDraftCount: number;
  blockingRiskCount: number;
  syncStatusLabel: string;
  codeDiffStatus: CodeDiffStatus;
  canApply: boolean;
  canRevert: boolean;
}

const EMPTY_STAGE_STATES: Record<WorkflowStage, WorkflowStageStatus> = {
  understand: "idle",
  evidence: "idle",
  qa: "idle",
  draft: "idle",
  code: "idle",
};

const CORE_PATH_NODE_TYPES = new Set(["METHOD", "FLOW_SCOPE", "FLOW_ACTION", "SQL", "MQ_TOPIC", "MQ_CONSUMER", "HTTP_ENDPOINT"]);
const RESOURCE_NODE_TYPES = new Set(["XML_RESOURCE", "CONFIG_ITEM", "DOC_PAGE", "SQL", "MQ_TOPIC"]);

export function deriveCurrentTarget(args: {
  detailNode: LinkGraphNode | null;
  activeAnchorNode: LinkGraphNode | null;
  selectedNodeId: string | null;
}): { title: string; path: string | null } {
  const node = args.detailNode ?? args.activeAnchorNode;
  const title = node?.title?.trim() || args.selectedNodeId || "链路审查";
  const location = node?.location?.trim();
  const signature = node?.signature?.trim();
  const path = location && signature
    ? `${location} · ${signature}`
    : location || signature || null;
  return { title, path };
}

export function deriveWorkflowStageStates(args: {
  graphBeautificationResult: GraphBeautificationResult | null;
  graphBeautificationRequestState: AsyncRequestState;
  auditResult: GraphPatchResult | null;
  auditRequestState: AsyncRequestState;
  draftWorkbenchState: DraftWorkbenchState;
  draftValidationState: DraftValidationState | null;
  codeDiffStatus: CodeDiffStatus;
  codeDraftRequestState: AsyncRequestState;
}): Record<WorkflowStage, WorkflowStageStatus> {
  const threads = deriveInvestigationThreads(args.auditResult);
  const unresolvedThreads = threads.filter(isOpenRiskThread);
  const pendingCandidates = args.auditResult?.candidateChanges.filter((change) => change.status === "PENDING_CONFIRMATION") ?? [];
  const hasEvidence =
    (args.auditResult?.findings.length ?? 0) > 0
    || (args.auditResult?.sourceContext?.length ?? 0) > 0
    || (args.auditResult?.evidenceTrace?.length ?? 0) > 0
    || (args.graphBeautificationResult?.steps.some((step) => step.evidence.length > 0) ?? false);
  const hasDraft = args.draftWorkbenchState.draftChanges.length > 0 || args.draftWorkbenchState.draftNotes.length > 0;

  const next = { ...EMPTY_STAGE_STATES };
  next.understand = statusFromRequest(
    args.graphBeautificationRequestState,
    (args.graphBeautificationResult?.steps.length ?? 0) > 0,
  );
  next.evidence = args.auditRequestState.phase === "RUNNING" || args.graphBeautificationRequestState.phase === "RUNNING"
    ? "running"
    : requestFailed(args.auditRequestState) || requestFailed(args.graphBeautificationRequestState)
      ? "failed"
      : unresolvedThreads.length > 0 || pendingCandidates.some(hasWeakEvidence)
        ? "blocked"
        : hasEvidence
          ? "done"
          : "idle";
  next.qa = statusFromRequest(
    args.auditRequestState,
    args.auditResult != null && unresolvedThreads.length === 0,
    unresolvedThreads.length > 0,
  );
  next.draft = args.draftValidationState?.status === "REVIEW_REQUIRED"
    ? "blocked"
    : hasDraft && args.draftValidationState?.status !== "EMPTY"
      ? "done"
      : "idle";
  next.code = args.codeDraftRequestState.phase === "RUNNING" || args.codeDiffStatus === "RUNNING"
    ? "running"
    : requestFailed(args.codeDraftRequestState) || args.codeDiffStatus === "FAILED"
      ? "failed"
      : args.codeDiffStatus === "FRESH"
        ? "done"
        : args.codeDiffStatus === "STALE"
          ? "blocked"
          : "idle";
  return next;
}

export function deriveLinkGraphOutline(args: {
  activeViewGraph: LinkGraphDocument;
  fullGraph?: LinkGraphDocument | null;
  anchorNodeId?: string | null;
  selectedNodeId?: string | null;
  auditResult: GraphPatchResult | null;
  draftWorkbenchState: DraftWorkbenchState;
  draftChangedNodeIds: string[];
}): {
  metrics: LinkGraphOutlineMetrics;
  items: LinkGraphOutlineItem[];
} {
  const visibleNodes = args.activeViewGraph.nodes;
  const visibleNodeIds = new Set(visibleNodes.map((node) => node.id));
  const fullNodeCount = args.fullGraph?.nodes.length ?? args.activeViewGraph.nodeCount ?? visibleNodes.length;
  const threads = deriveInvestigationThreads(args.auditResult);
  const blockingThreads = threads.filter(isOpenRiskThread);
  const draftChangedSet = new Set(args.draftChangedNodeIds);
  const items: LinkGraphOutlineItem[] = [];
  const addedIds = new Set<string>();

  function addNode(node: LinkGraphNode, group: LinkGraphOutlineItem["group"], badge?: string, severity?: LinkGraphOutlineItem["severity"]) {
    const id = `${group}:${node.id}`;
    if (addedIds.has(id)) {
      return;
    }
    addedIds.add(id);
    items.push({
      id: node.id,
      label: node.title || node.id,
      kind: node.type,
      group,
      badge,
      severity,
      meta: [node.location, node.signature].filter(Boolean).join(" · ") || undefined,
    });
  }

  const anchorNode = visibleNodes.find((node) => node.id === args.anchorNodeId) ?? null;
  if (anchorNode) {
    addNode(anchorNode, "entry", "Anchor", "success");
  }
  visibleNodes
    .filter((node) => node.id !== anchorNode?.id && (node.type === "METHOD" || node.type === "HTTP_ENDPOINT" || node.id === args.selectedNodeId))
    .forEach((node) => addNode(node, "entry", node.id === args.selectedNodeId ? "Selected" : undefined, "info"));

  visibleNodes
    .filter((node) => CORE_PATH_NODE_TYPES.has(node.type))
    .forEach((node) => addNode(node, "criticalPath", undefined, node.certainty === "LLM_SUGGESTED" ? "warning" : "normal"));

  visibleNodes
    .filter((node) => RESOURCE_NODE_TYPES.has(node.type) || node.sourceTag === "UNCERTAIN_FACT")
    .forEach((node) => addNode(node, "evidence", node.sourceTag, node.certainty === "PROVEN" ? "success" : "info"));

  blockingThreads.forEach((thread) => {
    items.push({
      id: `thread:${thread.threadId}`,
      label: thread.title,
      kind: "RISK",
      group: "risk",
      badge: thread.status,
      severity: thread.status === "BLOCKED" ? "danger" : "warning",
      meta: thread.evidenceGap || thread.summary,
    });
  });

  args.draftWorkbenchState.draftChanges.forEach((entry) => {
    items.push({
      id: `draft:${entry.entryId}`,
      label: entry.title,
      kind: "DRAFT",
      group: "draft",
      badge: entry.kind,
      severity: "success",
      meta: entry.impactSummary || entry.reason,
    });
  });

  return {
    metrics: {
      visibleNodeCount: visibleNodes.length,
      fullNodeCount,
      sourceAnchorCount: anchorNode ? 1 : 0,
      inferredEvidenceCount: visibleNodes.filter((node) => node.certainty !== "PROVEN").length,
      draftImpactCount: visibleNodes.filter((node) => draftChangedSet.has(node.id) || visibleNodeIds.has(node.id) && draftChangedSet.has(node.id)).length,
      blockingRiskCount: blockingThreads.length,
    },
    items,
  };
}

export function deriveEvidencePanelState(args: {
  selectedNode: LinkGraphNode | null;
  auditResult: GraphPatchResult | null;
  graphBeautificationResult: GraphBeautificationResult | null;
}): EvidencePanelState {
  const selectedNodeId = args.selectedNode?.id ?? null;
  const threads = deriveInvestigationThreads(args.auditResult);
  const selectedFindings = [
    ...(args.auditResult?.findings ?? []),
    ...(args.graphBeautificationResult?.steps.flatMap((step) => step.evidence) ?? []),
  ].filter((finding) => selectedNodeId == null || findingTargetsNode(finding, selectedNodeId));
  const evidenceMap = new Map<string, EvidenceSummaryItem>();
  selectedFindings.forEach((finding) => {
    evidenceMap.set(finding.id, {
      id: finding.id,
      label: finding.claim,
      level: finding.evidenceLevel,
      references: finding.references,
    });
  });

  return {
    selectedNode: args.selectedNode,
    selectedNodeEvidence: Array.from(evidenceMap.values()),
    evidenceGaps: threads
      .filter((thread) => isOpenRiskThread(thread) && (selectedNodeId == null || thread.targetNodeIds.includes(selectedNodeId) || thread.evidence.some((finding) => findingTargetsNode(finding, selectedNodeId))))
      .map((thread) => ({
        id: thread.threadId,
        title: thread.title,
        detail: thread.evidenceGap || thread.summary,
        targetNodeIds: thread.targetNodeIds,
        severity: thread.status === "BLOCKED" ? "danger" : "warning",
      })),
    sourceSnippets: (args.auditResult?.sourceContext ?? [])
      .filter((snippet) => selectedNodeId == null || snippet.nodeId === selectedNodeId),
    evidenceTrace: (args.auditResult?.evidenceTrace ?? [])
      .filter((trace) => selectedNodeId == null || trace.nodeId === selectedNodeId || trace.resolvedNodeId === selectedNodeId),
    openRiskThreads: threads.filter((thread) => isOpenRiskThread(thread) && (selectedNodeId == null || thread.targetNodeIds.includes(selectedNodeId))),
    pendingCandidateChanges: (args.auditResult?.candidateChanges ?? [])
      .filter((change) => change.status === "PENDING_CONFIRMATION")
      .filter((change) => selectedNodeId == null || change.targetNodeIds.includes(selectedNodeId) || (change.evidence ?? []).some((finding) => findingTargetsNode(finding, selectedNodeId))),
  };
}

export function deriveChangeTrayState(args: {
  auditResult: GraphPatchResult | null;
  draftWorkbenchState: DraftWorkbenchState;
  draftValidationState: DraftValidationState | null;
  codeDiffStatus: CodeDiffStatus;
  canUndoDraftPatchApply: boolean;
  lastAppliedDraftPatchSummary: string | null;
  lastDraftPatchApplyResult: DraftPatchApplyResult | null;
}): ChangeTrayState {
  const pendingCandidateCount = args.auditResult?.candidateChanges.filter((change) => change.status === "PENDING_CONFIRMATION").length ?? 0;
  const confirmedDraftCount = args.draftWorkbenchState.draftChanges.length;
  const blockingRiskCount = args.draftValidationState?.unresolvedThreadIds.length
    ?? deriveInvestigationThreads(args.auditResult).filter(isOpenRiskThread).length;
  const syncStatusLabel = args.lastDraftPatchApplyResult?.summary
    ?? args.lastAppliedDraftPatchSummary
    ?? (pendingCandidateCount === 0 && confirmedDraftCount === 0 && args.codeDiffStatus === "MISSING"
      ? "暂无待应用变更"
      : "存在待处理变更");

  return {
    pendingCandidateCount,
    confirmedDraftCount,
    blockingRiskCount,
    syncStatusLabel,
    codeDiffStatus: args.codeDiffStatus,
    canApply: args.codeDiffStatus === "FRESH" && confirmedDraftCount > 0,
    canRevert: args.canUndoDraftPatchApply,
  };
}

function statusFromRequest(
  requestState: AsyncRequestState,
  isDone: boolean,
  isBlocked = false,
): WorkflowStageStatus {
  if (requestState.phase === "RUNNING") {
    return "running";
  }
  if (requestFailed(requestState)) {
    return "failed";
  }
  if (isBlocked) {
    return "blocked";
  }
  return isDone ? "done" : "idle";
}

function requestFailed(requestState: AsyncRequestState): boolean {
  return requestState.phase === "FAILED" || requestState.phase === "TIMED_OUT";
}

function isOpenRiskThread(thread: { status: string; resolution?: { status: string } | null }): boolean {
  if (thread.resolution?.status && thread.resolution.status !== "UNRESOLVED") {
    return false;
  }
  return thread.status === "OPEN" || thread.status === "BLOCKED";
}

function hasWeakEvidence(change: CandidateDraftChange): boolean {
  const evidence = change.evidence ?? [];
  if (evidence.length === 0) {
    return true;
  }
  return !evidence.some((finding) => finding.evidenceLevel === "DIRECT_SOURCE" || finding.evidenceLevel === "DIRECT_GRAPH");
}

function findingTargetsNode(finding: ResultEvidenceFinding, nodeId: string): boolean {
  return finding.references.some((reference) => reference.nodeId === nodeId);
}
