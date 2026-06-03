import { useLayoutEffect, useRef, type CSSProperties, type Ref } from "react";
import {
  Handle,
  MarkerType,
  Position,
  useUpdateNodeInternals,
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
import type { RoutedEdgeData } from "../../reactflow/RoutedEdge";
import { resolveGraphNodeHighlightClassName } from "../graphNodeHighlights";
import {
  draftCompareEdgeClassName,
  draftCompareEdgeStyle,
  draftCompareMarkerColor,
} from "../draftComparePresentation";
import {
  classDiagramCompactRelationLabel,
  classDiagramRelationDetailLabel,
  classDiagramRelationDisplayLabel,
  classDiagramRelationKind,
  classDiagramRelationPresentation,
} from "./classDiagramRelations";

interface ClassDiagramNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
  compact?: boolean;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}

interface BuildClassDiagramNodesOptions {
  nodes: LinkGraphNode[];
  selectedNodeId: string | null;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIds?: string[];
  draftCompareNodeStatuses?: Record<string, DraftCompareStatus>;
  projectionIndex?: GraphProjectionIndex | null;
  nodeSizeRegistry: NodeSizeRegistry;
}

interface BuildClassDiagramEdgesOptions {
  edges: LinkGraphEdge[];
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
}

type ClassDiagramFlowNode = Node<ClassDiagramNodeData, "classDiagramNode">;
type ClassDiagramFlowNodeProps = NodeProps<ClassDiagramFlowNode>;

const HANDLE_STYLE: CSSProperties = {
  width: 14,
  height: 14,
  opacity: 0,
  background: "rgba(52, 180, 255, 0.72)",
  border: "2px solid rgba(248, 252, 255, 0.96)",
  borderRadius: "50%",
  boxShadow: "0 0 0 3px rgba(52, 180, 255, 0.16)",
};
const SIDE_FANOUT_SLOT_COUNT = 7;

function handleStyle(isConnectable: boolean): CSSProperties {
  return {
    ...HANDLE_STYLE,
    opacity: isConnectable ? 0.52 : 0,
    pointerEvents: isConnectable ? "all" : "none",
  };
}

function sideFanoutHandleStyle(isConnectable: boolean, index: number): CSSProperties {
  return {
    ...handleStyle(isConnectable),
    top: `${((index + 1) / (SIDE_FANOUT_SLOT_COUNT + 1)) * 100}%`,
  };
}

function verticalFanoutHandleStyle(isConnectable: boolean, index: number): CSSProperties {
  return {
    ...handleStyle(isConnectable),
    left: `${((index + 1) / (SIDE_FANOUT_SLOT_COUNT + 1)) * 100}%`,
  };
}

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

function memberLines(value?: string | null): string[] {
  return (value ?? "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}

function numericMetadata(value?: string | null): number {
  const parsed = Number(value ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0;
}

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

function compactNodeMeta(node: LinkGraphNode): string | null {
  return node.metadata?.["architecture.package"]
    ?? node.signature
    ?? node.location
    ?? null;
}

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

function compactNodeDetailTitle(node: LinkGraphNode): string | null {
  return node.metadata?.["classDiagram.node.reason"]?.trim() || compactNodeDetail(node);
}

function isAbstractClass(node: LinkGraphNode): boolean {
  return node.type === "CLASS" && node.metadata?.["jvm.class.abstract"] === "true";
}

function isAnchorNode(node: LinkGraphNode): boolean {
  if (node.metadata?.["presentation.role"]) {
    return node.metadata["presentation.role"] === "ANCHOR";
  }
  return node.metadata?.["presentation.role"] === "ANCHOR"
    || node.metadata?.["layout.direction"] === "ANCHOR";
}

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

function umlCardKindClassName(node: LinkGraphNode): string {
  return [
    `uml-kind-${node.type.toLowerCase()}`,
    isAbstractClass(node) ? "uml-kind-abstract" : "",
    isDataTypeNode(node) ? "uml-kind-data" : "",
  ].filter(Boolean).join(" ");
}

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

function umlComment(node: LinkGraphNode): string | null {
  const comment = node.doc ?? node.metadata?.["uml.comment"] ?? node.metadata?.["jvm.class.docComment"];
  return comment?.trim() || null;
}

function UmlCommentCompartment({ text }: { text: string }) {
  return (
    <div className="uml-comment-compartment" aria-label="注释">
      <span className="uml-comment-text" title={text}>{text}</span>
    </div>
  );
}

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

function ClassDiagramReactNode({ id, data, selected, isConnectable }: ClassDiagramFlowNodeProps) {
  const updateNodeInternals = useUpdateNodeInternals();
  const style = handleStyle(isConnectable);

  useLayoutEffect(() => {
    updateNodeInternals(id);
  }, [data.node, id, isConnectable, selected, updateNodeInternals]);

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

export const CLASS_DIAGRAM_NODE_TYPES: NodeTypes = {
  classDiagramNode: ClassDiagramReactNode,
};

function classNodeStyle(node: LinkGraphNode) {
  const isTypeNode = ["CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD", "OBJECT"].includes(node.type);
  const presentationRole = node.metadata?.["presentation.role"];
  const isAnchor = presentationRole === "ANCHOR" || isAnchorNode(node);
  return {
    width: classDiagramNodeCardWidth(node),
    borderRadius: 7,
    border: isAnchor
      ? "3px solid rgba(52, 180, 255, 0.92)"
      : isTypeNode ? "1.5px solid rgba(38, 38, 38, 0.62)" : "1px solid rgba(44, 32, 22, 0.16)",
    background: "#f8f8f4",
    boxShadow: isAnchor
      ? "0 0 0 3px rgba(52, 180, 255, 0.18), 0 20px 38px rgba(0, 0, 0, 0.34)"
      : "0 12px 26px rgba(0, 0, 0, 0.22)",
    padding: 0,
  };
}

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

function classEdgeLabel(edge: LinkGraphEdge): string {
  const base = classDiagramRelationDisplayLabel(edge);
  const confidence = relationConfidenceLabel(edge.metadata?.["jvm.relation.confidence"]);
  return confidence && confidence !== "静态确认" ? `${base} · ${confidence}` : base;
}

function classEdgeLabelTitle(edge: LinkGraphEdge): string {
  const detail = classDiagramRelationDetailLabel(edge);
  const confidence = relationConfidenceLabel(edge.metadata?.["jvm.relation.confidence"]);
  return confidence && confidence !== "静态确认" ? `${detail}\n${confidence}` : detail;
}

function classEdgeLabelVisibility(edge: LinkGraphEdge): RoutedEdgeData["labelVisibility"] {
  return edge.metadata?.["layout.anchorRelation"] === "false" ? "focus" : "always";
}

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

function classEdgeLabelPlacement(edge: LinkGraphEdge): RoutedEdgeData["labelPlacement"] {
  const placement = edge.metadata?.["layout.labelPlacement"];
  return placement === "source-stub" || placement === "target-stub" || placement === "center"
    ? placement
    : "target-stub";
}

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
