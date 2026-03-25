import type {
  BindingStatus,
  Certainty,
  DiffStatus,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  NodeType,
  SyncPreviewItem,
} from "./types";

interface BackendGraphNode {
  id: string;
  type: NodeType;
  title: string;
  signature?: string;
  doc?: string;
  certainty: Certainty;
  bindingStatus: BindingStatus;
  diff: {
    status: DiffStatus;
  };
}

interface BackendGraphEdge {
  id: string;
  type: string;
  fromNodeId: string;
  toNodeId: string;
  label?: string;
}

interface BackendGraphDocument {
  nodes: BackendGraphNode[];
  edges: BackendGraphEdge[];
}

declare global {
  interface Window {
    linkGraphBridge?: {
      exportMermaid?: () => void;
      requestSyncPreview?: () => void;
      graphChanged?: (payload: BackendGraphDocument) => void;
    };
    linkGraphBootstrap?: LinkGraphBootstrapState;
  }
}

export function readBootstrapState(): LinkGraphBootstrapState | null {
  return window.linkGraphBootstrap ?? null;
}

export function exportMermaid(): void {
  window.linkGraphBridge?.exportMermaid?.();
}

export function requestSyncPreview(): void {
  window.linkGraphBridge?.requestSyncPreview?.();
}

export function publishGraphChange(nodes: LinkGraphNode[], edges: LinkGraphEdge[]): void {
  window.linkGraphBridge?.graphChanged?.({
    nodes: nodes.map((node) => ({
      id: node.id,
      type: node.type,
      title: node.title,
      signature: node.signature,
      doc: node.doc,
      certainty: node.certainty,
      bindingStatus: node.bindingStatus,
      diff: {
        status: node.diffStatus ?? "MATCHED",
      },
    })),
    edges: edges.map((edge) => ({
      id: edge.id,
      type: edge.type,
      fromNodeId: edge.source,
      toNodeId: edge.target,
      label: edge.label,
    })),
  });
}

export function getSampleSyncPreview(): SyncPreviewItem[] {
  return [
    {
      id: "create-order-draft",
      title: "Create DTO",
      description: "Generate OrderDraftDto.java",
      risk: "LOW",
    },
    {
      id: "wire-place-draft",
      title: "Wire Service Call",
      description: "Connect controller flow to placeDraft service",
      risk: "MEDIUM",
    },
  ];
}
