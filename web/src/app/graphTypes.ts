export type Certainty = "PROVEN" | "RULE_INFERRED" | "LLM_SUGGESTED";

export type BindingStatus =
  | "BOUND"
  | "DESIGN_ONLY"
  | "GENERATABLE"
  | "PARTIALLY_SYNCED"
  | "CONFLICTED";

export type DiffStatus = "MATCHED" | "ONLY_IN_CODE" | "ONLY_IN_MERMAID" | "MODIFIED";
export type DraftCompareStatus = "MODIFIED" | "ADDED" | "REMOVED";
export type GraphSourceTag = "FACT" | "DESIGN_BASELINE" | "DRAFT_MANUAL" | "DRAFT_AI" | "UNCERTAIN_FACT";
export type GraphDiffElementKind = "NODE" | "EDGE";
export type GraphPatchAction =
  | "ADD_NODE"
  | "UPDATE_NODE"
  | "DELETE_NODE"
  | "ADD_EDGE"
  | "UPDATE_EDGE"
  | "DELETE_EDGE"
  | "ADD_ANNOTATION"
  | "MARK_UNCERTAIN";

export type NodeType =
  | "METHOD"
  | "FLOW_SCOPE"
  | "FLOW_ACTION"
  | "TERMINAL"
  | "MERGE"
  | "CLASS"
  | "MODULE"
  | "PACKAGE"
  | "INTERFACE"
  | "ENUM"
  | "ANNOTATION"
  | "RECORD"
  | "OBJECT"
  | "EXTERNAL_CLASS"
  | "LIBRARY"
  | "SERVICE"
  | "COMPONENT"
  | "LAYER"
  | "RESOURCE"
  | "SQL"
  | "HTTP_ENDPOINT"
  | "FEIGN_CLIENT"
  | "DUBBO_SERVICE"
  | "MQ_TOPIC"
  | "MQ_CONSUMER"
  | "CONFIG_ITEM"
  | "XML_RESOURCE"
  | "DOC_PAGE"
  | "UNCERTAIN_LINK";

export type EdgeType =
  | "CALL"
  | "CONTAINS_FLOW"
  | "CONTROL_FLOW"
  | "IMPLEMENTS"
  | "EXTENDS"
  | "USES_TYPE"
  | "INJECT"
  | "ROUTES_TO"
  | "MAPS_TO_SQL"
  | "PUBLISHES_TO"
  | "CONSUMES_FROM"
  | "BINDS_CONFIG"
  | "LINKS_DOC"
  | "USES_PROXY"
  | "REFLECTS_TO"
  | "SPI_RESOLVES_TO"
  | "TESTS"
  | "GENERATES"
  | "CLASS_USAGE";

export interface GraphPosition {
  x: number;
  y: number;
}

export interface GraphFocusRequest {
  nodeId: string;
  nonce: number;
}

export interface LinkGraphEdgeRouteSection {
  startPoint: GraphPosition;
  endPoint: GraphPosition;
  bendPoints?: GraphPosition[];
}

export interface LinkGraphEdgeRoute {
  sections: LinkGraphEdgeRouteSection[];
}

export interface LinkGraphLayoutState {
  positions: Record<string, GraphPosition>;
}

export type LinkGraphSceneId =
  | "WORKSPACE_FACT"
  | "WORKSPACE_FLOWCHART"
  | "WORKSPACE_RESOURCE_RELATION"
  | "WORKSPACE_ARCHITECTURE_GRAPH"
  | "WORKSPACE_CLASS_DIAGRAM"
  | "WORKSPACE_REVIEW_GRAPH"
  | "DIFF";

export interface LinkGraphSceneState {
  selectedNodeId?: string | null;
  anchorNodeId?: string | null;
  layoutState: LinkGraphLayoutState;
  layoutRevision: number;
  collapsedNodeIds: string[];
}

export interface LinkGraphNode {
  id: string;
  type: NodeType;
  title: string;
  location?: string;
  signature?: string;
  inputs: string[];
  outputs: string[];
  doc?: string;
  certainty: Certainty;
  bindingStatus: BindingStatus;
  diffStatus?: DiffStatus;
  position?: GraphPosition;
  metadata?: Record<string, string>;
  sourceTag?: GraphSourceTag;
}

export interface LinkGraphEdge {
  id: string;
  type: EdgeType;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
  label?: string;
  route?: LinkGraphEdgeRoute;
  metadata?: Record<string, string>;
  sourceTag?: GraphSourceTag;
}

export interface LinkGraphDocument {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  patch?: GraphPatch | null;
  nodeCount?: number;
  edgeCount?: number;
  truncated?: boolean;
}

export interface GraphPatchOperation {
  id: string;
  action: GraphPatchAction;
  elementKind: GraphDiffElementKind;
  elementId: string;
  title?: string | null;
  summary?: string | null;
  node?: LinkGraphNode | null;
  edge?: LinkGraphEdge | null;
  metadata?: Record<string, string>;
}

export interface GraphPatch {
  summary?: string | null;
  operations: GraphPatchOperation[];
  addedNodeIds: string[];
  removedNodeIds: string[];
  addedEdgeIds: string[];
  removedEdgeIds: string[];
}

export type GraphEditOperation =
  | {
      type: "UPSERT_NODE";
      node: LinkGraphNode;
    }
  | {
      type: "REMOVE_NODE";
      nodeId: string;
    }
  | {
      type: "UPSERT_EDGE";
      edge: LinkGraphEdge;
    }
  | {
      type: "REMOVE_EDGE";
      edgeId: string;
    };

export interface GraphEditScript {
  sceneId: LinkGraphSceneId;
  baseWorkspaceRevision: number;
  operations: GraphEditOperation[];
}
