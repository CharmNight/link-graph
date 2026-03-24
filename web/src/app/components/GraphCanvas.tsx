import ReactFlow, { Background, Controls, MiniMap } from "reactflow";
import "reactflow/dist/style.css";
import type { LinkGraphEdge, LinkGraphNode } from "../types";
import { IssueBadge } from "./IssueBadge";

interface GraphCanvasProps {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  onAddNode: () => void;
  onDeleteNode: (nodeId: string) => void;
  onReconnectEdge: (edgeId: string) => void;
}

export function GraphCanvas({
  nodes,
  edges,
  onAddNode,
  onDeleteNode,
  onReconnectEdge,
}: GraphCanvasProps) {
  const supportsFlow = typeof ResizeObserver !== "undefined";

  const flowNodes = nodes.map((node, index) => ({
    id: node.id,
    position: { x: 80 + index * 220, y: 120 + (index % 2) * 160 },
    data: { label: node.title },
    type: "default",
  }));

  const flowEdges = edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.type,
  }));

  return (
    <section className="graph-canvas-panel">
      <div className="canvas-toolbar">
        <div>
          <p className="eyebrow">Editable Graph</p>
          <h2>Chain Workspace</h2>
        </div>
        <button type="button" className="primary-button" onClick={onAddNode}>
          Add Node
        </button>
      </div>

      <div className="graph-canvas-shell">
        {supportsFlow ? (
          <ReactFlow nodes={flowNodes} edges={flowEdges} fitView nodesDraggable={false} nodesConnectable={false}>
            <MiniMap />
            <Controls />
            <Background gap={18} size={1} />
          </ReactFlow>
        ) : (
          <div className="graph-canvas-fallback" data-testid="graph-canvas-fallback">
            Graph preview is unavailable in this test environment.
          </div>
        )}
      </div>

      <div className="graph-grid">
        {nodes.map((node) => (
          <article key={node.id} className="graph-node-card">
            <div className="graph-node-header">
              <span className="node-type">{node.type}</span>
              <IssueBadge certainty={node.certainty} diffStatus={node.diffStatus} />
            </div>
            <h3>{node.title}</h3>
            {node.signature ? <p className="muted">{node.signature}</p> : null}
            <div className="card-actions">
              <button type="button" onClick={() => onDeleteNode(node.id)}>
                Delete {node.id}
              </button>
            </div>
          </article>
        ))}
      </div>

      <div className="edge-list">
        {edges.map((edge) => (
          <button key={edge.id} type="button" className="edge-chip" onClick={() => onReconnectEdge(edge.id)}>
            Reconnect {edge.id}
          </button>
        ))}
      </div>
    </section>
  );
}
