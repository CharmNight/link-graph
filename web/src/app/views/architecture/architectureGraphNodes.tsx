import { type CSSProperties } from "react";
import {
  Handle,
  MarkerType,
  Position,
  type Edge,
  type Node,
  type NodeProps,
  type NodeTypes,
} from "@xyflow/react";
import { edgeTypeLabel, relationConfidenceLabel } from "../../labels";
import type { NodeMeasuredSize, NodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { architectureGraphNodeCardWidth } from "../../graphNodeSizing";
import { NodeCardBase } from "../../components/graph/nodes/NodeCardBase";
import type { DraftCompareStatus, GraphProjectionIndex, LinkGraphEdge, LinkGraphNode } from "../../types";
import { canEditNodeLayout } from "../../layoutEditability";
import { reactFlowNodeInternalsSignature } from "../../reactflow/nodeInternalsSignature";
import type { RoutedEdgeData } from "../../reactflow/RoutedEdge";
import { useStableNodeInternalsUpdate } from "../../reactflow/useStableNodeInternalsUpdate";
import { resolveGraphNodeHighlightClassName } from "../graphNodeHighlights";
import {
  draftCompareEdgeClassName,
  draftCompareEdgeStyle,
  draftCompareMarkerColor,
} from "../draftComparePresentation";

interface ArchitectureNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  targetNode?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}

interface BuildArchitectureNodesOptions {
  nodes: LinkGraphNode[];
  selectedNodeId: string | null;
  targetNodeId?: string | null;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIds?: string[];
  draftCompareNodeStatuses?: Record<string, DraftCompareStatus>;
  focusNodeIds?: ReadonlySet<string> | null;
  projectionIndex?: GraphProjectionIndex | null;
  nodeSizeRegistry: NodeSizeRegistry;
}

interface BuildArchitectureEdgesOptions {
  edges: LinkGraphEdge[];
  labelVisibility?: RoutedEdgeData["labelVisibility"];
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
  focusEdgeIds?: ReadonlySet<string> | null;
}

interface ArchitectureEdgeGroup {
  id: string;
  source: string;
  target: string;
  edges: LinkGraphEdge[];
  label: string;
  representative: LinkGraphEdge;
}

type ArchitectureFlowNode = Node<ArchitectureNodeData, "architectureGraphNode">;
type ArchitectureFlowNodeProps = NodeProps<ArchitectureFlowNode>;

const HANDLE_STYLE: CSSProperties = {
  width: 16,
  height: 16,
  opacity: 0,
  background: "rgba(41, 83, 107, 0.6)",
  border: "2px solid rgba(255, 255, 255, 0.9)",
  borderRadius: "50%",
};

function handleStyle(isConnectable: boolean): CSSProperties {
  return {
    ...HANDLE_STYLE,
    opacity: isConnectable ? 0.26 : 0,
    pointerEvents: isConnectable ? "all" : "none",
  };
}

export function ArchitectureNodeCard({
  node,
  selected,
  explanationFocused = false,
  draftChanged = false,
  targetNode = false,
  draftCompareStatus,
  onMeasure,
}: {
  node: LinkGraphNode;
  selected: boolean;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  targetNode?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}) {
  const layerKind = node.metadata?.["indexed.layerKind"];
  const nodeRole = node.metadata?.["indexed.nodeRole"];
  const presentationRole = node.metadata?.["presentation.role"];
  const nodeKind = node.metadata?.["architecture.node.kind"] ?? node.type;
  const boundaryKind = node.metadata?.["architecture.boundary.kind"];
  const qualifiedName = node.metadata?.["architecture.qualifiedName"] ?? node.signature ?? null;
  const memberClassCount = metadataNumber(node, "indexed.memberClassCount");
  const memberResourceCount = metadataNumber(node, "indexed.memberResourceCount");
  const collapsedCount = metadataNumber(node, "indexed.collapsedCount");
  const detailTitle = qualifiedName ?? node.location ?? node.doc ?? node.id;
  const metricText = compactMetricText(memberClassCount, memberResourceCount, collapsedCount);
  const roleLabel = compactPresentationRoleLabel(presentationRole) ?? compactRoleLabel(nodeRole);
  const inferredBoundary = node.metadata?.["architecture.inferred"] === "true" || boundaryKind === "PROJECT_SERVICE_BOUNDARY";
  const titleText = readableNodeTitle(node);
  const locationText = architectureNodeLocation(node, titleText);

  return (
    <NodeCardBase
      node={node}
      selected={selected}
      explanationFocused={explanationFocused}
      draftChanged={draftChanged}
      onMeasure={onMeasure}
      measureDeps={[node, onMeasure]}
      variantClassName={[
        "resource-node-card",
        `architecture-node-card--${architectureRoleClass(presentationRole)}`,
        targetNode ? "is-target-node" : "",
      ]}
    >
      <div className="flow-node-head architecture-node-head">
        <span className={`architecture-node-glyph ${architectureGlyphClass(nodeKind, boundaryKind, layerKind)}`} aria-hidden="true" />
        <div className="flow-node-tags architecture-node-tags">
          {targetNode ? <span className="badge hierarchy-badge is-target-node-badge">目标</span> : null}
          <span className="badge hierarchy-badge">{compactArchitectureKindLabel(nodeKind, boundaryKind, layerKind)}</span>
          {inferredBoundary ? <span className="badge hierarchy-badge">推断</span> : null}
          {roleLabel ? <span className="badge hierarchy-badge">{roleLabel}</span> : null}
          {draftCompareStatus ? <span className={`badge draft-compare-${draftCompareStatus.toLowerCase()}`}>{draftCompareStatus}</span> : null}
        </div>
      </div>
      <strong className="flow-node-owner" title={titleText}>{titleText}</strong>
      {locationText ? (
        <span className="flow-node-meta" title={locationText}>{locationText}</span>
      ) : null}
      {node.metadata?.["architecture.displaySubtitle"] ? (
        <span className="flow-node-meta" title={node.metadata["architecture.displaySubtitle"]}>
          {node.metadata["architecture.displaySubtitle"]}
        </span>
      ) : null}
      {metricText ? <span className="flow-node-meta" title={detailTitle}>{metricText}</span> : null}
    </NodeCardBase>
  );
}

function metadataNumber(node: LinkGraphNode, key: string): number | null {
  const value = node.metadata?.[key];
  if (value == null) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function architectureGlyphClass(
  nodeKind: string,
  boundaryKind?: string,
  layerKind?: string,
): string {
  const normalized = compactArchitectureKindLabel(nodeKind, boundaryKind, layerKind);
  switch (normalized) {
    case "模块":
      return "is-module";
    case "层":
      return "is-layer";
    case "服务":
    case "服务边界":
      return "is-service";
    case "组件":
      return "is-component";
    case "资源":
      return "is-resource";
    case "外部":
    case "JDK":
      return "is-external";
    default:
      return "is-package";
  }
}

function architectureKindLabel(
  value: string,
  boundaryKind?: string,
  layerKind?: string,
): string {
  if (boundaryKind === "EXTERNAL_LIBRARY_GROUP" || value === "LIBRARY") {
    return "外部依赖分组";
  }
  if (boundaryKind === "JDK_GROUP" || value === "JDK") {
    return "JDK 分组";
  }
  if (boundaryKind === "PROJECT_SERVICE_BOUNDARY") {
    return "服务边界";
  }
  if (boundaryKind === "PROJECT_COMPONENT" || value === "COMPONENT") {
    return "组件分组";
  }
  if (layerKind === "EXTERNAL_LIBRARY") {
    return "外部依赖分组";
  }
  if (layerKind === "JDK") {
    return "JDK 分组";
  }
  switch (value) {
    case "MODULE":
      return "模块";
    case "PACKAGE":
      return "包分组";
    case "COMPONENT":
      return "组件分组";
    case "CLASS":
      return "类";
    case "INTERFACE":
      return "接口";
    case "ENUM":
      return "枚举";
    case "ANNOTATION":
      return "注解";
    case "RECORD":
      return "Record";
    case "OBJECT":
      return "Object";
    case "SERVICE":
      return "服务边界";
    case "RESOURCE":
      return "资源";
    case "LAYER":
      return "架构层";
    case "JDK":
      return "JDK 分组";
    case "LIBRARY":
      return "外部依赖分组";
    default:
      return value;
  }
}

function nodeRoleLabel(value: string): string {
  switch (value) {
    case "ENTRY":
      return "入口";
    case "SERVICE":
      return "业务服务";
    case "DATA":
      return "数据访问";
    case "CONFIG":
      return "配置";
    case "API":
      return "接口入口";
    case "TEST":
      return "测试";
    case "RESOURCE":
      return "资源";
    case "EXTERNAL":
      return "外部依赖";
    case "UNKNOWN":
      return "未知";
    default:
      return value;
  }
}

function compactRoleLabel(value: string | undefined): string | null {
  if (!value || value === "UNKNOWN" || value === "EXTERNAL") {
    return null;
  }
  return nodeRoleLabel(value);
}

function compactPresentationRoleLabel(value: string | undefined): string | null {
  switch (value) {
    case "ANCHOR":
      return "目标";
    case "ENTRY":
      return "入口";
    case "APPLICATION":
      return "应用";
    case "DOMAIN":
      return "领域";
    case "DATA":
      return "数据";
    case "RESOURCE":
      return "资源";
    case "EXTERNAL":
      return "外部";
    default:
      return null;
  }
}

function architectureRoleClass(value: string | undefined): string {
  switch (value) {
    case "ANCHOR":
      return "anchor";
    case "ENTRY":
      return "entry";
    case "APPLICATION":
      return "application";
    case "DOMAIN":
      return "domain";
    case "DATA":
      return "data";
    case "RESOURCE":
      return "resource";
    case "EXTERNAL":
      return "external";
    default:
      return "application";
  }
}

function compactArchitectureKindLabel(
  value: string,
  boundaryKind?: string,
  layerKind?: string,
): string {
  const label = architectureKindLabel(value, boundaryKind, layerKind);
  switch (label) {
    case "外部依赖分组":
      return "外部";
    case "JDK 分组":
      return "JDK";
    case "服务边界":
      return "服务边界";
    case "组件分组":
      return "组件";
    case "包分组":
      return "包";
    case "架构层":
      return "层";
    default:
      return label;
  }
}

function readableNodeTitle(node: LinkGraphNode): string {
  const nodeKind = node.metadata?.["architecture.node.kind"] ?? node.type;
  const qualifiedName = node.metadata?.["architecture.qualifiedName"]?.trim();
  if (nodeKind === "LAYER") {
    const layerTitle = qualifiedName || node.title;
    return layerTitle.endsWith("层") ? layerTitle : `${layerTitle} 层`;
  }
  if (qualifiedName) {
    return qualifiedName;
  }
  const signature = node.signature?.trim();
  if (signature) {
    return signature;
  }
  return node.title;
}

function architectureNodeLocation(node: LinkGraphNode, titleText: string): string | null {
  if (node.metadata?.["architecture.displaySubtitle"]) {
    return null;
  }
  const location = node.metadata?.["architecture.package"]
    ?? node.metadata?.["architecture.qualifiedName"]
    ?? node.location
    ?? node.signature
    ?? null;
  return location === titleText ? null : location;
}

function compactMetricText(
  memberClassCount: number | null,
  memberResourceCount: number | null,
  collapsedCount: number | null,
): string | null {
  const parts: string[] = [];
  if (memberClassCount != null && memberClassCount > 0) {
    parts.push(`${memberClassCount} 类型`);
  }
  if (memberResourceCount != null && memberResourceCount > 0) {
    parts.push(`${memberResourceCount} 资源`);
  }
  if (collapsedCount != null && collapsedCount > 0) {
    parts.push(`+${collapsedCount}`);
  }
  return parts.length > 0 ? parts.join(" · ") : null;
}

function ArchitectureReactNode({ id, data, selected, isConnectable }: ArchitectureFlowNodeProps) {
  const style = handleStyle(isConnectable);
  const nodeInternalsSignature = [
    reactFlowNodeInternalsSignature(data.node),
    String(isConnectable),
  ].join("\u0001");
  useStableNodeInternalsUpdate(id, nodeInternalsSignature);

  return (
    <div className={["resource-relation-react-node", isConnectable ? "is-connectable" : ""].join(" ").trim()}>
      <Handle id="target-left" type="target" position={Position.Left} style={style} />
      <Handle id="source-right" type="source" position={Position.Right} style={style} />
      <ArchitectureNodeCard
        node={data.node}
        selected={selected}
        explanationFocused={data.explanationFocused}
        draftChanged={data.draftChanged}
        targetNode={data.targetNode}
        draftCompareStatus={data.draftCompareStatus}
        onMeasure={data.onMeasure}
      />
    </div>
  );
}

export const ARCHITECTURE_GRAPH_NODE_TYPES: NodeTypes = {
  architectureGraphNode: ArchitectureReactNode,
};

function architectureNodeStyle(node: LinkGraphNode) {
  const presentationRole = node.metadata?.["presentation.role"];
  const roleColors: Record<string, { border: string; background: string; shadow: string }> = {
    ANCHOR: {
      border: "rgba(37, 99, 235, 0.34)",
      background: "linear-gradient(135deg, color-mix(in srgb, var(--accent) 18%, transparent), var(--panel))",
      shadow: "rgba(37, 99, 235, 0.13)",
    },
    ENTRY: {
      border: "rgba(42, 105, 122, 0.28)",
      background: "linear-gradient(135deg, color-mix(in srgb, var(--accent-strong) 14%, transparent), var(--panel))",
      shadow: "rgba(42, 105, 122, 0.09)",
    },
    APPLICATION: {
      border: "rgba(62, 86, 133, 0.24)",
      background: "linear-gradient(135deg, color-mix(in srgb, var(--accent) 10%, transparent), var(--panel))",
      shadow: "rgba(62, 86, 133, 0.08)",
    },
    DOMAIN: {
      border: "rgba(82, 118, 71, 0.28)",
      background: "linear-gradient(135deg, color-mix(in srgb, var(--success) 12%, transparent), var(--panel))",
      shadow: "rgba(82, 118, 71, 0.08)",
    },
    DATA: {
      border: "rgba(130, 93, 45, 0.28)",
      background: "linear-gradient(135deg, color-mix(in srgb, var(--warning) 14%, transparent), var(--panel))",
      shadow: "rgba(130, 93, 45, 0.08)",
    },
    RESOURCE: {
      border: "rgba(105, 92, 134, 0.26)",
      background: "linear-gradient(135deg, color-mix(in srgb, var(--accent) 8%, transparent), var(--panel))",
      shadow: "rgba(105, 92, 134, 0.08)",
    },
    EXTERNAL: {
      border: "rgba(96, 106, 116, 0.26)",
      background: "linear-gradient(135deg, color-mix(in srgb, var(--muted) 8%, transparent), var(--panel))",
      shadow: "rgba(96, 106, 116, 0.08)",
    },
  };
  const colors = presentationRole ? roleColors[presentationRole] : null;
  return {
    width: architectureGraphNodeCardWidth(),
    borderRadius: 8,
    border: `1px solid ${colors?.border ?? "rgba(41, 83, 107, 0.18)"}`,
    background: colors?.background ?? (node.type === "LAYER"
      ? "linear-gradient(135deg, color-mix(in srgb, var(--success) 10%, transparent), var(--panel))"
      : "var(--panel)"),
    boxShadow: `0 4px 14px ${colors?.shadow ?? "rgba(28, 49, 64, 0.06)"}`,
    padding: 0,
  };
}

function architectureEdgeStyle(edge: LinkGraphEdge) {
  if (
    edge.type === "REFLECTS_TO"
    || edge.metadata?.["jvm.relation.confidence"] === "AMBIGUOUS"
    || edge.metadata?.["jvm.relation.confidence"] === "RUNTIME_REQUIRED"
  ) {
    return { stroke: "var(--edge-warning)", strokeWidth: 1.5, strokeDasharray: "7 5", opacity: 0.86 };
  }
  return { stroke: "var(--edge-info)", strokeWidth: 1.7, opacity: 0.9 };
}

function edgePriority(edge: LinkGraphEdge): number {
  switch (edge.type) {
    case "REFLECTS_TO":
    case "SPI_RESOLVES_TO":
    case "USES_PROXY":
      return 0;
    case "ROUTES_TO":
    case "MAPS_TO_SQL":
    case "PUBLISHES_TO":
    case "CONSUMES_FROM":
    case "BINDS_CONFIG":
      return 1;
    case "IMPLEMENTS":
    case "EXTENDS":
      return 2;
    default:
      return 3;
  }
}

function architectureEdgeLabel(edge: LinkGraphEdge): string {
  const displayRelation = edge.metadata?.["architecture.displayRelation"]?.trim();
  if (displayRelation) {
    const sourceCount = edge.metadata?.["indexed.sourceCount"]?.trim();
    return sourceCount && sourceCount !== "1" ? `${displayRelation} ${sourceCount}` : displayRelation;
  }
  const base = edge.label?.trim() || edgeTypeLabel(edge.type);
  const confidence = relationConfidenceLabel(edge.metadata?.["jvm.relation.confidence"]);
  return confidence && confidence !== "静态确认" ? `${base} · ${confidence}` : base;
}

function architectureEdgeGroupId(source: string, target: string): string {
  return `architecture-edge-group:${source}->${target}`;
}

function architectureEdgeGroupKey(edge: LinkGraphEdge): string {
  return `${edge.source}\u0000${edge.target}`;
}

function mergeDraftCompareStatus(statuses: Array<DraftCompareStatus | undefined>): DraftCompareStatus | undefined {
  if (statuses.includes("REMOVED")) {
    return "REMOVED";
  }
  if (statuses.includes("ADDED")) {
    return "ADDED";
  }
  if (statuses.includes("MODIFIED")) {
    return "MODIFIED";
  }
  return undefined;
}

function uniqueSortedLabels(edges: LinkGraphEdge[]): string[] {
  return Array.from(new Set(edges.map(architectureEdgeLabel))).sort((left, right) => left.localeCompare(right));
}

function architectureEdgeGroupLabel(edges: LinkGraphEdge[]): string {
  const labels = uniqueSortedLabels(edges);
  if (labels.length <= 2) {
    return labels.join(" / ");
  }
  return `${labels.slice(0, 2).join(" / ")} +${labels.length - 2}`;
}

function architectureEdgeGroupTitle(group: ArchitectureEdgeGroup): string {
  if (group.edges.length <= 1) {
    return architectureEdgeLabel(group.representative);
  }
  return uniqueSortedLabels(group.edges).join(" / ");
}

function groupArchitectureEdges(edges: LinkGraphEdge[]): ArchitectureEdgeGroup[] {
  const groupsByKey = new Map<string, LinkGraphEdge[]>();
  edges.forEach((edge) => {
    const key = architectureEdgeGroupKey(edge);
    const groupEdges = groupsByKey.get(key) ?? [];
    groupEdges.push(edge);
    groupsByKey.set(key, groupEdges);
  });
  return Array.from(groupsByKey.values()).map((groupEdges) => {
    const representative = [...groupEdges].sort((left, right) =>
      edgePriority(left) - edgePriority(right)
      || architectureEdgeLabel(left).localeCompare(architectureEdgeLabel(right))
      || left.id.localeCompare(right.id),
    )[0]!;
    return {
      id: groupEdges.length === 1
        ? representative.id
        : architectureEdgeGroupId(representative.source, representative.target),
      source: representative.source,
      target: representative.target,
      edges: groupEdges,
      label: architectureEdgeGroupLabel(groupEdges),
      representative,
    };
  });
}

export function buildArchitectureGraphNodes({
  nodes,
  selectedNodeId,
  targetNodeId = null,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareNodeStatuses = {},
  focusNodeIds = null,
  projectionIndex = null,
  nodeSizeRegistry,
}: BuildArchitectureNodesOptions): ArchitectureFlowNode[] {
  const draftChangedNodeIdSet = new Set(draftChangedNodeIds);
  const hasFocus = focusNodeIds != null && focusNodeIds.size > 0;
  return nodes.map((node) => ({
    id: node.id,
    type: "architectureGraphNode",
    className: resolveGraphNodeHighlightClassName({
      baseClassName: [
        targetNodeId === node.id ? "is-target-node" : "",
        hasFocus && focusNodeIds?.has(node.id) ? "is-focus-neighbor" : "",
        hasFocus && !focusNodeIds?.has(node.id) ? "is-dimmed" : "",
      ].filter(Boolean).join(" "),
      nodeId: node.id,
      explanationFocusNodeId,
      draftChangedNodeIdSet,
      draftCompareStatus: draftCompareNodeStatuses[node.id],
    }) || undefined,
    selected: selectedNodeId === node.id,
    draggable: canEditNodeLayout(node, "ARCHITECTURE_GRAPH", projectionIndex),
    position: node.position ?? { x: 80, y: 88 },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: {
      node,
      explanationFocused: explanationFocusNodeId === node.id,
      draftChanged: draftChangedNodeIdSet.has(node.id),
      targetNode: targetNodeId === node.id,
      draftCompareStatus: draftCompareNodeStatuses[node.id],
      onMeasure: nodeSizeRegistry.reporter(node.id),
    },
    style: architectureNodeStyle(node),
  }));
}

export function buildArchitectureGraphEdges({
  edges,
  labelVisibility = "selected",
  draftCompareEdgeStatuses = {},
  focusEdgeIds = null,
}: BuildArchitectureEdgesOptions): Array<Edge<RoutedEdgeData, "routedEdge">> {
  const hasFocus = focusEdgeIds != null && focusEdgeIds.size > 0;
  return groupArchitectureEdges(edges).map((group) => {
    const edge = group.representative;
    const draftCompareStatus = mergeDraftCompareStatus(group.edges.map((groupEdge) => draftCompareEdgeStatuses[groupEdge.id]));
    const focused = group.edges.some((groupEdge) => focusEdgeIds?.has(groupEdge.id));
    return {
      id: group.id,
      source: group.source,
      target: group.target,
      label: group.label,
      ariaLabel: architectureEdgeGroupTitle(group),
      type: "routedEdge",
      className: draftCompareEdgeClassName(
        [
          "edge-architecture",
          group.edges.length > 1 ? "is-aggregated" : "",
          hasFocus && focused ? "is-focus-edge" : "",
          hasFocus && !focused ? "is-dimmed" : "",
        ].filter(Boolean).join(" "),
        draftCompareStatus,
      ),
      data: {
        route: edge.route,
        labelVisibility,
        focusedEdge: hasFocus && focused,
      },
      style: draftCompareEdgeStyle(architectureEdgeStyle(edge), draftCompareStatus),
      markerEnd: {
        type: MarkerType.ArrowClosed,
        width: 20,
        height: 20,
        color: draftCompareMarkerColor("#29536b", draftCompareStatus),
      },
    };
  });
}
