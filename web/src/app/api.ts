import type { LinkGraphNode, SyncPreviewItem } from "./types";

declare global {
  interface Window {
    linkGraphBridge?: {
      exportMermaid?: () => void;
      requestSyncPreview?: () => void;
      graphChanged?: (payload: { nodes: LinkGraphNode[] }) => void;
    };
  }
}

export function exportMermaid(): void {
  window.linkGraphBridge?.exportMermaid?.();
}

export function requestSyncPreview(): void {
  window.linkGraphBridge?.requestSyncPreview?.();
}

export function publishGraphChange(nodes: LinkGraphNode[]): void {
  window.linkGraphBridge?.graphChanged?.({ nodes });
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
