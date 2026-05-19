import { useMemo, useState } from "react";
import { buildEdgeActions as buildSharedEdgeActions } from "../../components/graph/actions/actionSchema";
import type { GraphContextMenuAction } from "../../components/graph/actions/actionSchema";
import { DraftCompareSummary } from "../../components/DraftCompareSummary";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { nodeCardWidth } from "../../graphNodeSizing";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { useMeasuredLayout } from "../../reactflow/useMeasuredLayout";
import { canNavigateToSource } from "../../sourceNavigation";
import type { LinkGraphNode, NodeType, ReviewGraphViewDocument } from "../../types";
import {
  ARCHITECTURE_GRAPH_NODE_TYPES,
  buildArchitectureGraphEdges,
  buildArchitectureGraphNodes,
} from "../architecture/architectureGraphNodes";
import type { ViewStageProps } from "../viewStageProps";
import { layoutReviewGraphView } from "./reviewGraphLayout";

interface ReviewGraphViewProps extends ViewStageProps {
  view: ReviewGraphViewDocument;
}

type ReviewRole = "ALL" | "CHANGED" | "UPSTREAM" | "DOWNSTREAM" | "RELATED_TEST";

const FILTER_TYPES: Array<NodeType | "ALL"> = ["ALL", "CLASS", "METHOD", "INTERFACE", "RESOURCE", "CONFIG_ITEM"];
const ROLE_FILTERS: Array<{ id: ReviewRole; label: string }> = [
  { id: "ALL", label: "全部角色" },
  { id: "CHANGED", label: "变更符号" },
  { id: "UPSTREAM", label: "上游" },
  { id: "DOWNSTREAM", label: "下游" },
  { id: "RELATED_TEST", label: "相关测试" },
];

function nodeMatchesQuery(node: LinkGraphNode, query: string): boolean {
  const normalized = query.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  return [
    node.title,
    node.signature,
    node.location,
    node.doc,
    node.metadata?.["review.qualifiedName"],
    node.metadata?.["architecture.package"],
    node.metadata?.["source.filePath"],
  ].some((value) => value?.toLowerCase().includes(normalized));
}

function changeKindLabel(kind: string | undefined | null): string {
  switch (kind) {
    case "ADDED":
    case "HUNK_ADDED":
      return "新增";
    case "DELETED":
    case "HUNK_DELETED":
      return "删除";
    case "RENAMED":
    case "HUNK_RENAMED":
      return "重命名";
    case "COPIED":
    case "HUNK_COPIED":
      return "复制";
    case "MODIFIED":
    case "HUNK_MODIFIED":
      return "修改";
    default:
      return kind ?? "变更";
  }
}

function reasonLabel(reason: string | undefined | null): string {
  switch (reason) {
    case "CALL_PATH":
      return "调用路径";
    case "TEST_ANNOTATION":
      return "测试注解";
    case "TEST_SOURCE_ROOT":
      return "测试源码根";
    default:
      return reason ?? "相关关系";
  }
}

function displayPath(primary: string | null | undefined, fallback: string | null | undefined): string {
  const path = primary ?? fallback ?? "";
  return path.split("/").slice(-4).join("/") || path || "unknown";
}

function simpleName(value: string): string {
  return value.split(".").pop() ?? value;
}

function formatCount(value: number | undefined | null): string {
  return (value ?? 0).toLocaleString("zh-CN");
}

function lineRange(start?: number | null, end?: number | null): string {
  if (start == null && end == null) {
    return "";
  }
  if (start != null && end != null && start !== end) {
    return `:${start}-${end}`;
  }
  return `:${start ?? end}`;
}

function reviewScopeTitle(view: ReviewGraphViewDocument): string {
  const changedFiles = view.changedFiles ?? [];
  if (changedFiles.length === 0) {
    return view.visibleGraph.nodes.length > 0 ? "符号影响范围" : "暂无可追溯符号";
  }
  if (changedFiles.length === 1) {
    return displayPath(changedFiles[0].newPath, changedFiles[0].oldPath);
  }
  return `${formatCount(changedFiles.length)} 个文件变更`;
}

function reviewScopeDetail(view: ReviewGraphViewDocument): string {
  const changedHunkCount = view.changedHunks?.length ?? 0;
  const unmatchedHunkCount = view.unmatchedHunks?.length ?? 0;
  const changedFileCount = view.changedFiles?.length ?? 0;
  const segments = [
    `文件 ${formatCount(changedFileCount)}`,
    `hunk ${formatCount(changedHunkCount)}`,
  ];
  if (unmatchedHunkCount > 0) {
    segments.push(`未匹配 ${formatCount(unmatchedHunkCount)}`);
  }
  return segments.join(" · ");
}

function ReviewGraphDetailPanels({ view }: { view: ReviewGraphViewDocument }) {
  const changedFiles = view.changedFiles ?? [];
  const unmatchedHunks = view.unmatchedHunks ?? [];
  const baselineOnlySymbols = view.baselineOnlySymbols ?? [];
  const relatedTests = view.relatedTests ?? [];
  const affectedPackages = view.affectedPackages ?? [];
  const affectedModules = view.affectedModules ?? [];
  const evidenceSnippets = view.evidenceSnippets ?? [];
  const affectedScopeCount = affectedPackages.length + affectedModules.length + relatedTests.length;
  const hasDetails = changedFiles.length > 0
    || unmatchedHunks.length > 0
    || baselineOnlySymbols.length > 0
    || relatedTests.length > 0
    || affectedPackages.length > 0
    || affectedModules.length > 0
    || evidenceSnippets.length > 0;

  if (!hasDetails) {
    return null;
  }

  return (
    <div className="review-detail-grid">
      <section className="review-detail-panel" aria-label="Review Graph 变更文件">
        <div className="review-detail-head">
          <span className="canvas-reading-label">变更文件</span>
          <span className="review-detail-count">{formatCount(changedFiles.length)}</span>
        </div>
        <div className="review-detail-list">
          {changedFiles.slice(0, 6).map((file, index) => (
            <div className="review-detail-row" key={`${file.oldPath ?? ""}:${file.newPath ?? ""}:${index}`}>
              <strong>{displayPath(file.newPath, file.oldPath)}</strong>
              <span>{changeKindLabel(file.changeKind)} · hunk {file.hunkCount}{file.similarity != null ? ` · ${file.similarity}%` : ""}</span>
            </div>
          ))}
          {changedFiles.length === 0 ? <span className="muted">无结构化文件变更</span> : null}
          {changedFiles.length > 6 ? <span className="muted">另有 {formatCount(changedFiles.length - 6)} 个文件已收起</span> : null}
        </div>
        {unmatchedHunks.length > 0 ? (
          <div className="review-unmatched-list">
            <span className="canvas-reading-label">未匹配 hunk</span>
            {unmatchedHunks.slice(0, 4).map((hunk, index) => (
              <code key={`${hunk.filePath}:${hunk.header}:${index}`}>
                {displayPath(hunk.newFilePath, hunk.oldFilePath)}{lineRange(hunk.newStartLine ?? hunk.oldStartLine, null)}
              </code>
            ))}
          </div>
        ) : null}
      </section>

      <section className="review-detail-panel" aria-label="Review Graph 基线符号">
        <div className="review-detail-head">
          <span className="canvas-reading-label">基线符号</span>
          <span className="review-detail-count">{formatCount(baselineOnlySymbols.length)}</span>
        </div>
        <div className="review-detail-list">
          {baselineOnlySymbols.slice(0, 5).map((symbol) => (
            <div className="review-detail-row" key={symbol.symbolId}>
              <strong>{symbol.qualifiedName}</strong>
              <span>{changeKindLabel(symbol.changeKind)}{symbol.unavailableReason ? ` · ${symbol.unavailableReason}` : ""}</span>
            </div>
          ))}
          {baselineOnlySymbols.length === 0 ? <span className="muted">无仅基线符号</span> : null}
          {baselineOnlySymbols.length > 5 ? <span className="muted">另有 {formatCount(baselineOnlySymbols.length - 5)} 个基线符号已收起</span> : null}
        </div>
      </section>

      <section className="review-detail-panel" aria-label="Review Graph 影响范围">
        <div className="review-detail-head">
          <span className="canvas-reading-label">影响范围</span>
          <span className="review-detail-count">{formatCount(affectedScopeCount)}</span>
        </div>
        <div className="review-chip-row">
          {affectedPackages.slice(0, 6).map((name) => <span className="review-chip" key={`pkg:${name}`}>{name}</span>)}
          {affectedModules.slice(0, 4).map((name) => <span className="review-chip" key={`mod:${name}`}>{name}</span>)}
        </div>
        <div className="review-detail-list">
          {relatedTests.slice(0, 5).map((test) => (
            <div className="review-detail-row" key={test.symbolId}>
              <strong>{simpleName(test.qualifiedName)}</strong>
              <span>{reasonLabel(test.reason)}{test.startLine != null ? ` · ${displayPath(test.filePath, null)}:${test.startLine}` : ""}</span>
            </div>
          ))}
          {relatedTests.length === 0 ? <span className="muted">无 relation 可追溯的相关测试</span> : null}
          {relatedTests.length > 5 ? <span className="muted">另有 {formatCount(relatedTests.length - 5)} 个相关测试已收起</span> : null}
        </div>
      </section>

      <section className="review-detail-panel review-evidence-panel" aria-label="Review Graph 证据片段">
        <div className="review-detail-head">
          <span className="canvas-reading-label">证据片段</span>
          <span className="review-detail-count">{formatCount(evidenceSnippets.length)}</span>
        </div>
        <div className="review-evidence-list">
          {evidenceSnippets.slice(0, 3).map((evidence, index) => (
            <div className="review-evidence-item" key={`${evidence.title}:${evidence.filePath ?? ""}:${index}`}>
              <strong>{evidence.title}</strong>
              <span>{displayPath(evidence.filePath, null)}{lineRange(evidence.startLine, evidence.endLine)}</span>
              {evidence.snippet ? <pre>{evidence.snippet.slice(0, 420)}</pre> : <span className="muted">{evidence.unavailableReason}</span>}
            </div>
          ))}
          {evidenceSnippets.length === 0 ? <span className="muted">无证据片段</span> : null}
          {evidenceSnippets.length > 3 ? <span className="muted">另有 {formatCount(evidenceSnippets.length - 3)} 个证据片段已收起</span> : null}
        </div>
      </section>
    </div>
  );
}

function reviewNodeActions(args: {
  nodeId: string;
  node: LinkGraphNode | null;
  canOpenSource: boolean;
  collapsed: boolean;
  onInspectNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
  onRequestQa: (selectedNodeId?: string) => void;
  onOpenQa: (selectedNodeId?: string) => void;
  onToggleCollapseNode: (nodeId: string) => void;
  onFormatLayout: () => void;
  onClose: () => void;
}): GraphContextMenuAction[] {
  const actions: GraphContextMenuAction[] = [
    {
      id: "inspect-node",
      label: "查看详情",
      onSelect: () => {
        args.onInspectNode(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "toggle-collapse",
      label: args.collapsed ? "展开下游" : "折叠下游",
      onSelect: () => {
        args.onToggleCollapseNode(args.nodeId);
        args.onClose();
      },
    },
  ];
  if (args.canOpenSource) {
    actions.push({
      id: "open-source",
      label: "打开源码",
      onSelect: () => {
        args.onRequestSourceNavigation(args.nodeId);
        args.onClose();
      },
    });
  }
  actions.push(
    {
      id: "beautify-node",
      label: "讲解当前影响",
      onSelect: () => {
        args.onRequestBeautification(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "qa-node",
      label: "问答当前节点",
      onSelect: () => {
        args.onRequestQa(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "set-qa-anchor",
      label: "设为问答范围起点",
      onSelect: () => {
        args.onOpenQa(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "format-layout",
      label: "重新整理布局",
      onSelect: () => {
        args.onFormatLayout();
        args.onClose();
      },
    },
  );
  return actions;
}

export function ReviewGraphView({
  view,
  selectedNodeId,
  focusNodeRequest = null,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareProjection = null,
  selectedGroupNodeIds = [],
  hiddenNodeIds = [],
  collapsedNodeIds = [],
  experiments = null,
  onSelectNode,
  onSelectionGroupChange = () => undefined,
  onInspectNode,
  onCreateEdge,
  onDeleteEdge,
  onMoveNode,
  onMoveNodes,
  onRequestSourceNavigation,
  onRequestBeautification = () => undefined,
  onRequestQa = () => undefined,
  onToggleCollapseNode = () => undefined,
  onOpenQa = () => undefined,
}: ReviewGraphViewProps) {
  const [query, setQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState<NodeType | "ALL">("ALL");
  const [roleFilter, setRoleFilter] = useState<ReviewRole>("ALL");
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  const presentedGraph = draftCompareProjection?.compareGraph ?? view.visibleGraph;
  const layoutState = useMeasuredLayout({
    graph: presentedGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    nodeSizeRegistry,
    layout: layoutReviewGraphView,
    debugLabel: "review-graph",
  });

  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  const collapsedNodeIdSet = useMemo(() => new Set(collapsedNodeIds), [collapsedNodeIds]);
  const visibleNodes = useMemo(
    () => layoutState.nodes.filter((node) =>
      !hiddenNodeIdSet.has(node.id)
      && (typeFilter === "ALL" || node.type === typeFilter)
      && (roleFilter === "ALL" || node.metadata?.["review.role"] === roleFilter)
      && nodeMatchesQuery(node, query)
    ),
    [hiddenNodeIdSet, layoutState.nodes, query, roleFilter, typeFilter],
  );
  const visibleNodeIds = useMemo(() => new Set(visibleNodes.map((node) => node.id)), [visibleNodes]);
  const visibleEdges = useMemo(
    () => layoutState.edges.filter((edge) => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target)),
    [layoutState.edges, visibleNodeIds],
  );
  const isLayoutLoading = layoutState.layoutPending && presentedGraph.nodes.length > 0 && layoutState.nodes.length === 0;
  const nodeIndex = useMemo(() => new Map(visibleNodes.map((node) => [node.id, node])), [visibleNodes]);
  const selectedNode = useMemo(
    () => visibleNodes.find((node) => node.id === selectedNodeId) ?? visibleNodes[0] ?? null,
    [selectedNodeId, visibleNodes],
  );
  const flowNodes = useMemo(
    () => buildArchitectureGraphNodes({
      nodes: visibleNodes,
      selectedNodeId,
      explanationFocusNodeId,
      draftChangedNodeIds,
      draftCompareNodeStatuses: draftCompareProjection?.nodeStatuses,
      nodeSizeRegistry,
    }),
    [draftChangedNodeIds, draftCompareProjection?.nodeStatuses, explanationFocusNodeId, nodeSizeRegistry, selectedNodeId, visibleNodes],
  );
  const flowEdges = useMemo(
    () => buildArchitectureGraphEdges({
      edges: visibleEdges,
      draftCompareEdgeStatuses: draftCompareProjection?.edgeStatuses,
    }),
    [draftCompareProjection?.edgeStatuses, visibleEdges],
  );
  const summaryMetrics = [
    { label: "变更符号", value: view.summary.changedSymbolCount },
    { label: "上游", value: view.summary.upstreamCount },
    { label: "下游", value: view.summary.downstreamCount },
    { label: "相关测试", value: view.summary.relatedTestCount },
  ];

  const header = (
    <section className="canvas-reading-summary" aria-label="Review Graph 摘要">
      {draftCompareProjection ? <DraftCompareSummary projection={draftCompareProjection} /> : null}
      <div className="review-summary-grid">
        <article className="canvas-reading-card is-anchor">
          <span className="canvas-reading-label">审查范围</span>
          <strong className="canvas-reading-title" title={reviewScopeTitle(view)}>{reviewScopeTitle(view)}</strong>
          <span className="canvas-reading-detail">{reviewScopeDetail(view)}</span>
          <div className="canvas-reading-flags" aria-label="Review Graph 状态">
            {view.summary.truncated ? (
              <span className="canvas-reading-flag is-warning">
                首屏收束 {formatCount(view.summary.hiddenNodeCount)} 节点
              </span>
            ) : null}
            {selectedNode ? <span className="canvas-reading-flag is-info">当前节点 {selectedNode.title}</span> : null}
          </div>
        </article>
        <article className="canvas-reading-card review-metric-card">
          <span className="canvas-reading-label">影响统计</span>
          <div className="review-metric-grid">
            {summaryMetrics.map((metric) => (
              <span className="review-metric" key={metric.label}>
                <strong>{formatCount(metric.value)}</strong>
                <span>{metric.label}</span>
              </span>
            ))}
          </div>
        </article>
        <article className="canvas-reading-card review-filter-card">
          <span className="canvas-reading-label">筛选</span>
          <input
            className="compact-input"
            value={query}
            placeholder="搜索"
            onChange={(event) => setQuery(event.target.value)}
          />
          <select
            className="compact-input"
            value={roleFilter}
            onChange={(event) => setRoleFilter(event.target.value as ReviewRole)}
          >
            {ROLE_FILTERS.map((role) => (
              <option key={role.id} value={role.id}>{role.label}</option>
            ))}
          </select>
          <select
            className="compact-input"
            value={typeFilter}
            onChange={(event) => setTypeFilter(event.target.value as NodeType | "ALL")}
          >
            {FILTER_TYPES.map((type) => (
              <option key={type} value={type}>{type === "ALL" ? "全部类型" : type}</option>
            ))}
          </select>
        </article>
      </div>
      <ReviewGraphDetailPanels view={view} />
    </section>
  );

  return (
    <section className="graph-stage-view review-graph-view" data-testid="review-graph-view">
      <GraphFlowSurface
        nodes={visibleNodes}
        edges={visibleEdges}
        flowNodes={flowNodes}
        flowEdges={flowEdges}
        nodeTypes={ARCHITECTURE_GRAPH_NODE_TYPES}
        viewportMode="REVIEW_GRAPH"
        anchorNodeId={view.anchorNodeId ?? null}
        selectedNodeId={selectedNodeId}
        focusNodeRequest={focusNodeRequest}
        selectedGroupNodeIds={selectedGroupNodeIds}
        experiments={experiments}
        editable={false}
        layoutEditable={false}
        header={header}
        emptyState={(
          isLayoutLoading ? (
            <div className="canvas-empty-state">
              <strong>正在整理 Review Graph</strong>
              <p className="muted">变更审查索引已完成，正在计算布局。</p>
            </div>
          ) : (
            <div className="canvas-empty-state">
              <strong>当前没有 Review Graph 结果</strong>
              <p className="muted">请从视图切换加载 Review Graph。</p>
            </div>
          )
        )}
        buildPaneActions={({ visibleNodeCount, hasGroupedSelection, close }) => {
          const actions: GraphContextMenuAction[] = [];
          if (visibleNodeCount > 0) {
            actions.push(
              {
                id: "format-layout",
                label: "重新整理布局",
                onSelect: () => {
                  layoutState.requestRelayout();
                  close();
                },
              },
              {
                id: "open-qa",
                label: hasGroupedSelection ? "问答已框选范围" : "问答当前范围",
                onSelect: () => {
                  onOpenQa();
                  close();
                },
              },
            );
          }
          return actions;
        }}
        buildNodeActions={({ nodeId, close }) =>
          reviewNodeActions({
            nodeId,
            node: nodeIndex.get(nodeId) ?? null,
            canOpenSource: canNavigateToSource(nodeIndex.get(nodeId) ?? { type: "CLASS", location: undefined, signature: undefined }),
            collapsed: collapsedNodeIdSet.has(nodeId),
            onInspectNode,
            onRequestSourceNavigation,
            onRequestBeautification,
            onRequestQa,
            onOpenQa,
            onToggleCollapseNode,
            onFormatLayout: layoutState.requestRelayout,
            onClose: close,
          })
        }
        buildEdgeActions={({ edgeId, close }) =>
          buildSharedEdgeActions({
            analysisDisplayMode: "REVIEW_GRAPH",
            editable: false,
            edgeId,
            onDeleteEdge,
            onClose: close,
          })
        }
        onSelectNode={onSelectNode}
        onSelectionGroupChange={onSelectionGroupChange}
        onInspectNode={onInspectNode}
        onCreateEdge={onCreateEdge}
        onMoveNode={onMoveNode}
        onMoveNodes={onMoveNodes}
        shouldFocusAnchorOnLoad={false}
        nodeViewportSize={(node) => ({ width: nodeCardWidth(node), height: 156 })}
      />
    </section>
  );
}
