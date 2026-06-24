import { useLayoutEffect, useRef, type CSSProperties, type Ref } from "react";
import {
  Handle,
  MarkerType,
  Position,
  type Edge,
  type EdgeMarker,
  type Node,
  type NodeProps,
  type NodeTypes,
} from "@xyflow/react";
import { nodeTypeLabel, relationConfidenceLabel } from "../../labels";
import type { NodeMeasuredSize, NodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { classDiagramNodeCardWidth } from "../../graphNodeSizing";
import { measureNodeContentBox } from "../../components/graph/nodes/measureNodeContentBox";
import { GraphNodeStateBadges } from "../../components/graph/nodes/GraphNodeStateBadges";
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
import {
  classDiagramCompactRelationLabel,
  classDiagramRelationDetailText,
  classDiagramRelationDetailLabel,
  classDiagramRelationDisplayLabel,
  classDiagramRelationKind,
  classDiagramRelationPresentation,
} from "./classDiagramRelations";

/** React Flow 节点 data：携带原始 LinkGraphNode、是否紧凑模式、解释高亮、草稿对比状态及尺寸测量回调。 */
interface ClassDiagramNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
  compact?: boolean;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}

/** 构造类图节点列表所需的全部外部输入：节点集合、选中态、解释聚焦、草稿状态、尺寸注册表等。 */
interface BuildClassDiagramNodesOptions {
  nodes: LinkGraphNode[];
  selectedNodeId: string | null;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIds?: string[];
  draftCompareNodeStatuses?: Record<string, DraftCompareStatus>;
  projectionIndex?: GraphProjectionIndex | null;
  nodeSizeRegistry: NodeSizeRegistry;
}

/** 构造类图边列表所需的外部输入：边集合以及可选的草稿对比状态映射。 */
interface BuildClassDiagramEdgesOptions {
  edges: LinkGraphEdge[];
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
}

/** React Flow 中类图节点的具体 Node 类型定义。 */
type ClassDiagramFlowNode = Node<ClassDiagramNodeData, "classDiagramNode">;
/** 类图节点对应的 NodeProps 类型，供组件签名使用。 */
type ClassDiagramFlowNodeProps = NodeProps<ClassDiagramFlowNode>;

// 端点 Handle 的基础样式：默认透明，仅在可连接时半透明显示
const HANDLE_STYLE: CSSProperties = {
  width: 14,
  height: 14,
  opacity: 0,
  background: "rgba(52, 180, 255, 0.72)",
  border: "2px solid rgba(248, 252, 255, 0.96)",
  borderRadius: "50%",
  boxShadow: "0 0 0 3px rgba(52, 180, 255, 0.16)",
};
// 同侧端口扇出的分槽数量，用于在一条边上分配多个 slot
const SIDE_FANOUT_SLOT_COUNT = 7;

/** 根据 isConnectable 返回端点的实际样式：不可连接时完全透明且不响应事件。 */
function handleStyle(isConnectable: boolean): CSSProperties {
  return {
    ...HANDLE_STYLE,
    opacity: isConnectable ? 0.52 : 0,
    pointerEvents: isConnectable ? "all" : "none",
  };
}

/** 水平方向（左右）端点的扇出样式：根据 slot 索引计算 top 百分比均匀分布。 */
function sideFanoutHandleStyle(isConnectable: boolean, index: number): CSSProperties {
  return {
    ...handleStyle(isConnectable),
    top: `${((index + 1) / (SIDE_FANOUT_SLOT_COUNT + 1)) * 100}%`,
  };
}

/** 垂直方向（上下）端点的扇出样式：根据 slot 索引计算 left 百分比均匀分布。 */
function verticalFanoutHandleStyle(isConnectable: boolean, index: number): CSSProperties {
  return {
    ...handleStyle(isConnectable),
    left: `${((index + 1) / (SIDE_FANOUT_SLOT_COUNT + 1)) * 100}%`,
  };
}

/**
 * 类图节点卡片：完整 UML 框，含 stereotype、类名、包名、注释、字段与方法分区。
 * 紧凑模式且非 anchor 节点时委托给 CompactClassDiagramNodeCard 渲染精简视图。
 */
function ClassDiagramNodeCard({
  node,
  compact = false,
  selected,
  explanationFocused = false,
  draftChanged = false,
  draftCompareStatus,
  onMeasure,
}: {
  node: LinkGraphNode;
  compact?: boolean;
  selected: boolean;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}) {
  const rootRef = useRef<HTMLDivElement | null>(null);
  const packageName = node.metadata?.["architecture.package"];
  const fields = memberLines(node.metadata?.["uml.field.items"]);
  const methods = memberLines(node.metadata?.["uml.method.items"]);
  const hiddenFieldCount = numericMetadata(node.metadata?.["uml.field.hiddenCount"]);
  const hiddenMethodCount = numericMetadata(node.metadata?.["uml.method.hiddenCount"]);
  const stereotype = umlStereotype(node);
  const comment = umlComment(node);
  const anchor = isAnchorNode(node);
  const visibleFields = compact ? fields.slice(0, 2) : fields;
  const visibleMethods = compact ? methods.slice(0, 3) : methods;
  const compactHiddenFieldCount = compact ? hiddenFieldCount + Math.max(0, fields.length - visibleFields.length) : hiddenFieldCount;
  const compactHiddenMethodCount = compact ? hiddenMethodCount + Math.max(0, methods.length - visibleMethods.length) : hiddenMethodCount;

  useLayoutEffect(() => {
    const size = measureNodeContentBox(rootRef.current);
    if (size) {
      onMeasure?.(size);
    }
  }, [node, onMeasure]);

  if (compact && !anchor) {
    return (
      <CompactClassDiagramNodeCard
        rootRef={rootRef}
        node={node}
        selected={selected}
        explanationFocused={explanationFocused}
        draftChanged={draftChanged}
        draftCompareStatus={draftCompareStatus}
      />
    );
  }

  return (
    <div
      ref={rootRef}
      className={["uml-class-card", umlCardKindClassName(node), anchor ? "is-anchor" : "", selected ? "is-selected" : ""].join(" ").trim()}
      data-node-id={node.id}
    >
      <GraphNodeStateBadges selected={selected} explanationFocused={explanationFocused} draftChanged={draftChanged} />
      <div className="uml-class-header">
        {anchor ? <span className="uml-anchor-badge">当前类</span> : null}
        <span className="uml-class-stereotype">{stereotype}</span>
        <strong className="uml-class-name" title={node.title}>{node.title}</strong>
        <span className="uml-class-package" title={node.signature ?? node.location ?? node.id}>
          {packageName ?? node.signature ?? node.id}
        </span>
      </div>
      {comment && !compact ? <UmlCommentCompartment text={comment} /> : null}
      <UmlMemberCompartment
        kind="field"
        items={visibleFields}
        hiddenCount={compactHiddenFieldCount}
      />
      <UmlMemberCompartment
        kind="method"
        items={visibleMethods}
        hiddenCount={compactHiddenMethodCount}
      />
      {draftCompareStatus ? <span className={`uml-draft-badge draft-compare-${draftCompareStatus.toLowerCase()}`}>{draftCompareStatus}</span> : null}
    </div>
  );
}

/**
 * 紧凑型节点卡片：用单行标题 + 可选 meta/detail 展示节点，省略 UML 字段方法分区，
 * 用于非 anchor 的协作节点以节省画布空间。
 */
function CompactClassDiagramNodeCard({
  rootRef,
  node,
  selected,
  explanationFocused,
  draftChanged,
  draftCompareStatus,
}: {
  rootRef: Ref<HTMLDivElement>;
  node: LinkGraphNode;
  selected: boolean;
  explanationFocused: boolean;
  draftChanged: boolean;
  draftCompareStatus?: DraftCompareStatus;
}) {
  const roleLabel = compactRoleLabel(node);
  const meta = compactNodeMeta(node);
  const detail = compactNodeDetail(node);
  const detailTitle = compactNodeDetailTitle(node);
  return (
    <div
      ref={rootRef}
      className={["uml-class-card", "is-compact", umlCardKindClassName(node), selected ? "is-selected" : ""].join(" ").trim()}
      data-node-id={node.id}
    >
      <GraphNodeStateBadges selected={selected} explanationFocused={explanationFocused} draftChanged={draftChanged} />
      <div className="uml-compact-header">
        <span className="uml-compact-kind">{roleLabel}</span>
        <strong className="uml-compact-title" title={node.title}>{node.title}</strong>
        {meta ? <span className="uml-compact-meta" title={meta}>{meta}</span> : null}
      </div>
      {detail ? <span className="uml-compact-detail" title={detailTitle ?? detail}>{detail}</span> : null}
      {draftCompareStatus ? <span className={`uml-draft-badge draft-compare-${draftCompareStatus.toLowerCase()}`}>{draftCompareStatus}</span> : null}
    </div>
  );
}

/** 把以换行符分隔的成员文本拆分为非空、去空白的多行数组。 */
function memberLines(value?: string | null): string[] {
  return (value ?? "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}

/** 安全地把 metadata 中的字符串数值化为非负数字；非法值统一视为 0。 */
function numericMetadata(value?: string | null): number {
  const parsed = Number(value ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0;
}

/** 根据节点的 role/lane/direction 元数据返回中文角色标签，用于紧凑卡片的类型角标。 */
function compactRoleLabel(node: LinkGraphNode): string {
  const role = node.metadata?.["presentation.role"];
  const lane = node.metadata?.["presentation.laneId"];
  if (role === "CALLER" || lane === "caller" || node.metadata?.["layout.direction"] === "INCOMING") {
    return "调用方";
  }
  if (role === "INTERFACE" || lane === "abstraction" || node.metadata?.["layout.direction"] === "PARENT") {
    return "抽象与接口";
  }
  if (role === "OUTPUT" || lane === "output" || node.metadata?.["layout.direction"] === "DATA") {
    return "输出类型";
  }
  if (role === "ANCHOR" || lane === "anchor" || node.metadata?.["layout.direction"] === "ANCHOR") {
    return "当前类";
  }
  return "协作对象";
}

/** 取紧凑卡片 meta 行的文本：优先包名，其次签名，再次位置信息。 */
function compactNodeMeta(node: LinkGraphNode): string | null {
  return node.metadata?.["architecture.package"]
    ?? node.signature
    ?? node.location
    ?? null;
}

/** 取紧凑卡片 detail 行的文本：优先关系原因，其次首个方法/字段，再次文档摘要。 */
function compactNodeDetail(node: LinkGraphNode): string | null {
  const relationReason = node.metadata?.["classDiagram.node.reason"]?.trim();
  if (relationReason) {
    return classDiagramCompactRelationLabel(relationReason);
  }
  const method = memberLines(node.metadata?.["uml.method.items"])[0];
  if (method) {
    return method;
  }
  const field = memberLines(node.metadata?.["uml.field.items"])[0];
  if (field) {
    return field;
  }
  return node.doc?.trim() || null;
}

/** 取 detail 行的 title 文本（hover 时展示），关系原因场景下返回更详细的描述。 */
function compactNodeDetailTitle(node: LinkGraphNode): string | null {
  const relationReason = node.metadata?.["classDiagram.node.reason"]?.trim();
  if (relationReason) {
    return classDiagramRelationDetailText(relationReason);
  }
  return compactNodeDetail(node);
}

/** 判断是否为抽象类（CLASS 类型且 metadata 标记 abstract=true）。 */
function isAbstractClass(node: LinkGraphNode): boolean {
  return node.type === "CLASS" && node.metadata?.["jvm.class.abstract"] === "true";
}

/** 判断是否为"当前类"（anchor）节点：优先看 presentation.role，否则回退到 layout.direction。 */
function isAnchorNode(node: LinkGraphNode): boolean {
  if (node.metadata?.["presentation.role"]) {
    return node.metadata["presentation.role"] === "ANCHOR";
  }
  return node.metadata?.["presentation.role"] === "ANCHOR"
    || node.metadata?.["layout.direction"] === "ANCHOR";
}

/** 判断是否为数据类型节点（OUTPUT/DATA 角色，或 ENUM/RECORD/OBJECT 类型）。 */
function isDataTypeNode(node: LinkGraphNode): boolean {
  return node.metadata?.["presentation.role"] === "OUTPUT"
    || node.metadata?.["layout.direction"] === "DATA"
    || node.type === "ENUM"
    || node.type === "RECORD"
    || node.type === "OBJECT"
    || node.metadata?.["jvm.class.kind"] === "ENUM"
    || node.metadata?.["jvm.class.kind"] === "RECORD"
    || node.metadata?.["jvm.class.kind"] === "OBJECT";
}

/** 拼接节点 kind 相关的 className，便于在 CSS 中按类型/抽象/数据节点差异化呈现。 */
function umlCardKindClassName(node: LinkGraphNode): string {
  return [
    `uml-kind-${node.type.toLowerCase()}`,
    isAbstractClass(node) ? "uml-kind-abstract" : "",
    isDataTypeNode(node) ? "uml-kind-data" : "",
  ].filter(Boolean).join(" ");
}

/** 根据节点类型和抽象标记生成 UML stereotype 文本（如 <<interface>>、<<abstract>> 等）。 */
function umlStereotype(node: LinkGraphNode): string {
  if (isAbstractClass(node)) {
    return "<<abstract>>";
  }
  switch (node.type) {
    case "CLASS":
      return "<<class>>";
    case "INTERFACE":
      return "<<interface>>";
    case "ENUM":
      return "<<enum>>";
    case "ANNOTATION":
      return "<<annotation>>";
    case "RECORD":
      return "<<record>>";
    case "OBJECT":
      return "<<object>>";
    default:
      return nodeTypeLabel(node.type);
  }
}

/** 取节点的注释文本：优先 doc，其次 uml.comment 和 jvm.class.docComment；空字符串归一化为 null。 */
function umlComment(node: LinkGraphNode): string | null {
  const comment = node.doc ?? node.metadata?.["uml.comment"] ?? node.metadata?.["jvm.class.docComment"];
  return comment?.trim() || null;
}

/** UML 卡片中的注释分区：单独一块展示文档/注释，title 提供完整内容。 */
function UmlCommentCompartment({ text }: { text: string }) {
  return (
    <div className="uml-comment-compartment" aria-label="注释">
      <span className="uml-comment-text" title={text}>{text}</span>
    </div>
  );
}

/** UML 卡片中的成员（字段/方法）分区：列出可见成员并在末尾以 +N 形式提示被折叠的数量。 */
function UmlMemberCompartment({
  kind,
  items,
  hiddenCount,
}: {
  kind: "field" | "method";
  items: string[];
  hiddenCount: number;
}) {
  const label = kind === "field" ? "字段" : "方法";
  return (
    <div className={`uml-member-compartment uml-member-compartment-${kind}`} aria-label={label}>
      <span className="uml-member-title">{label}</span>
      {items.length === 0 ? (
        <span className="uml-member-line is-empty">无</span>
      ) : items.map((item) => (
        <span key={item} className="uml-member-line" title={item}>{item}</span>
      ))}
      {hiddenCount > 0 ? <span className="uml-member-line is-muted">... +{hiddenCount}</span> : null}
    </div>
  );
}

/**
 * React Flow 中实际渲染的类图节点：在卡片四周布置全部 source/target Handle（含扇出 slot），
 * 内部委托 ClassDiagramNodeCard 渲染具体 UML 内容，同时通过 hook 上报 internals 变更。
 */
function ClassDiagramReactNode({ id, data, selected, isConnectable }: ClassDiagramFlowNodeProps) {
  const style = handleStyle(isConnectable);
  const nodeInternalsSignature = [
    reactFlowNodeInternalsSignature(data.node),
    String(isConnectable),
  ].join("\u0001");
  useStableNodeInternalsUpdate(id, nodeInternalsSignature);

  return (
    <div className={["class-diagram-react-node", isConnectable ? "is-connectable" : ""].join(" ").trim()}>
      <Handle id="source-top" type="source" position={Position.Top} style={style} />
      <Handle id="target-top" type="target" position={Position.Top} style={style} />
      {Array.from({ length: SIDE_FANOUT_SLOT_COUNT }, (_, index) => (
        <Handle
          key={`source-top-${index}`}
          id={`source-top-${index}`}
          type="source"
          position={Position.Top}
          style={verticalFanoutHandleStyle(isConnectable, index)}
        />
      ))}
      <Handle id="target-left" type="target" position={Position.Left} style={style} />
      <Handle id="source-left" type="source" position={Position.Left} style={style} />
      <Handle id="source-right" type="source" position={Position.Right} style={style} />
      <Handle id="target-right" type="target" position={Position.Right} style={style} />
      {Array.from({ length: SIDE_FANOUT_SLOT_COUNT }, (_, index) => (
        <Handle
          key={`source-right-${index}`}
          id={`source-right-${index}`}
          type="source"
          position={Position.Right}
          style={sideFanoutHandleStyle(isConnectable, index)}
        />
      ))}
      {Array.from({ length: SIDE_FANOUT_SLOT_COUNT }, (_, index) => (
        <Handle
          key={`target-right-${index}`}
          id={`target-right-${index}`}
          type="target"
          position={Position.Right}
          style={sideFanoutHandleStyle(isConnectable, index)}
        />
      ))}
      {Array.from({ length: SIDE_FANOUT_SLOT_COUNT }, (_, index) => (
        <Handle
          key={`source-left-${index}`}
          id={`source-left-${index}`}
          type="source"
          position={Position.Left}
          style={sideFanoutHandleStyle(isConnectable, index)}
        />
      ))}
      {Array.from({ length: SIDE_FANOUT_SLOT_COUNT }, (_, index) => (
        <Handle
          key={`target-left-${index}`}
          id={`target-left-${index}`}
          type="target"
          position={Position.Left}
          style={sideFanoutHandleStyle(isConnectable, index)}
        />
      ))}
      <Handle id="source-bottom" type="source" position={Position.Bottom} style={style} />
      {Array.from({ length: SIDE_FANOUT_SLOT_COUNT }, (_, index) => (
        <Handle
          key={`source-bottom-${index}`}
          id={`source-bottom-${index}`}
          type="source"
          position={Position.Bottom}
          style={verticalFanoutHandleStyle(isConnectable, index)}
        />
      ))}
      <Handle id="target-bottom" type="target" position={Position.Bottom} style={style} />
      <ClassDiagramNodeCard
        node={data.node}
        compact={data.compact}
        selected={selected}
        explanationFocused={data.explanationFocused}
        draftChanged={data.draftChanged}
        draftCompareStatus={data.draftCompareStatus}
        onMeasure={data.onMeasure}
      />
    </div>
  );
}

/** React Flow 注册表：声明类图使用的节点类型与对应组件。 */
export const CLASS_DIAGRAM_NODE_TYPES: NodeTypes = {
  classDiagramNode: ClassDiagramReactNode,
};

/** 计算单个节点的内联样式：宽度、圆角、边框、背景渐变与阴影，anchor 节点会得到更醒目的样式。 */
function classNodeStyle(node: LinkGraphNode) {
  const isTypeNode = ["CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD", "OBJECT"].includes(node.type);
  const presentationRole = node.metadata?.["presentation.role"];
  const isAnchor = presentationRole === "ANCHOR" || isAnchorNode(node);
  return {
    width: classDiagramNodeCardWidth(node),
    borderRadius: 7,
    border: isAnchor
      ? "3px solid var(--accent)"
      : isTypeNode ? "1.5px solid var(--uml-line)" : "1px solid var(--uml-line)",
    background: isAnchor
      ? "linear-gradient(135deg, color-mix(in srgb, var(--accent) 16%, transparent), var(--surface))"
      : isTypeNode
        ? "var(--uml-bg)"
        : "var(--surface-soft)",
    boxShadow: isAnchor
      ? "0 0 0 3px color-mix(in srgb, var(--accent) 18%, transparent), 0 20px 38px rgba(0, 0, 0, 0.34)"
      : "0 12px 26px rgba(0, 0, 0, 0.22)",
    padding: 0,
  };
}

/** 决定节点是否应使用紧凑卡片：尊重显式标记，否则按非 anchor 默认紧凑的原则。 */
function compactClassCard(node: LinkGraphNode): boolean {
  if (node.metadata?.["presentation.compact"] === "false") {
    return false;
  }
  if (node.metadata?.["presentation.compact"] === "true") {
    return true;
  }
  if (node.metadata?.["presentation.role"]) {
    return node.metadata["presentation.role"] !== "ANCHOR";
  }
  return node.metadata?.["layout.direction"] !== "ANCHOR";
}

/** 根据关系类型与 lane 关系调整边的描边颜色、宽度、不透明度和虚线模式。 */
function classEdgeStyle(edge: LinkGraphEdge): Record<string, string | number> {
  const presentation = classDiagramRelationPresentation(edge);
  const sameLaneRelation = edge.metadata?.["layout.sameLaneRelation"] === "true";
  const anchorRelation = edge.metadata?.["layout.anchorRelation"] === "true";
  const secondaryRelation = edge.metadata?.["layout.anchorRelation"] === "false";
  const style: Record<string, string | number> = {
    stroke: presentation.color,
    strokeWidth: secondaryRelation
      ? Math.max(1.6, presentation.strokeWidth - 0.9)
      : sameLaneRelation && !anchorRelation
      ? Math.max(1.8, presentation.strokeWidth - 0.7)
      : presentation.strokeWidth,
    opacity: secondaryRelation ? 0.5 : sameLaneRelation && !anchorRelation ? 0.58 : presentation.opacity ?? 0.96,
  };
  if (presentation.strokeDasharray) {
    style.strokeDasharray = presentation.strokeDasharray;
  }
  return style;
}

/** 生成边的可见标签文本：基础关系名 + 可信度（非"静态确认"才追加）。 */
function classEdgeLabel(edge: LinkGraphEdge): string {
  const base = classDiagramRelationDisplayLabel(edge);
  const confidence = relationConfidenceLabel(edge.metadata?.["jvm.relation.confidence"]);
  return confidence && confidence !== "静态确认" ? `${base} · ${confidence}` : base;
}

/** 生成边标签 hover 时的 title 文本（多行），用更详细的关系描述加可信度。 */
function classEdgeLabelTitle(edge: LinkGraphEdge): string {
  const detail = classDiagramRelationDetailLabel(edge);
  const confidence = relationConfidenceLabel(edge.metadata?.["jvm.relation.confidence"]);
  return confidence && confidence !== "静态确认" ? `${detail}\n${confidence}` : detail;
}

/** 决定边标签的可见性策略：次级关系仅在聚焦时显示，其余始终显示。 */
function classEdgeLabelVisibility(edge: LinkGraphEdge): RoutedEdgeData["labelVisibility"] {
  return edge.metadata?.["layout.anchorRelation"] === "false" ? "focus" : "always";
}

/** 根据关系类型选择 React Flow 的箭头 marker：泛化/实现用空心箭头，其余用实心。 */
function classEdgeMarker(edge: LinkGraphEdge): EdgeMarker {
  const kind = classDiagramRelationKind(edge);
  const presentation = classDiagramRelationPresentation(edge);
  return {
    type: kind === "GENERALIZATION" || kind === "REALIZATION" || kind === "EXTENDS" || kind === "IMPLEMENTS" ? MarkerType.Arrow : MarkerType.ArrowClosed,
    width: kind === "GENERALIZATION" || kind === "REALIZATION" || kind === "EXTENDS" || kind === "IMPLEMENTS" ? 24 : 18,
    height: kind === "GENERALIZATION" || kind === "REALIZATION" || kind === "EXTENDS" || kind === "IMPLEMENTS" ? 24 : 18,
    color: presentation.color,
  };
}

/** 选择目标端的装饰物：组合关系用实心菱形，聚合用空心菱形，其它由 marker 处理。 */
function classEdgeTargetAdornment(edge: LinkGraphEdge): RoutedEdgeData["targetAdornment"] | undefined {
  switch (classDiagramRelationKind(edge)) {
    case "COMPOSITION":
      return "filled-diamond";
    case "AGGREGATION":
      return "diamond";
    default:
      return undefined;
  }
}

/** 解析边 metadata 中的标签摆放策略，未配置时默认 target-stub。 */
function classEdgeLabelPlacement(edge: LinkGraphEdge): RoutedEdgeData["labelPlacement"] {
  const placement = edge.metadata?.["layout.labelPlacement"];
  return placement === "source-stub" || placement === "target-stub" || placement === "center"
    ? placement
    : "target-stub";
}

/**
 * 把领域节点转换为 React Flow 节点：合并 className（kind + 高亮状态）、写入位置与 data、
 * 应用节点样式，并把每条节点挂上对应的尺寸上报回调。
 */
export function buildClassDiagramNodes({
  nodes,
  selectedNodeId,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareNodeStatuses = {},
  projectionIndex = null,
  nodeSizeRegistry,
}: BuildClassDiagramNodesOptions): ClassDiagramFlowNode[] {
  const draftChangedNodeIdSet = new Set(draftChangedNodeIds);
  return nodes.map((node) => ({
    id: node.id,
    type: "classDiagramNode",
    className: [
      umlCardKindClassName(node),
      resolveGraphNodeHighlightClassName({
        nodeId: node.id,
        explanationFocusNodeId,
        draftChangedNodeIdSet,
        draftCompareStatus: draftCompareNodeStatuses[node.id],
      }),
    ].filter(Boolean).join(" ") || undefined,
    selected: selectedNodeId === node.id,
    draggable: canEditNodeLayout(node, "CLASS_DIAGRAM", projectionIndex),
    position: node.position ?? { x: 80, y: 88 },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: {
      node,
      compact: compactClassCard(node),
      explanationFocused: explanationFocusNodeId === node.id,
      draftChanged: draftChangedNodeIdSet.has(node.id),
      draftCompareStatus: draftCompareNodeStatuses[node.id],
      onMeasure: nodeSizeRegistry.reporter(node.id),
    },
    style: classNodeStyle(node),
  }));
}

/**
 * 把领域边转换为 React Flow 边：写入标签、样式、装饰、marker、zIndex 等，
 * 同时按 metadata 决定是否保留存储路径还是交由路由器重算。
 */
export function buildClassDiagramEdges({
  edges,
  draftCompareEdgeStatuses = {},
}: BuildClassDiagramEdgesOptions): Array<Edge<RoutedEdgeData, "routedEdge">> {
  return edges.map((edge) => {
    const presentation = classDiagramRelationPresentation(edge);
    const preserveStoredRoute = edge.metadata?.["layout.routeMode"] === "stored"
      || edge.metadata?.["layout.route"] === "class-diagram-lane";
    const targetAdornment = classEdgeTargetAdornment(edge);
    return {
      id: edge.id,
      source: edge.source,
      target: edge.target,
      sourceHandle: edge.sourceHandle ?? undefined,
      targetHandle: edge.targetHandle ?? undefined,
      label: classEdgeLabel(edge),
      type: "routedEdge",
      className: draftCompareEdgeClassName(`edge-domain class-diagram-edge class-diagram-edge-${presentation.role}`, draftCompareEdgeStatuses[edge.id]),
      data: {
        route: edge.route,
        labelVisibility: classEdgeLabelVisibility(edge),
        labelTitle: classEdgeLabelTitle(edge),
        labelPlacement: classEdgeLabelPlacement(edge),
        routeMode: preserveStoredRoute ? "stored" : undefined,
        sourceAdornment: "dot",
        targetAdornment,
      },
      style: draftCompareEdgeStyle(classEdgeStyle(edge), draftCompareEdgeStatuses[edge.id]),
      markerEnd: targetAdornment ? undefined : {
        ...classEdgeMarker(edge),
        color: draftCompareMarkerColor(presentation.color, draftCompareEdgeStatuses[edge.id]),
      },
      zIndex: presentation.zIndex,
    };
  });
}
