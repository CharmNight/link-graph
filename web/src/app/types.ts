export type Certainty = "PROVEN" | "RULE_INFERRED" | "LLM_SUGGESTED";

export type BindingStatus =
  | "BOUND"
  | "DESIGN_ONLY"
  | "GENERATABLE"
  | "PARTIALLY_SYNCED"
  | "CONFLICTED";

export type DiffStatus = "MATCHED" | "ONLY_IN_CODE" | "ONLY_IN_MERMAID" | "MODIFIED";

export type NodeType =
  | "METHOD"
  | "CLASS"
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
  | "IMPLEMENTS"
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
  | "GENERATES";

export interface LinkGraphNode {
  id: string;
  type: NodeType;
  title: string;
  signature?: string;
  doc?: string;
  certainty: Certainty;
  bindingStatus: BindingStatus;
  diffStatus?: DiffStatus;
}

export interface LinkGraphEdge {
  id: string;
  type: EdgeType;
  source: string;
  target: string;
  label?: string;
}

export interface LinkGraphDocument {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
}

export interface SyncPreviewItem {
  id: string;
  title: string;
  description: string;
  risk: "LOW" | "MEDIUM" | "HIGH";
}

export interface DiffItem {
  id: string;
  title: string;
  status: DiffStatus;
  description: string;
}

export interface LinkGraphBootstrapState {
  graph: LinkGraphDocument;
  diffItems: DiffItem[];
  syncPreviewItems: SyncPreviewItem[];
  selectedNodeId?: string | null;
}
