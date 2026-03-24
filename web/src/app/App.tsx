import { startTransition, useDeferredValue, useState } from "react";
import { exportMermaid, getSampleSyncPreview, publishGraphChange, requestSyncPreview } from "./api";
import { DiffPanel } from "./components/DiffPanel";
import { GraphCanvas } from "./components/GraphCanvas";
import { Legend } from "./components/Legend";
import { PropertyPanel } from "./components/PropertyPanel";
import { SyncPreviewPanel } from "./components/SyncPreviewPanel";
import { Toolbar } from "./components/Toolbar";
import type { DiffItem, LinkGraphEdge, LinkGraphNode } from "./types";

const INITIAL_NODES: LinkGraphNode[] = [
  {
    id: "method:place-order",
    type: "METHOD",
    title: "OrderService.place",
    signature: "com.example.OrderService.place(java.lang.String):void",
    doc: "Places a new order and dispatches persistence work.",
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  },
  {
    id: "sql:insert-order",
    type: "SQL",
    title: "insert into orders",
    certainty: "RULE_INFERRED",
    bindingStatus: "PARTIALLY_SYNCED",
    diffStatus: "MODIFIED",
  },
];

const INITIAL_EDGES: LinkGraphEdge[] = [
  {
    id: "call:place-order->insert-order",
    type: "CALL",
    source: "method:place-order",
    target: "sql:insert-order",
  },
];

const DIFF_ITEMS: DiffItem[] = [
  {
    id: "method:place-order",
    title: "OrderService.place",
    status: "MODIFIED",
    description: "Mermaid expects a draft DTO branch that code has not wired yet.",
  },
  {
    id: "sql:insert-order",
    title: "insert into orders",
    status: "ONLY_IN_CODE",
    description: "SQL node exists in code but is absent from the imported design graph.",
  },
];

export function App() {
  const [nodes, setNodes] = useState<LinkGraphNode[]>(INITIAL_NODES);
  const [edges, setEdges] = useState<LinkGraphEdge[]>(INITIAL_EDGES);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(INITIAL_NODES[0].id);
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);

  const filteredNodes = nodes.filter((node) => {
    const query = deferredSearch.trim().toLowerCase();
    if (!query) {
      return true;
    }
    return [node.title, node.signature, node.type].some((value) => value?.toLowerCase().includes(query));
  });

  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null;

  function syncNodes(nextNodes: LinkGraphNode[]) {
    setNodes(nextNodes);
    publishGraphChange(nextNodes);
  }

  function handleAddNode() {
    startTransition(() => {
      const nextNode: LinkGraphNode = {
        id: `design:${nodes.length + 1}`,
        type: "CLASS",
        title: `DesignNode${nodes.length + 1}`,
        certainty: "LLM_SUGGESTED",
        bindingStatus: "DESIGN_ONLY",
      };
      syncNodes([...nodes, nextNode]);
      setSelectedNodeId(nextNode.id);
    });
  }

  function handleDeleteNode(nodeId: string) {
    startTransition(() => {
      syncNodes(nodes.filter((node) => node.id !== nodeId));
      setEdges(edges.filter((edge) => edge.source !== nodeId && edge.target !== nodeId));
      if (selectedNodeId === nodeId) {
        setSelectedNodeId(null);
      }
    });
  }

  function handleUpdateNode(nextNode: LinkGraphNode) {
    startTransition(() => {
      syncNodes(nodes.map((node) => (node.id === nextNode.id ? nextNode : node)));
      setSelectedNodeId(nextNode.id);
    });
  }

  function handleReconnectEdge(edgeId: string) {
    startTransition(() => {
      setEdges(
        edges.map((edge) =>
          edge.id === edgeId ? { ...edge, target: nodes[0]?.id ?? edge.target } : edge,
        ),
      );
    });
  }

  return (
    <div className="app-shell">
      <div className="backdrop-orbit backdrop-orbit-a" />
      <div className="backdrop-orbit backdrop-orbit-b" />

      <Toolbar
        search={search}
        onSearchChange={setSearch}
        onAddNode={handleAddNode}
        onExportMermaid={exportMermaid}
        onRequestSync={requestSyncPreview}
      />

      <Legend />

      <main className="workspace-grid">
        <GraphCanvas
          nodes={filteredNodes}
          edges={edges}
          onAddNode={handleAddNode}
          onDeleteNode={handleDeleteNode}
          onReconnectEdge={handleReconnectEdge}
        />

        <div className="side-column">
          <PropertyPanel
            selectedNode={selectedNode}
            onUpdateNode={handleUpdateNode}
            onDeleteNode={handleDeleteNode}
          />
          <DiffPanel items={DIFF_ITEMS} />
          <SyncPreviewPanel items={getSampleSyncPreview()} />
        </div>
      </main>
    </div>
  );
}
