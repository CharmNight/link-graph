import { startTransition, useDeferredValue, useState } from "react";
import {
  exportMermaid,
  getSampleSyncPreview,
  publishGraphChange,
  publishNodeSelected,
  readBootstrapState,
  requestSourceNavigation,
  requestSyncPreview,
} from "./api";
import { DiffPanel } from "./components/DiffPanel";
import { GraphCanvas } from "./components/GraphCanvas";
import { Legend } from "./components/Legend";
import { PropertyPanel } from "./components/PropertyPanel";
import { SyncPreviewPanel } from "./components/SyncPreviewPanel";
import { Toolbar } from "./components/Toolbar";
import type { DiffItem, LinkGraphBootstrapState, LinkGraphEdge, LinkGraphNode } from "./types";

const INITIAL_NODES: LinkGraphNode[] = [
  {
    id: "method:place-order",
    type: "METHOD",
    title: "OrderService.place",
    location: "src/main/java/com/example/OrderService.java:12:1",
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

const SAMPLE_STATE: LinkGraphBootstrapState = {
  graph: {
    nodes: INITIAL_NODES,
    edges: INITIAL_EDGES,
  },
  diffItems: DIFF_ITEMS,
  syncPreviewItems: getSampleSyncPreview(),
  selectedNodeId: INITIAL_NODES[0]?.id ?? null,
};

export function App() {
  const initialState = readBootstrapState() ?? SAMPLE_STATE;
  const [nodes, setNodes] = useState<LinkGraphNode[]>(() => initialState.graph.nodes);
  const [edges, setEdges] = useState<LinkGraphEdge[]>(() => initialState.graph.edges);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(
    () => initialState.selectedNodeId ?? initialState.graph.nodes[0]?.id ?? null,
  );
  const [diffItems] = useState<DiffItem[]>(() => initialState.diffItems);
  const [syncPreviewItems] = useState(() => initialState.syncPreviewItems);
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

  function handleSelectNode(nodeId: string) {
    startTransition(() => {
      setSelectedNodeId(nodeId);
      publishNodeSelected(nodeId);
    });
  }

  function handleDiffItemSelect(itemId: string) {
    const targetNode = nodes.find((node) => node.id === itemId);
    if (!targetNode) {
      return;
    }
    handleSelectNode(targetNode.id);
  }

  function handleRequestSourceNavigation(nodeId: string) {
    requestSourceNavigation(nodeId);
  }

  function syncGraph(nextNodes: LinkGraphNode[], nextEdges: LinkGraphEdge[]) {
    setNodes(nextNodes);
    setEdges(nextEdges);
    publishGraphChange(nextNodes, nextEdges);
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
      syncGraph([...nodes, nextNode], edges);
      setSelectedNodeId(nextNode.id);
    });
  }

  function handleDeleteNode(nodeId: string) {
    startTransition(() => {
      const nextNodes = nodes.filter((node) => node.id !== nodeId);
      const nextEdges = edges.filter((edge) => edge.source !== nodeId && edge.target !== nodeId);
      syncGraph(nextNodes, nextEdges);
      if (selectedNodeId === nodeId) {
        setSelectedNodeId(null);
      }
    });
  }

  function handleUpdateNode(nextNode: LinkGraphNode) {
    startTransition(() => {
      syncGraph(
        nodes.map((node) => (node.id === nextNode.id ? nextNode : node)),
        edges,
      );
      setSelectedNodeId(nextNode.id);
    });
  }

  function handleReconnectEdge(edgeId: string) {
    startTransition(() => {
      const nextEdges =
        edges.map((edge) =>
          edge.id === edgeId ? { ...edge, target: nodes[0]?.id ?? edge.target } : edge,
        );
      syncGraph(nodes, nextEdges);
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
          onSelectNode={handleSelectNode}
          onDeleteNode={handleDeleteNode}
          onReconnectEdge={handleReconnectEdge}
        />

        <div className="side-column">
          <PropertyPanel
            selectedNode={selectedNode}
            onUpdateNode={handleUpdateNode}
            onDeleteNode={handleDeleteNode}
            onRequestSourceNavigation={handleRequestSourceNavigation}
          />
          <DiffPanel items={diffItems} onSelectItem={handleDiffItemSelect} />
          <SyncPreviewPanel items={syncPreviewItems} />
        </div>
      </main>
    </div>
  );
}
