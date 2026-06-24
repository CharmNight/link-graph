// 混合工作台派生状态：把多个原始状态字段（图、QA 结果、草稿状态等）派生为
// 面向 UI 的最终展示数据（大纲条目、变更托盘状态、阶段状态等）。
// 集中在这里是为了让 UI 组件只关心渲染，不必各自重复派生逻辑。
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

/** 链路大纲的指标数据。 */
export interface LinkGraphOutlineMetrics {
  /** 可见节点数。 */
  visibleNodeCount: number;
  /** 完整节点数（包含隐藏）。 */
  fullNodeCount: number;
  /** 入口节点数。 */
  sourceAnchorCount: number;
  /** 推断证据节点数（非 PROVEN）。 */
  inferredEvidenceCount: number;
  /** 被草稿影响的节点数。 */
  draftImpactCount: number;
  /** 阻塞风险数。 */
  blockingRiskCount: number;
}

/** 单条链路大纲条目。 */
export interface LinkGraphOutlineItem {
  /** 条目 ID（通常是节点 ID）。 */
  id: string;
  /** 展示标签。 */
  label: string;
  /** 节点种类字符串（用于辅助显示）。 */
  kind: string;
  /** 所属分组。 */
  group: "entry" | "criticalPath" | "evidence" | "risk" | "draft";
  /** 可选徽章文本。 */
  badge?: string;
  /** 严重程度；用于行级样式。 */
  severity?: "normal" | "info" | "warning" | "danger" | "success";
  /** 元数据描述行。 */
  meta?: string;
}

/** 证据面板中的证据摘要条目。 */
export interface EvidenceSummaryItem {
  id: string;
  label: string;
  level: ResultEvidenceLevel;
  references: ResultEvidenceReference[];
}

/** 证据面板中的证据缺口条目。 */
export interface EvidenceGapItem {
  id: string;
  title: string;
  detail: string;
  targetNodeIds: string[];
  severity: "warning" | "danger";
}

/** 关系证据条目：把图边上的关系元数据展开为证据形态。 */
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

/** 证据面板的完整派生状态。 */
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

/** 变更托盘派生状态。 */
export interface ChangeTrayState {
  /** 待确认候选数。 */
  pendingCandidateCount: number;
  /** 已确认草稿数。 */
  confirmedDraftCount: number;
  /** 阻塞风险数。 */
  blockingRiskCount: number;
  /** 同步状态文案。 */
  syncStatusLabel: string;
  /** 代码 diff 状态。 */
  codeDiffStatus: CodeDiffStatus;
  /** 是否允许写入工程。 */
  canApply: boolean;
  /** 是否允许回退。 */
  canRevert: boolean;
}

/** 阶段状态的初始空值集合（所有阶段都为 idle）。 */
const EMPTY_STAGE_STATES: Record<WorkflowStage, WorkflowStageStatus> = {
  understand: "idle",
  evidence: "idle",
  qa: "idle",
  draft: "idle",
  code: "idle",
};

/** 关键路径上的节点类型集合（用于大纲"关键路径"分组）。 */
const CORE_PATH_NODE_TYPES = new Set(["METHOD", "FLOW_SCOPE", "FLOW_ACTION", "SQL", "MQ_TOPIC", "MQ_CONSUMER", "HTTP_ENDPOINT"]);
/** 资源类型节点集合（用于大纲"资源证据"分组）。 */
const RESOURCE_NODE_TYPES = new Set(["XML_RESOURCE", "CONFIG_ITEM", "DOC_PAGE", "SQL", "MQ_TOPIC"]);

/**
 * 派生"当前目标"信息：标题 + 路径。
 *
 * 优先用详情节点；缺失时用锚点节点；都没有时用选中节点 ID 兜底。
 * 路径由 location 与 signature 组合而成。
 */
export function deriveCurrentTarget(args: {
  detailNode: LinkGraphNode | null;
  activeAnchorNode: LinkGraphNode | null;
  selectedNodeId: string | null;
}): { title: string; path: string | null } {
  const node = args.detailNode ?? args.activeAnchorNode;
  const title = node?.title?.trim() || args.selectedNodeId || "链路审查";
  const location = node?.location?.trim();
  const signature = node?.signature?.trim();
  // 同时有 location 与 signature 时合并展示
  const path = location && signature
    ? `${location} · ${signature}`
    : location || signature || null;
  return { title, path };
}

/**
 * 派生 5 个工作流阶段的运行态。
 *
 * 各阶段依据其相关异步请求、QA 结果、草稿状态、代码 diff 状态等综合判断：
 * - understand：基于讲解请求状态；
 * - evidence：综合 QA、讲解、风险线程、证据是否存在；
 * - qa：基于 QA 请求状态与未解决风险；
 * - draft：基于草稿状态与校验结果；
 * - code：基于代码 diff 状态。
 */
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
  // 任一来源有证据都视为"有证据"
  const hasEvidence =
    (args.qaResult?.findings.length ?? 0) > 0
    || (args.qaResult?.sourceContext?.length ?? 0) > 0
    || (args.qaResult?.evidenceTrace?.length ?? 0) > 0
    || (args.graphBeautificationResult?.steps.some((step) => step.evidence.length > 0) ?? false);
  const hasDraft = args.draftWorkbenchState.draftChanges.length > 0 || args.draftWorkbenchState.draftNotes.length > 0;

  const next = { ...EMPTY_STAGE_STATES };
  // 理解阶段：讲解请求 + 是否已生成步骤
  next.understand = statusFromRequest(
    args.graphBeautificationRequestState,
    (args.graphBeautificationResult?.steps.length ?? 0) > 0,
  );
  // 证据阶段：综合多种状态
  next.evidence = args.qaRequestState.phase === "RUNNING" || args.graphBeautificationRequestState.phase === "RUNNING"
    ? "running"
    : requestFailed(args.qaRequestState) || requestFailed(args.graphBeautificationRequestState)
      ? "failed"
      : unresolvedThreads.length > 0 || pendingCandidates.some(hasWeakEvidence)
        ? "blocked"
        : hasEvidence
          ? "done"
          : "idle";
  // 问答阶段：基于 QA 请求；额外考虑未解决风险
  next.qa = statusFromRequest(
    args.qaRequestState,
    args.qaResult != null && unresolvedThreads.length === 0,
    unresolvedThreads.length > 0,
  );
  // 草稿阶段：基于校验状态；REVIEW_REQUIRED 视为阻塞
  next.draft = args.draftValidationState?.status === "REVIEW_REQUIRED"
    ? "blocked"
    : hasDraft && args.draftValidationState?.status !== "EMPTY"
      ? "done"
      : "idle";
  // 代码阶段：基于 diff 状态
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

/**
 * 派生链路大纲：把图节点按角色分组为大纲条目。
 *
 * 分组：入口（锚点 + 方法/HTTP/选中节点）→ 关键路径 → 资源证据 → 风险 → 草稿影响。
 * 每个节点最多出现一次（按 group+nodeId 去重）。
 */
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
  // 已添加条目的去重集合
  const addedIds = new Set<string>();

  /** 把节点加入指定分组；同 group+id 只加入一次。 */
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

  // 入口组：锚点优先；其次方法 / HTTP / 选中节点
  const anchorNode = visibleNodes.find((node) => node.id === args.anchorNodeId) ?? null;
  if (anchorNode) {
    addNode(anchorNode, "entry", "Anchor", "success");
  }
  visibleNodes
    .filter((node) => node.id !== anchorNode?.id && (node.type === "METHOD" || node.type === "HTTP_ENDPOINT" || node.id === args.selectedNodeId))
    .forEach((node) => addNode(node, "entry", node.id === args.selectedNodeId ? "Selected" : undefined, "info"));

  // 关键路径组：所有核心类型节点
  visibleNodes
    .filter((node) => CORE_PATH_NODE_TYPES.has(node.type))
    .forEach((node) => addNode(node, "criticalPath", undefined, node.certainty === "LLM_SUGGESTED" ? "warning" : "normal"));

  // 资源证据组：资源类型或不确定事实
  visibleNodes
    .filter((node) => RESOURCE_NODE_TYPES.has(node.type) || node.sourceTag === "UNCERTAIN_FACT")
    .forEach((node) => addNode(node, "evidence", node.sourceTag, node.certainty === "PROVEN" ? "success" : "info"));

  // 风险组：所有阻塞线程
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

  // 草稿影响组：所有草稿条目
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
      // 非确证的节点视为"推断证据"
      inferredEvidenceCount: visibleNodes.filter((node) => node.certainty !== "PROVEN").length,
      draftImpactCount: visibleNodes.filter((node) => draftChangedSet.has(node.id) || visibleNodeIds.has(node.id) && draftChangedSet.has(node.id)).length,
      blockingRiskCount: blockingThreads.length,
    },
    items,
  };
}

/**
 * 派生证据面板状态。
 *
 * 把 QA 结果中的发现、证据轨迹、源码片段等综合过滤，
 * 当选中具体节点时只展示与该节点相关的内容；未选中时展示全部。
 */
export function deriveEvidencePanelState(args: {
  selectedNode: LinkGraphNode | null;
  activeViewGraph?: LinkGraphDocument | null;
  qaResult: GraphPatchResult | null;
  graphBeautificationResult: GraphBeautificationResult | null;
}): EvidencePanelState {
  const selectedNodeId = args.selectedNode?.id ?? null;
  const threads = deriveInvestigationThreads(args.qaResult);
  // 合并 QA 发现 + 链路讲解步骤中的证据
  const selectedFindings = [
    ...(args.qaResult?.findings ?? []),
    ...(args.graphBeautificationResult?.steps.flatMap((step) => step.evidence) ?? []),
  ].filter((finding) => selectedNodeId == null || findingTargetsNode(finding, selectedNodeId));
  // 按 ID 去重
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
      // 风险线程中尚未化解的；无选中或与选中相关
      .filter((thread) => isOpenRiskThread(thread) && (selectedNodeId == null || thread.targetNodeIds.includes(selectedNodeId) || thread.evidence.some((finding) => findingTargetsNode(finding, selectedNodeId))))
      .map((thread) => ({
        id: thread.threadId,
        title: thread.title,
        detail: thread.evidenceGap || thread.summary,
        targetNodeIds: thread.targetNodeIds,
        severity: thread.status === "BLOCKED" ? "danger" : "warning",
      })),
    // 源码片段：按节点过滤
    sourceSnippets: (args.qaResult?.sourceContext ?? [])
      .filter((snippet) => selectedNodeId == null || snippet.nodeId === selectedNodeId),
    // 证据轨迹：按节点过滤（包括 resolvedNodeId）
    evidenceTrace: (args.qaResult?.evidenceTrace ?? [])
      .filter((trace) => selectedNodeId == null || trace.nodeId === selectedNodeId || trace.resolvedNodeId === selectedNodeId),
    openRiskThreads: threads.filter((thread) => isOpenRiskThread(thread) && (selectedNodeId == null || thread.targetNodeIds.includes(selectedNodeId))),
    pendingCandidateChanges: (args.qaResult?.candidateChanges ?? [])
      .filter((change) => change.status === "PENDING_CONFIRMATION")
      .filter((change) => selectedNodeId == null || change.targetNodeIds.includes(selectedNodeId) || (change.evidence ?? []).some((finding) => findingTargetsNode(finding, selectedNodeId))),
  };
}

/**
 * 派生变更托盘状态。
 *
 * 综合候选数、确认数、阻塞风险数、代码 diff 状态等得出：
 * - 是否允许写入工程（diff 新鲜 + 有确认草稿 + 无阻塞）；
 * - 是否允许回退（取决于是否能撤销最近一次写入）；
 * - 同步状态文案（带摘要时优先用摘要）。
 */
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
  // 同步状态文案优先级：最近一次应用摘要 > 历史摘要 > 默认提示
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
    // 写入工程：diff 新鲜 + 有确认草稿 + 无阻塞
    canApply: args.codeDiffStatus === "FRESH" && confirmedDraftCount > 0 && blockingRiskCount === 0,
    canRevert: args.canUndoDraftPatchApply,
  };
}

/**
 * 从异步请求状态派生阶段状态。
 *
 * 优先级：RUNNING → running；FAILED/TIMED_OUT → failed；blocked → blocked；其余按 isDone。
 */
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

/** 判断请求是否失败（FAILED 或 TIMED_OUT）。 */
function requestFailed(requestState: AsyncRequestState): boolean {
  return requestState.phase === "FAILED" || requestState.phase === "TIMED_OUT";
}

/**
 * 判断调查线程是否仍是"开放风险"。
 * 已化解（resolution 非 UNRESOLVED）视为关闭；状态为 OPEN 或 BLOCKED 视为开放。
 */
function isOpenRiskThread(thread: { status: string; resolution?: { status: string } | null }): boolean {
  if (thread.resolution?.status && thread.resolution.status !== "UNRESOLVED") {
    return false;
  }
  return thread.status === "OPEN" || thread.status === "BLOCKED";
}

/**
 * 判断候选变更是否"证据薄弱"。
 * 无任何证据、或所有证据都不直接（DIRECT_SOURCE/DIRECT_GRAPH）都视为薄弱。
 */
function hasWeakEvidence(change: CandidateDraftChange): boolean {
  const evidence = change.evidence ?? [];
  if (evidence.length === 0) {
    return true;
  }
  return !evidence.some((finding) => finding.evidenceLevel === "DIRECT_SOURCE" || finding.evidenceLevel === "DIRECT_GRAPH");
}

/** 判断证据是否指向某节点（通过 reference.nodeId）。 */
function findingTargetsNode(finding: ResultEvidenceFinding, nodeId: string): boolean {
  return finding.references.some((reference) => reference.nodeId === nodeId);
}

/** 架构图关系种类集合；只把这些种类的关系作为"关系证据"展示。 */
const ARCHITECTURE_RELATION_KINDS = new Set([
  "INJECTS",
  "SPI_PROVIDES",
  "SERVICE_LOADER_LOADS",
  "REFLECTS_TO",
  "USES_PROXY",
  "SPRING_EVENT_PUBLISHES",
  "SPRING_EVENT_LISTENS",
]);

/**
 * 派生"关系证据"列表：从图中找出与选中节点相关的架构关系边，
 * 把它们转换为证据形态的展示对象。
 */
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
  // 与选中节点相关的所有边
  graph.edges
    .filter((edge) => edge.source === selectedNodeId || edge.target === selectedNodeId)
    .forEach((edge) => {
      const item = relationEvidenceFromEdge(edge, nodesById);
      if (item) {
        items.set(item.id, item);
      }
    });
  // 节点本身的关系元数据（不一定有边）
  const selectedNodeItem = relationEvidenceFromNode(selectedNode);
  if (selectedNodeItem) {
    items.set(selectedNodeItem.id, selectedNodeItem);
  }
  // 按标签排序，保证多次刷新顺序稳定
  return Array.from(items.values()).sort((a, b) => a.label.localeCompare(b.label));
}

/** 把一条边转换为关系证据（若不是架构关系种类则返回 null）。 */
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

/** 把节点上携带的关系元数据转换为关系证据（无关系时返回 null）。 */
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

/**
 * 取关系种类。只接受 ARCHITECTURE_RELATION_KINDS 中的种类。
 * 其他关系不作为"关系证据"展示。
 */
function relationKind(metadata?: Record<string, string>): string | null {
  const kind = metadataValue(metadata, "jvm.relation.kind", "relation.kind");
  if (!kind || !ARCHITECTURE_RELATION_KINDS.has(kind)) {
    return null;
  }
  return kind;
}

/** 取关系置信度字符串。 */
function relationConfidence(metadata?: Record<string, string>): string | null {
  return metadataValue(metadata, "jvm.relation.confidence", "relation.confidence");
}

/**
 * 从元数据中取值：按 keys 顺序查找，返回第一个非空值。
 * 兼容多种 key 命名（jvm./非前缀）。
 */
function metadataValue(metadata: Record<string, string> | undefined, ...keys: string[]): string | null {
  for (const key of keys) {
    const value = metadata?.[key]?.trim();
    if (value) {
      return value;
    }
  }
  return null;
}

/** 把关系种类代码转为中文标签。 */
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

/**
 * 把节点转换为证据引用列表。
 * 优先用元数据中的源码位置；缺失时只填 nodeId。
 */
function nodeReference(node: LinkGraphNode | null): ResultEvidenceReference[] {
  if (!node) {
    return [];
  }
  const metadataPath = metadataValue(node.metadata, "source.filePath");
  const filePath = metadataPath ?? node.location?.split(":")[0] ?? null;
  if (!filePath) {
    // 无路径信息时只填 nodeId
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
