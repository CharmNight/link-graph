import { useLayoutEffect, useRef, type CSSProperties } from "react";
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
import { edgeTypeLabel, nodeTypeLabel, relationConfidenceLabel } from "../../labels";
import type { NodeMeasuredSize, NodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { classDiagramNodeCardWidth } from "../../graphNodeSizing";
import { measureNodeContentBox } from "../../components/graph/nodes/measureNodeContentBox";
import { GraphNodeStateBadges } from "../../components/graph/nodes/GraphNodeStateBadges";
import type { DraftCompareStatus, LinkGraphEdge, LinkGraphNode } from "../../types";
import { canEditNodeLayout } from "../../layoutEditability";
import type { RoutedEdgeData } from "../../reactflow/RoutedEdge";
import { resolveGraphNodeHighlightClassName } from "../graphNodeHighlights";
import {
  draftCompareEdgeClassName,
  draftCompareEdgeStyle,
  draftCompareMarkerColor,
} from "../draftComparePresentation";

interface ClassDiagramNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
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
  nodeSizeRegistry: NodeSizeRegistry;
}

interface BuildClassDiagramEdgesOptions {
  edges: LinkGraphEdge[];
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
}

type ClassDiagramFlowNode = Node<ClassDiagramNodeData, "classDiagramNode">;
type ClassDiagramFlowNodeProps = NodeProps<ClassDiagramFlowNode>;

const HANDLE_STYLE: CSSProperties = {
  width: 16,
  height: 16,
  opacity: 0,
  background: "rgba(70, 71, 108, 0.62)",
  border: "2px solid rgba(255, 255, 255, 0.9)",
  borderRadius: "50%",
};
const EMPTY_MEMBER_TEXT = " ";

function handleStyle(isConnectable: boolean): CSSProperties {
  return {
    ...HANDLE_STYLE,
    opacity: isConnectable ? 0.26 : 0,
    pointerEvents: isConnectable ? "all" : "none",
  };
}

function ClassDiagramNodeCard({
  node,
  selected,
  explanationFocused = false,
  draftChanged = false,
  draftCompareStatus,
  onMeasure,
}: {
  node: LinkGraphNode;
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

  useLayoutEffect(() => {
    const size = measureNodeContentBox(rootRef.current);
    if (size) {
      onMeasure?.(size);
    }
  }, [node, onMeasure]);

  return (
    <div
      ref={rootRef}
      className={["uml-class-card", umlCardKindClassName(node), selected ? "is-selected" : ""].join(" ").trim()}
      data-node-id={node.id}
    >
      <GraphNodeStateBadges selected={selected} explanationFocused={explanationFocused} draftChanged={draftChanged} />
      <div className="uml-class-header">
        <span className="uml-class-stereotype">{stereotype}</span>
        <strong className="uml-class-name" title={node.title}>{node.title}</strong>
        <span className="uml-class-package" title={node.signature ?? node.location ?? node.id}>
          {packageName ?? node.signature ?? node.id}
        </span>
      </div>
      {comment ? <UmlCommentCompartment text={comment} /> : null}
      <UmlMemberCompartment
        kind="field"
        items={fields}
        hiddenCount={hiddenFieldCount}
      />
      <UmlMemberCompartment
        kind="method"
        items={methods}
        hiddenCount={hiddenMethodCount}
      />
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

function isAbstractClass(node: LinkGraphNode): boolean {
  return node.type === "CLASS" && node.metadata?.["jvm.class.abstract"] === "true";
}

function umlCardKindClassName(node: LinkGraphNode): string {
  return [
    `uml-kind-${node.type.toLowerCase()}`,
    isAbstractClass(node) ? "uml-kind-abstract" : "",
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
      {items.length === 0 ? (
        <span className="uml-member-line is-empty">{EMPTY_MEMBER_TEXT}</span>
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
      <Handle id="target-top" type="target" position={Position.Top} style={style} />
      <Handle id="target-left" type="target" position={Position.Left} style={style} />
      <Handle id="source-right" type="source" position={Position.Right} style={style} />
      <Handle id="source-bottom" type="source" position={Position.Bottom} style={style} />
      <ClassDiagramNodeCard
        node={data.node}
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
  const isAnchor = node.metadata?.["layout.direction"] === "ANCHOR";
  return {
    width: classDiagramNodeCardWidth(),
    borderRadius: 8,
    border: isAnchor
      ? "2px solid rgba(25, 90, 153, 0.72)"
      : isTypeNode ? "1.5px solid rgba(38, 38, 38, 0.62)" : "1px solid rgba(44, 32, 22, 0.16)",
    background: "#fffefb",
    boxShadow: isAnchor
      ? "0 8px 18px rgba(25, 90, 153, 0.14)"
      : "0 4px 10px rgba(20, 24, 32, 0.08)",
    padding: 0,
  };
}

function relationKind(edge: LinkGraphEdge): string {
  return edge.metadata?.["jvm.relation.kind"] ?? edge.type;
}

function classEdgeStyle(edge: LinkGraphEdge): CSSProperties {
  switch (relationKind(edge)) {
    case "EXTENDS":
      return { stroke: "#262626", strokeWidth: 2, opacity: 0.95 };
    case "IMPLEMENTS":
      return { stroke: "#262626", strokeWidth: 2, strokeDasharray: "7 5", opacity: 0.95 };
    case "INJECTS":
      return { stroke: "#195a99", strokeWidth: 1.8, opacity: 0.9 };
    case "CALLS":
      return { stroke: "#2d6a4f", strokeWidth: 1.8, opacity: 0.9 };
    case "SPI_PROVIDES":
    case "SERVICE_LOADER_LOADS":
      return { stroke: "#8a4f00", strokeWidth: 1.8, strokeDasharray: "6 4", opacity: 0.9 };
    case "REFLECTS_TO":
      return { stroke: "#7b2cbf", strokeWidth: 1.7, strokeDasharray: "3 5", opacity: 0.9 };
    case "USES_PROXY":
    case "DUBBO_REFERENCES":
    case "FEIGN_CLIENT_CALLS":
      return { stroke: "#0f766e", strokeWidth: 1.7, strokeDasharray: "8 4", opacity: 0.88 };
    case "FEIGN_ROUTES_TO":
    case "SPRING_EVENT_PUBLISHES":
    case "SPRING_EVENT_LISTENS":
    case "MQ_PUBLISHES":
    case "MQ_CONSUMES":
      return { stroke: "#9a3412", strokeWidth: 1.7, opacity: 0.88 };
    case "USES_TYPE":
      return { stroke: "#5f5a53", strokeWidth: 1.6, strokeDasharray: "5 5", opacity: 0.82 };
    default:
      return { stroke: "#5f5a53", strokeWidth: 1.5, opacity: 0.86 };
  }
}

function classEdgeLabel(edge: LinkGraphEdge): string {
  const base = edge.label?.trim() || edgeTypeLabel(edge.type);
  const confidence = relationConfidenceLabel(edge.metadata?.["jvm.relation.confidence"]);
  return confidence && confidence !== "静态确认" ? `${base} · ${confidence}` : base;
}

function classEdgeMarker(edge: LinkGraphEdge): EdgeMarker {
  const kind = relationKind(edge);
  return {
    type: kind === "EXTENDS" || kind === "IMPLEMENTS" ? MarkerType.Arrow : MarkerType.ArrowClosed,
    width: kind === "EXTENDS" || kind === "IMPLEMENTS" ? 24 : 18,
    height: kind === "EXTENDS" || kind === "IMPLEMENTS" ? 24 : 18,
    color: "#262626",
  };
}

export function buildClassDiagramNodes({
  nodes,
  selectedNodeId,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareNodeStatuses = {},
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
    draggable: canEditNodeLayout(node, "CLASS_DIAGRAM"),
    position: node.position ?? { x: 80, y: 88 },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: {
      node,
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
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.sourceHandle ?? undefined,
    targetHandle: edge.targetHandle ?? undefined,
    label: classEdgeLabel(edge),
    type: "routedEdge",
    className: draftCompareEdgeClassName("edge-domain", draftCompareEdgeStatuses[edge.id]),
    data: { route: edge.route },
    style: draftCompareEdgeStyle(classEdgeStyle(edge), draftCompareEdgeStatuses[edge.id]),
    markerEnd: {
      ...classEdgeMarker(edge),
      color: draftCompareMarkerColor("#262626", draftCompareEdgeStatuses[edge.id]),
    },
  }));
}
