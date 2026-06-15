import { deriveInvestigationThreads } from "../investigationThreads";
import type { CodeDiffStatus } from "../workbenchStatusModel";
import type {
  AsyncRequestState,
  CandidateDraftChange,
  DraftPatchApplyResult,
  DraftValidationState,
  DraftWorkbenchState,
  EvidenceTraceEntry,
  GraphBeautificationResult,
  GraphPatchResult,
  LinkGraphEdge,
  LinkGraphDocument,
  LinkGraphNode,
  ResultEvidenceFinding,
  ResultEvidenceLevel,
  ResultEvidenceReference,
  SourceSnippetContext,
} from "../types";
import type { WorkflowStage, WorkflowStageStatus } from "../workflow/workflowStage";

export type { CodeDiffStatus };

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

export interface RelationEvidenceItem {
  id: string;
  label: string;
  kind: string;
  confidence: string | null;
  source: string | null;
  resolverId: string | null;
  count: string | null;
  references: ResultEvidenceReference[];
}

export interface EvidencePanelState {
  selectedNode: LinkGraphNode | null;
  selectedNodeEvidence: EvidenceSummaryItem[];
  relationEvidence: RelationEvidenceItem[];
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
  qaResult: GraphPatchResult | null;
  qaRequestState: AsyncRequestState;
  draftWorkbenchState: DraftWorkbenchState;
  draftValidationState: DraftValidationState | null;
  codeDiffStatus: CodeDiffStatus;
  codeDraftRequestState: AsyncRequestState;
}): Record<WorkflowStage, WorkflowStageStatus> {
  const threads = deriveInvestigationThreads(args.qaResult);
  const unresolvedThreads = threads.filter(isOpenRiskThread);
  const pendingCandidates = args.qaResult?.candidateChanges.filter((change) => change.status === "PENDING_CONFIRMATION") ?? [];
  const hasEvidence =
    (args.qaResult?.findings.length ?? 0) > 0
    || (args.qaResult?.sourceContext?.length ?? 0) > 0
    || (args.qaResult?.evidenceTrace?.length ?? 0) > 0
    || (args.graphBeautificationResult?.steps.some((step) => step.evidence.length > 0) ?? false);
  const hasDraft = args.draftWorkbenchState.draftChanges.length > 0 || args.draftWorkbenchState.draftNotes.length > 0;

  const next = { ...EMPTY_STAGE_STATES };
  next.understand = statusFromRequest(
    args.graphBeautificationRequestState,
    (args.graphBeautificationResult?.steps.length ?? 0) > 0,
  );
  next.evidence = args.qaRequestState.phase === "RUNNING" || args.graphBeautificationRequestState.phase === "RUNNING"
    ? "running"
    : requestFailed(args.qaRequestState) || requestFailed(args.graphBeautificationRequestState)
      ? "failed"
      : unresolvedThreads.length > 0 || pendingCandidates.some(hasWeakEvidence)
        ? "blocked"
        : hasEvidence
          ? "done"
          : "idle";
  next.qa = statusFromRequest(
    args.qaRequestState,
    args.qaResult != null && unresolvedThreads.length === 0,
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
  qaResult: GraphPatchResult | null;
  draftWorkbenchState: DraftWorkbenchState;
  draftChangedNodeIds: string[];
}): {
  metrics: LinkGraphOutlineMetrics;
  items: LinkGraphOutlineItem[];
} {
  const visibleNodes = args.activeViewGraph.nodes;
  const visibleNodeIds = new Set(visibleNodes.map((node) => node.id));
  const fullNodeCount = args.fullGraph?.nodes.length ?? args.activeViewGraph.nodeCount ?? visibleNodes.length;
  const threads = deriveInvestigationThreads(args.qaResult);
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
  activeViewGraph?: LinkGraphDocument | null;
  qaResult: GraphPatchResult | null;
  graphBeautificationResult: GraphBeautificationResult | null;
}): EvidencePanelState {
  const selectedNodeId = args.selectedNode?.id ?? null;
  const threads = deriveInvestigationThreads(args.qaResult);
  const selectedFindings = [
    ...(args.qaResult?.findings ?? []),
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
    relationEvidence: deriveRelationEvidence(args.activeViewGraph, selectedNodeId),
    evidenceGaps: threads
      .filter((thread) => isOpenRiskThread(thread) && (selectedNodeId == null || thread.targetNodeIds.includes(selectedNodeId) || thread.evidence.some((finding) => findingTargetsNode(finding, selectedNodeId))))
      .map((thread) => ({
        id: thread.threadId,
        title: thread.title,
        detail: thread.evidenceGap || thread.summary,
        targetNodeIds: thread.targetNodeIds,
        severity: thread.status === "BLOCKED" ? "danger" : "warning",
      })),
    sourceSnippets: (args.qaResult?.sourceContext ?? [])
      .filter((snippet) => selectedNodeId == null || snippet.nodeId === selectedNodeId),
    evidenceTrace: (args.qaResult?.evidenceTrace ?? [])
      .filter((trace) => selectedNodeId == null || trace.nodeId === selectedNodeId || trace.resolvedNodeId === selectedNodeId),
    openRiskThreads: threads.filter((thread) => isOpenRiskThread(thread) && (selectedNodeId == null || thread.targetNodeIds.includes(selectedNodeId))),
    pendingCandidateChanges: (args.qaResult?.candidateChanges ?? [])
      .filter((change) => change.status === "PENDING_CONFIRMATION")
      .filter((change) => selectedNodeId == null || change.targetNodeIds.includes(selectedNodeId) || (change.evidence ?? []).some((finding) => findingTargetsNode(finding, selectedNodeId))),
  };
}

export function deriveChangeTrayState(args: {
  qaResult: GraphPatchResult | null;
  draftWorkbenchState: DraftWorkbenchState;
  draftValidationState: DraftValidationState | null;
  codeDiffStatus: CodeDiffStatus;
  canUndoDraftPatchApply: boolean;
  lastAppliedDraftPatchSummary: string | null;
  lastDraftPatchApplyResult: DraftPatchApplyResult | null;
}): ChangeTrayState {
  const pendingCandidateCount = args.qaResult?.candidateChanges.filter((change) => change.status === "PENDING_CONFIRMATION").length ?? 0;
  const confirmedDraftCount = args.draftWorkbenchState.draftChanges.length;
  const blockingRiskCount = args.draftValidationState?.unresolvedThreadIds.length
    ?? deriveInvestigationThreads(args.qaResult).filter(isOpenRiskThread).length;
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

const ARCHITECTURE_RELATION_KINDS = new Set([
  "INJECTS",
  "SPI_PROVIDES",
  "SERVICE_LOADER_LOADS",
  "REFLECTS_TO",
  "USES_PROXY",
  "SPRING_EVENT_PUBLISHES",
  "SPRING_EVENT_LISTENS",
]);

function deriveRelationEvidence(
  graph: LinkGraphDocument | null | undefined,
  selectedNodeId: string | null,
): RelationEvidenceItem[] {
  if (!graph || !selectedNodeId) {
    return [];
  }
  const nodesById = new Map(graph.nodes.map((node) => [node.id, node]));
  const selectedNode = nodesById.get(selectedNodeId) ?? null;
  const items = new Map<string, RelationEvidenceItem>();
  graph.edges
    .filter((edge) => edge.source === selectedNodeId || edge.target === selectedNodeId)
    .forEach((edge) => {
      const item = relationEvidenceFromEdge(edge, nodesById);
      if (item) {
        items.set(item.id, item);
      }
    });
  const selectedNodeItem = relationEvidenceFromNode(selectedNode);
  if (selectedNodeItem) {
    items.set(selectedNodeItem.id, selectedNodeItem);
  }
  return Array.from(items.values()).sort((a, b) => a.label.localeCompare(b.label));
}

function relationEvidenceFromEdge(
  edge: LinkGraphEdge,
  nodesById: Map<string, LinkGraphNode>,
): RelationEvidenceItem | null {
  const kind = relationKind(edge.metadata);
  if (!kind) {
    return null;
  }
  const sourceNode = nodesById.get(edge.source) ?? null;
  const targetNode = nodesById.get(edge.target) ?? null;
  return {
    id: edge.id,
    label: `${relationKindLabel(kind)}：${sourceNode?.title ?? edge.source} -> ${targetNode?.title ?? edge.target}`,
    kind,
    confidence: relationConfidence(edge.metadata),
    source: metadataValue(edge.metadata, "jvm.relation.source", "relation.source"),
    resolverId: metadataValue(edge.metadata, "relation.resolverId", "jvm.relation.resolverId"),
    count: metadataValue(edge.metadata, "jvm.relation.count", "architecture.count", "call.count", "usage.count"),
    references: [sourceNode, targetNode].flatMap((node) => nodeReference(node)),
  };
}

function relationEvidenceFromNode(node: LinkGraphNode | null): RelationEvidenceItem | null {
  const kind = relationKind(node?.metadata);
  if (!node || !kind) {
    return null;
  }
  return {
    id: `node:${node.id}:${kind}`,
    label: `${relationKindLabel(kind)}：${node.title}`,
    kind,
    confidence: relationConfidence(node.metadata),
    source: metadataValue(node.metadata, "jvm.relation.source", "relation.source"),
    resolverId: metadataValue(node.metadata, "relation.resolverId", "jvm.relation.resolverId"),
    count: metadataValue(node.metadata, "jvm.relation.count", "architecture.count", "call.count", "usage.count"),
    references: nodeReference(node),
  };
}

function relationKind(metadata?: Record<string, string>): string | null {
  const kind = metadataValue(metadata, "jvm.relation.kind", "relation.kind");
  if (!kind || !ARCHITECTURE_RELATION_KINDS.has(kind)) {
    return null;
  }
  return kind;
}

function relationConfidence(metadata?: Record<string, string>): string | null {
  return metadataValue(metadata, "jvm.relation.confidence", "relation.confidence");
}

function metadataValue(metadata: Record<string, string> | undefined, ...keys: string[]): string | null {
  for (const key of keys) {
    const value = metadata?.[key]?.trim();
    if (value) {
      return value;
    }
  }
  return null;
}

function relationKindLabel(kind: string): string {
  switch (kind) {
    case "INJECTS":
      return "注入";
    case "SPI_PROVIDES":
    case "SERVICE_LOADER_LOADS":
      return "SPI";
    case "REFLECTS_TO":
      return "反射";
    case "USES_PROXY":
      return "代理";
    case "SPRING_EVENT_PUBLISHES":
      return "事件发布";
    case "SPRING_EVENT_LISTENS":
      return "事件监听";
    default:
      return kind;
  }
}

function nodeReference(node: LinkGraphNode | null): ResultEvidenceReference[] {
  if (!node) {
    return [];
  }
  const metadataPath = metadataValue(node.metadata, "source.filePath");
  const filePath = metadataPath ?? node.location?.split(":")[0] ?? null;
  if (!filePath) {
    return [{ nodeId: node.id }];
  }
  return [{
    nodeId: node.id,
    filePath,
    startLine: metadataValue(node.metadata, "source.startLine") != null
      ? Number(metadataValue(node.metadata, "source.startLine"))
      : undefined,
    endLine: metadataValue(node.metadata, "source.endLine") != null
      ? Number(metadataValue(node.metadata, "source.endLine"))
      : undefined,
  }];
}
