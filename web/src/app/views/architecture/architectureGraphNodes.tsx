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

/**
 * React Flow 中架构节点携带的数据载荷：包含原始图节点、各种聚焦/对比状态标记，
 * 以及上报节点实际渲染尺寸的回调（供布局算法在下一轮度量时使用）。
 */
interface ArchitectureNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  targetNode?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}

/** 构建架构图节点数组所需的输入：节点列表、选中/聚焦/对比状态、尺寸上报器。 */
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

/** 构建架构图边数组所需的输入：边列表、标签可见性策略、对比/聚焦状态。 */
interface BuildArchitectureEdgesOptions {
  edges: LinkGraphEdge[];
  labelVisibility?: RoutedEdgeData["labelVisibility"];
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
  focusEdgeIds?: ReadonlySet<string> | null;
}

/** 一组同源同目标边的聚合：折叠后用一条线表示，附带聚合标签和代表边。 */
interface ArchitectureEdgeGroup {
  id: string;
  source: string;
  target: string;
  edges: LinkGraphEdge[];
  label: string;
  representative: LinkGraphEdge;
}

/** React Flow 中架构节点类型；type 字段固定为 "architectureGraphNode" 以匹配 NodeTypes 注册表。 */
type ArchitectureFlowNode = Node<ArchitectureNodeData, "architectureGraphNode">;
/** React Flow 注入给架构节点组件的 props。 */
type ArchitectureFlowNodeProps = NodeProps<ArchitectureFlowNode>;

/** React Flow 连接点的视觉样式（默认透明，悬停/可连接时才显示）。 */
const HANDLE_STYLE: CSSProperties = {
  width: 16,
  height: 16,
  opacity: 0,
  background: "rgba(41, 83, 107, 0.6)",
  border: "2px solid rgba(255, 255, 255, 0.9)",
  borderRadius: "50%",
};

/**
 * 根据是否允许连线返回不同的连接点样式：
 * 不可连线时完全隐藏；可连线时显示淡淡的圆点，提示用户可拖拽建立连接。
 */
function handleStyle(isConnectable: boolean): CSSProperties {
  return {
    ...HANDLE_STYLE,
    opacity: isConnectable ? 0.26 : 0,
    pointerEvents: isConnectable ? "all" : "none",
  };
}

/**
 * 架构图节点卡片视图：渲染节点的图标、类型徽章、标题、位置副标题与指标统计。
 * 该组件只是 DOM 内容（不含连接点），由 React Flow 节点组件进一步包裹。
 */
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

/** 从节点 metadata 中读取数值字段；缺失或非法时返回 null，便于上层用 null 判断渲染。 */
function metadataNumber(node: LinkGraphNode, key: string): number | null {
  const value = node.metadata?.[key];
  if (value == null) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** 把节点类型/边界/层信息映射为 CSS 图标类名，用于显示对应的几何符号（方框、菱形等）。 */
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

/**
 * 把节点 kind/boundary/layer 翻译成中文显示标签（含完整描述如"服务边界"），
 * 用于 tooltip 或详情面板；compactArchitectureKindLabel 在此基础上再做卡片徽章用的简版。
 */
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

/** 把 indexed.nodeRole 枚举值翻译为中文（入口/业务服务/数据访问 等）。 */
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

/** 简化角色标签：把 UNKNOWN/EXTERNAL 等无意义或冗余角色折叠为 null，不显示在卡片上。 */
function compactRoleLabel(value: string | undefined): string | null {
  if (!value || value === "UNKNOWN" || value === "EXTERNAL") {
    return null;
  }
  return nodeRoleLabel(value);
}

/** 把展示层 presentation.role 枚举（ANCHOR/ENTRY/APPLICATION 等）映射为简短中文徽章。 */
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

/** 把展示层 role 映射为 CSS class 后缀（如 anchor/entry/domain），用于配色区分。 */
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

/** 把完整 kind 标签简化为卡片徽章用的 1-3 字版本（"外部依赖分组" → "外部"）。 */
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

/**
 * 选出最易读的节点标题：优先用 architecture 限定的名称，缺失时回退到 signature、title；
 * "层"类节点末尾自动补"层"字，让标题更直观。
 */
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

/**
 * 提取卡片副标题位置信息：从 package/qualifiedName/location/signature 中选一条，
 * 若与主标题重复则返回 null（避免同字符串重复占行）。
 */
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

/**
 * 把"成员类数/成员资源数/折叠数"拼接成简短的指标字符串（如 "12 类型 · 3 资源 · +5"），
 * 让卡片一眼能看出聚合节点的规模；全部为零时返回 null。
 */
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

/**
 * 架构图节点在 React Flow 中的注册组件：在卡片外加左右两个连接点，
 * 同时把"节点内部签名 + 是否可连接"作为签名上报给稳定内部状态更新 hook，
 * 保证 React Flow 在节点更新时不会破坏边的连接关系。
 */
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

/** React Flow 节点类型注册表：把 "architectureGraphNode" 字符串映射到具体组件。 */
export const ARCHITECTURE_GRAPH_NODE_TYPES: NodeTypes = {
  architectureGraphNode: ArchitectureReactNode,
};

/**
 * 根据节点展示层 role 生成卡片样式（边框/背景/阴影配色）：
 * 不同 role 用不同色相区分；层节点用绿色调，无 role 的节点用中性面板色。
 */
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

/**
 * 决定边的视觉样式：反射/SPI/弱推断的边用虚线警示色，普通边用稳定的实线 info 色。
 */
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

/** 边优先级：SPI/反射/路由等"行为性"关系优先，普通调用/继承靠后，用作聚合时的代表边选择。 */
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

/**
 * 生成边的显示标签：优先使用架构侧预计算好的 displayRelation，
 * 否则用基础 label + 关系置信度（如"调用 · 静态确认"）。
 * 若有来源计数（同方向多条关系），追加计数以提示用户这是聚合边。
 */
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

/** 生成聚合边的稳定 id：基于 source+target 唯一标识一组同方向边。 */
function architectureEdgeGroupId(source: string, target: string): string {
  return `architecture-edge-group:${source}->${target}`;
}

/** 计算边的分组键：同 source+target 归一组，用于折叠显示。 */
function architectureEdgeGroupKey(edge: LinkGraphEdge): string {
  return `${edge.source}\u0000${edge.target}`;
}

/**
 * 把分组内多条边的对比状态合并成一个：优先级 REMOVED > ADDED > MODIFIED。
 * 用于在聚合边上显示"最显著"的变化类型。
 */
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

/** 收集一组边的去重标签并按字典序排序，方便后续拼成稳定可读的聚合标签。 */
function uniqueSortedLabels(edges: LinkGraphEdge[]): string[] {
  return Array.from(new Set(edges.map(architectureEdgeLabel))).sort((left, right) => left.localeCompare(right));
}

/** 把一组边的标签聚合成单行显示文本（≤2 个直接拼接，更多用 "X / Y +N" 形式提示）。 */
function architectureEdgeGroupLabel(edges: LinkGraphEdge[]): string {
  const labels = uniqueSortedLabels(edges);
  if (labels.length <= 2) {
    return labels.join(" / ");
  }
  return `${labels.slice(0, 2).join(" / ")} +${labels.length - 2}`;
}

/** 生成聚合边的 tooltip 文本：单条边直接用标签，多条边把所有标签按序拼接展示完整关系列表。 */
function architectureEdgeGroupTitle(group: ArchitectureEdgeGroup): string {
  if (group.edges.length <= 1) {
    return architectureEdgeLabel(group.representative);
  }
  return uniqueSortedLabels(group.edges).join(" / ");
}

/**
 * 把所有边按 source+target 聚合成组：每组选一条代表边（按优先级 + 字典序挑出），
 * 这样图上的视觉连线数量大幅减少，但保留了原始关系列表用于 tooltip。
 */
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

/**
 * 把后端图节点转换成 React Flow 节点数组：根据选中、目标、聚焦、对比等状态计算 className、
 * 是否可拖拽、卡片样式与载荷数据，最终交给 React Flow 渲染。
 */
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

/**
 * 把后端图边转换成 React Flow 边数组：先按 source+target 聚合，再生成聚合标签、
 * ariaLabel、样式、箭头颜色等。聚合边会带上原始关系集合供 tooltip 显示。
 */
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
