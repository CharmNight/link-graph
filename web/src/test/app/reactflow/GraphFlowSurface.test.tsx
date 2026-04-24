import type { ComponentProps, ReactNode } from "react";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LinkGraphEdge, LinkGraphNode } from "../../../app/types";
import { GraphFlowSurface } from "../../../app/reactflow/GraphFlowSurface";

const reactFlowFitViewMock = vi.fn();
const reactFlowSetCenterMock = vi.fn();
const reactFlowScreenToFlowPositionMock = vi.fn(({ x, y }: { x: number; y: number }) => ({ x, y }));
const reactFlowInstanceMock = {
  fitView: reactFlowFitViewMock,
  setCenter: reactFlowSetCenterMock,
  screenToFlowPosition: reactFlowScreenToFlowPositionMock,
};

let resizeObserverCallback: ResizeObserverCallback | null = null;

vi.mock("@xyflow/react", async () => {
  const React = await import("react");

  function MockReactFlow({
    nodes,
    edges,
    edgeTypes,
    minZoom,
    onInit,
    onPaneClick,
    onPaneContextMenu,
    onEdgeClick,
    onNodeClick,
    onNodeDoubleClick,
    onNodeContextMenu,
    onEdgeContextMenu,
    onConnect,
    onNodeDrag,
    onNodeDragStop,
    onSelectionDrag,
    onSelectionDragStop,
    children,
  }: {
    nodes: Array<{ id: string; data: { label: ReactNode }; position?: { x: number; y: number } }>;
    edges: Array<{ id: string; label?: string }>;
    edgeTypes?: Record<string, unknown>;
    minZoom?: number;
    onInit?: (instance: typeof reactFlowInstanceMock) => void;
    onPaneClick?: () => void;
    onPaneContextMenu?: (event: React.MouseEvent<HTMLDivElement>) => void;
    onEdgeClick?: (event: React.MouseEvent<HTMLDivElement>, edge: { id: string }) => void;
    onNodeClick?: (event: React.MouseEvent<HTMLDivElement>, node: { id: string }) => void;
    onNodeDoubleClick?: (event: React.MouseEvent<HTMLDivElement>, node: { id: string }) => void;
    onNodeContextMenu?: (event: React.MouseEvent<HTMLDivElement>, node: { id: string }) => void;
    onEdgeContextMenu?: (event: React.MouseEvent<HTMLDivElement>, edge: { id: string }) => void;
    onConnect?: (connection: {
      source: string | null;
      target: string | null;
      sourceHandle?: string | null;
      targetHandle?: string | null;
    }) => void;
    onNodeDrag?: (
      event: React.MouseEvent<HTMLDivElement>,
      node: { id: string; position: { x: number; y: number } },
    ) => void;
    onNodeDragStop?: (
      event: React.MouseEvent<HTMLDivElement>,
      node: { id: string; position: { x: number; y: number } },
    ) => void;
    onSelectionDrag?: (
      event: React.MouseEvent<HTMLDivElement>,
      nodes: Array<{ id: string; position: { x: number; y: number } }>,
    ) => void;
    onSelectionDragStop?: (
      event: React.MouseEvent<HTMLDivElement>,
      nodes: Array<{ id: string; position: { x: number; y: number } }>,
    ) => void;
    children?: ReactNode;
  }) {
    React.useEffect(() => {
      onInit?.(reactFlowInstanceMock);
    }, [onInit]);

    return (
      <div
        data-testid="reactflow"
        data-edge-types={Object.keys(edgeTypes ?? {}).join(",")}
        data-min-zoom={String(minZoom ?? "")}
        onClick={() => onPaneClick?.()}
        onContextMenu={(event) => onPaneContextMenu?.(event)}
      >
        <div data-testid="reactflow-node-layer">
          {nodes.map((node) => (
            <div
              key={node.id}
              data-testid={`reactflow-node-${node.id}`}
              data-position={`${node.position?.x ?? 0},${node.position?.y ?? 0}`}
              onClick={(event) => onNodeClick?.(event, { id: node.id })}
              onDoubleClick={(event) => onNodeDoubleClick?.(event, { id: node.id })}
              onContextMenu={(event) => onNodeContextMenu?.(event, { id: node.id })}
            >
              {node.data.label}
            </div>
          ))}
        </div>
        <div data-testid="reactflow-edge-layer">
          {edges.map((edge) => (
            <div
              key={edge.id}
              data-testid={`reactflow-edge-${edge.id}`}
              onClick={(event) => onEdgeClick?.(event, { id: edge.id })}
              onContextMenu={(event) => onEdgeContextMenu?.(event, { id: edge.id })}
            >
              {edge.label}
            </div>
          ))}
        </div>
        <button
          type="button"
          data-testid="reactflow-connect-handled-edge"
          onClick={() =>
            onConnect?.({
              source: "method:anchor",
              target: "method:tail",
              sourceHandle: "source-bottom",
              targetHandle: "target-top",
            })}
        >
          connect-handled-edge
        </button>
        <button
          type="button"
          data-testid="reactflow-drag-progress-node"
          onClick={(event) =>
            nodes[0]
              ? onNodeDrag?.(event as unknown as React.MouseEvent<HTMLDivElement>, {
                  id: nodes[0].id,
                  position: { x: 420, y: 240 },
                })
              : undefined}
        >
          drag-progress-node
        </button>
        <button
          type="button"
          data-testid="reactflow-drag-node"
          onClick={(event) =>
            nodes[0]
              ? onNodeDragStop?.(event as unknown as React.MouseEvent<HTMLDivElement>, {
                  id: nodes[0].id,
                  position: nodes[0].position ?? { x: 0, y: 0 },
                })
              : undefined}
        >
          drag-node
        </button>
        <button
          type="button"
          data-testid="reactflow-drag-progress-selection"
          onClick={(event) =>
            onSelectionDrag?.(
              event as unknown as React.MouseEvent<HTMLDivElement>,
              nodes.map((node, index) => ({
                id: node.id,
                position: {
                  x: (node.position?.x ?? 0) + 24 * (index + 1),
                  y: (node.position?.y ?? 0) + 12 * (index + 1),
                },
              })),
            )}
        >
          drag-progress-selection
        </button>
        <button
          type="button"
          data-testid="reactflow-drag-selection"
          onClick={(event) =>
            onSelectionDragStop?.(
              event as unknown as React.MouseEvent<HTMLDivElement>,
              nodes.map((node) => ({
                id: node.id,
                position: node.position ?? { x: 0, y: 0 },
              })),
            )}
        >
          drag-selection
        </button>
        {children}
      </div>
    );
  }

  return {
    __esModule: true,
    ReactFlow: MockReactFlow,
    Background: () => <div data-testid="reactflow-background" />,
    Controls: () => <div data-testid="reactflow-controls" />,
    SelectionMode: {
      Partial: "partial",
    },
  };
});

function installResizeObserverStub() {
  vi.stubGlobal(
    "ResizeObserver",
    class ResizeObserver {
      constructor(callback: ResizeObserverCallback) {
        resizeObserverCallback = callback;
      }

      observe() {
        return undefined;
      }

      disconnect() {
        return undefined;
      }

      unobserve() {
        return undefined;
      }
    },
  );
}

function baseNode(id = "method:anchor"): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title: id,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    position: { x: 120, y: 96 },
  };
}

function renderSurface(overrides: Partial<ComponentProps<typeof GraphFlowSurface>> = {}) {
  const node = baseNode();
  const edge: LinkGraphEdge = {
    id: "edge:anchor->tail",
    type: "CALL",
    source: "method:anchor",
    target: "method:tail",
  };
  const props: ComponentProps<typeof GraphFlowSurface> = {
    nodes: [node],
    edges: [edge],
    flowNodes: [
      {
        id: node.id,
        data: { label: node.title },
        position: node.position ?? { x: 0, y: 0 },
      },
    ],
    flowEdges: [
      {
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: edge.id,
      },
    ],
    anchorNodeId: node.id,
    selectedNodeId: null,
    selectedGroupNodeIds: [],
    editable: true,
    emptyState: <div>empty</div>,
    buildPaneActions: ({ close }) => [
      {
        id: "pane-action",
        label: "Pane Action",
        onSelect: () => close(),
      },
    ],
    buildNodeActions: ({ nodeId, close }) => [
      {
        id: "node-action",
        label: `Node Action ${nodeId}`,
        onSelect: () => close(),
      },
    ],
    buildEdgeActions: ({ edgeId, close }) => [
      {
        id: "edge-action",
        label: `Edge Action ${edgeId}`,
        onSelect: () => close(),
      },
    ],
    onSelectNode: () => undefined,
    onSelectionGroupChange: () => undefined,
    onInspectNode: () => undefined,
    onCreateEdge: () => undefined,
    onMoveNode: () => undefined,
    onMoveNodes: () => undefined,
    nodeViewportSize: () => ({ width: 240, height: 120 }),
    ...overrides,
  };
  return render(<GraphFlowSurface {...props} />);
}

afterEach(() => {
  vi.clearAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  resizeObserverCallback = null;
});

describe("GraphFlowSurface", () => {
  it("still renders the React Flow surface when ResizeObserver is unavailable", () => {
    const originalResizeObserver = globalThis.ResizeObserver;
    Reflect.deleteProperty(globalThis, "ResizeObserver");
    try {
      renderSurface();
    } finally {
      Object.defineProperty(globalThis, "ResizeObserver", {
        configurable: true,
        writable: true,
        value: originalResizeObserver,
      });
    }

    expect(screen.getByTestId("reactflow")).toBeInTheDocument();
    expect(screen.queryByText("empty")).not.toBeInTheDocument();
  });

  it("registers the shared routed edge renderer for all view modules", () => {
    installResizeObserverStub();

    renderSurface();

    expect(screen.getByTestId("reactflow")).toHaveAttribute("data-edge-types", expect.stringContaining("routedEdge"));
  });

  it("lowers the React Flow minimum zoom so very tall graphs can still fit inside the canvas", () => {
    installResizeObserverStub();

    renderSurface();

    expect(screen.getByTestId("reactflow")).toHaveAttribute("data-min-zoom", "0.08");
  });

  it("renders the provided empty state when the graph has no visible nodes", () => {
    installResizeObserverStub();

    renderSurface({
      nodes: [],
      edges: [],
      flowNodes: [],
      flowEdges: [],
      anchorNodeId: null,
      emptyState: <div>当前没有可展示节点</div>,
    });

    expect(screen.getByText("当前没有可展示节点")).toBeInTheDocument();
  });

  it("locates the anchor node through the shared viewport control", async () => {
    installResizeObserverStub();

    renderSurface();

    fireEvent.click(screen.getByRole("button", { name: "定位当前方法" }));

    expect(reactFlowSetCenterMock).toHaveBeenCalledWith(240, 156, { duration: 0 });
  });

  it("shows the grouped-selection chip without depending on per-view canvas logic", () => {
    installResizeObserverStub();

    renderSurface({
      selectedGroupNodeIds: ["method:anchor", "method:tail"],
    });

    expect(screen.getByRole("status")).toHaveTextContent("已框选 2 个节点");
  });

  it("opens pane, node, and edge context menus through the shared menu infrastructure", () => {
    installResizeObserverStub();

    renderSurface();

    fireEvent.contextMenu(screen.getByTestId("reactflow"), {
      clientX: 320,
      clientY: 240,
    });
    expect(within(screen.getByRole("menu")).getByRole("menuitem", { name: "Pane Action" })).toBeInTheDocument();

    fireEvent.contextMenu(screen.getByTestId("reactflow-node-method:anchor"), {
      clientX: 360,
      clientY: 260,
    });
    expect(within(screen.getByRole("menu")).getByRole("menuitem", { name: "Node Action method:anchor" })).toBeInTheDocument();

    fireEvent.contextMenu(screen.getByTestId("reactflow-edge-edge:anchor->tail"), {
      clientX: 380,
      clientY: 280,
    });
    expect(within(screen.getByRole("menu")).getByRole("menuitem", { name: "Edge Action edge:anchor->tail" })).toBeInTheDocument();
  });

  it("shows explicit edge actions after selecting an edge and supports keyboard deletion without relying on the context menu", () => {
    installResizeObserverStub();
    const onDeleteEdge = vi.fn();

    renderSurface({
      buildEdgeActions: ({ edgeId, close }) => [
        {
          id: "delete-edge",
          label: "删除连线",
          onSelect: () => {
            onDeleteEdge(edgeId);
            close();
          },
        },
      ],
    });

    fireEvent.click(screen.getByTestId("reactflow-edge-edge:anchor->tail"));

    expect(screen.getByRole("button", { name: "删除连线" })).toBeInTheDocument();

    fireEvent.keyDown(screen.getByTestId("graph-canvas-shell"), { key: "Delete" });

    expect(onDeleteEdge).toHaveBeenCalledWith("edge:anchor->tail");
  });

  it("does not schedule another fitView when only the selection changes", () => {
    installResizeObserverStub();
    vi.useFakeTimers();

    const { rerender } = render(
      <GraphFlowSurface
        nodes={[baseNode()]}
        edges={[]}
        flowNodes={[
          {
            id: "method:anchor",
            data: { label: "anchor" },
            position: { x: 120, y: 96 },
          },
        ]}
        flowEdges={[]}
        anchorNodeId="method:anchor"
        selectedNodeId={null}
        selectedGroupNodeIds={[]}
        editable
        emptyState={<div>empty</div>}
        buildPaneActions={() => []}
        buildNodeActions={() => []}
        buildEdgeActions={() => []}
        onSelectNode={() => undefined}
        onSelectionGroupChange={() => undefined}
        onInspectNode={() => undefined}
        onCreateEdge={() => undefined}
        onMoveNode={() => undefined}
        onMoveNodes={() => undefined}
        nodeViewportSize={() => ({ width: 240, height: 120 })}
      />,
    );

    act(() => {
      vi.runAllTimers();
    });
    expect(reactFlowFitViewMock).toHaveBeenCalledTimes(2);

    rerender(
      <GraphFlowSurface
        nodes={[baseNode()]}
        edges={[]}
        flowNodes={[
          {
            id: "method:anchor",
            data: { label: "anchor" },
            position: { x: 120, y: 96 },
          },
        ]}
        flowEdges={[]}
        anchorNodeId="method:anchor"
        selectedNodeId="method:anchor"
        selectedGroupNodeIds={[]}
        editable
        emptyState={<div>empty</div>}
        buildPaneActions={() => []}
        buildNodeActions={() => []}
        buildEdgeActions={() => []}
        onSelectNode={() => undefined}
        onSelectionGroupChange={() => undefined}
        onInspectNode={() => undefined}
        onCreateEdge={() => undefined}
        onMoveNode={() => undefined}
        onMoveNodes={() => undefined}
        nodeViewportSize={() => ({ width: 240, height: 120 })}
      />,
    );

    act(() => {
      vi.runAllTimers();
    });
    expect(reactFlowFitViewMock).toHaveBeenCalledTimes(2);
  });

  it("centers an explicitly requested node even when the graph shape and selection policy stay unchanged", () => {
    installResizeObserverStub();
    vi.useFakeTimers();

    const anchorNode = baseNode();
    const tailNode = {
      ...baseNode("method:tail"),
      position: { x: 440, y: 96 },
    };

    const { rerender } = render(
      <GraphFlowSurface
        nodes={[anchorNode, tailNode]}
        edges={[]}
        flowNodes={[
          {
            id: anchorNode.id,
            data: { label: anchorNode.title },
            position: anchorNode.position ?? { x: 0, y: 0 },
          },
          {
            id: tailNode.id,
            data: { label: tailNode.title },
            position: tailNode.position ?? { x: 0, y: 0 },
          },
        ]}
        flowEdges={[]}
        anchorNodeId={anchorNode.id}
        selectedNodeId={anchorNode.id}
        selectedGroupNodeIds={[]}
        editable
        emptyState={<div>empty</div>}
        buildPaneActions={() => []}
        buildNodeActions={() => []}
        buildEdgeActions={() => []}
        onSelectNode={() => undefined}
        onSelectionGroupChange={() => undefined}
        onInspectNode={() => undefined}
        onCreateEdge={() => undefined}
        onMoveNode={() => undefined}
        onMoveNodes={() => undefined}
        nodeViewportSize={() => ({ width: 240, height: 120 })}
      />,
    );

    act(() => {
      vi.runAllTimers();
    });
    reactFlowSetCenterMock.mockClear();

    rerender(
      <GraphFlowSurface
        nodes={[anchorNode, tailNode]}
        edges={[]}
        flowNodes={[
          {
            id: anchorNode.id,
            data: { label: anchorNode.title },
            position: anchorNode.position ?? { x: 0, y: 0 },
          },
          {
            id: tailNode.id,
            data: { label: tailNode.title },
            position: tailNode.position ?? { x: 0, y: 0 },
          },
        ]}
        flowEdges={[]}
        anchorNodeId={anchorNode.id}
        selectedNodeId={tailNode.id}
        selectedGroupNodeIds={[]}
        focusNodeRequest={{ nodeId: tailNode.id, nonce: 1 }}
        editable
        emptyState={<div>empty</div>}
        buildPaneActions={() => []}
        buildNodeActions={() => []}
        buildEdgeActions={() => []}
        onSelectNode={() => undefined}
        onSelectionGroupChange={() => undefined}
        onInspectNode={() => undefined}
        onCreateEdge={() => undefined}
        onMoveNode={() => undefined}
        onMoveNodes={() => undefined}
        nodeViewportSize={() => ({ width: 240, height: 120 })}
      />,
    );

    act(() => {
      vi.runAllTimers();
    });

    expect(reactFlowSetCenterMock).toHaveBeenCalledWith(560, 156, { duration: 0 });
    expect(reactFlowFitViewMock).toHaveBeenCalledTimes(2);
  });

  it("centers a newly selected non-anchor node even when the graph shape stays unchanged", () => {
    installResizeObserverStub();
    vi.useFakeTimers();

    const anchorNode = baseNode();
    const tailNode = {
      ...baseNode("method:tail"),
      position: { x: 440, y: 96 },
    };

    const { rerender } = render(
      <GraphFlowSurface
        nodes={[anchorNode, tailNode]}
        edges={[]}
        flowNodes={[
          {
            id: anchorNode.id,
            data: { label: anchorNode.title },
            position: anchorNode.position ?? { x: 0, y: 0 },
          },
          {
            id: tailNode.id,
            data: { label: tailNode.title },
            position: tailNode.position ?? { x: 0, y: 0 },
          },
        ]}
        flowEdges={[]}
        anchorNodeId={anchorNode.id}
        selectedNodeId={anchorNode.id}
        selectedGroupNodeIds={[]}
        editable
        emptyState={<div>empty</div>}
        buildPaneActions={() => []}
        buildNodeActions={() => []}
        buildEdgeActions={() => []}
        onSelectNode={() => undefined}
        onSelectionGroupChange={() => undefined}
        onInspectNode={() => undefined}
        onCreateEdge={() => undefined}
        onMoveNode={() => undefined}
        onMoveNodes={() => undefined}
        nodeViewportSize={() => ({ width: 240, height: 120 })}
      />,
    );

    act(() => {
      vi.runAllTimers();
    });
    reactFlowSetCenterMock.mockClear();

    rerender(
      <GraphFlowSurface
        nodes={[anchorNode, tailNode]}
        edges={[]}
        flowNodes={[
          {
            id: anchorNode.id,
            data: { label: anchorNode.title },
            position: anchorNode.position ?? { x: 0, y: 0 },
          },
          {
            id: tailNode.id,
            data: { label: tailNode.title },
            position: tailNode.position ?? { x: 0, y: 0 },
          },
        ]}
        flowEdges={[]}
        anchorNodeId={anchorNode.id}
        selectedNodeId={tailNode.id}
        selectedGroupNodeIds={[]}
        editable
        emptyState={<div>empty</div>}
        buildPaneActions={() => []}
        buildNodeActions={() => []}
        buildEdgeActions={() => []}
        onSelectNode={() => undefined}
        onSelectionGroupChange={() => undefined}
        onInspectNode={() => undefined}
        onCreateEdge={() => undefined}
        onMoveNode={() => undefined}
        onMoveNodes={() => undefined}
        nodeViewportSize={() => ({ width: 240, height: 120 })}
      />,
    );

    act(() => {
      vi.runAllTimers();
    });

    expect(reactFlowSetCenterMock).toHaveBeenCalledWith(560, 156, { zoom: 0.76, duration: 0 });
    expect(reactFlowFitViewMock).toHaveBeenCalledTimes(2);
  });

  it("uses the flowchart viewport mode to center the anchor at a readable zoom instead of fitting the entire tall graph", () => {
    installResizeObserverStub();
    vi.useFakeTimers();

    renderSurface({
      nodeViewportSize: () => ({ width: 240, height: 120 }),
      viewportMode: "FLOWCHART",
    });

    act(() => {
      vi.runAllTimers();
    });

    expect(reactFlowSetCenterMock).toHaveBeenCalledWith(240, 156, { zoom: 0.76, duration: 0 });
    expect(reactFlowFitViewMock).not.toHaveBeenCalled();
  });

  it("publishes node drag updates even when structural editing is disabled", () => {
    installResizeObserverStub();
    const onMoveNode = vi.fn();

    renderSurface({
      editable: false,
      onMoveNode,
    });

    fireEvent.click(screen.getByTestId("reactflow-drag-node"));

    expect(onMoveNode).toHaveBeenCalledWith("method:anchor", { x: 120, y: 96 });
  });

  it("reflects the live node position inside the controlled surface before drag stop publishes layout", () => {
    installResizeObserverStub();
    const onMoveNode = vi.fn();

    renderSurface({
      onMoveNode,
    });

    expect(screen.getByTestId("reactflow-node-method:anchor")).toHaveAttribute("data-position", "120,96");

    fireEvent.click(screen.getByTestId("reactflow-drag-progress-node"));

    expect(screen.getByTestId("reactflow-node-method:anchor")).toHaveAttribute("data-position", "420,240");
    expect(onMoveNode).not.toHaveBeenCalled();
  });

  it("publishes grouped drag updates even when structural editing is disabled", () => {
    installResizeObserverStub();
    const onMoveNodes = vi.fn();
    const anchorNode = baseNode();
    const tailNode = {
      ...baseNode("method:tail"),
      position: { x: 440, y: 96 },
    };

    renderSurface({
      editable: false,
      nodes: [anchorNode, tailNode],
      edges: [
        {
          id: "edge:anchor->tail",
          type: "CALL",
          source: "method:anchor",
          target: "method:tail",
        },
      ],
      flowNodes: [
        {
          id: anchorNode.id,
          data: { label: anchorNode.title },
          position: anchorNode.position ?? { x: 0, y: 0 },
        },
        {
          id: tailNode.id,
          data: { label: tailNode.title },
          position: tailNode.position ?? { x: 0, y: 0 },
        },
      ],
      flowEdges: [
        {
          id: "edge:anchor->tail",
          source: "method:anchor",
          target: "method:tail",
          label: "edge:anchor->tail",
        },
      ],
      onMoveNodes,
    });

    fireEvent.click(screen.getByTestId("reactflow-drag-selection"));

    expect(onMoveNodes).toHaveBeenCalledWith([
      { id: "method:anchor", position: { x: 120, y: 96 } },
      { id: "method:tail", position: { x: 440, y: 96 } },
    ]);
  });

  it("preserves source and target handle intent when a user creates an edge from a specific port", () => {
    installResizeObserverStub();
    const onCreateEdge = vi.fn();

    renderSurface({ onCreateEdge });

    fireEvent.click(screen.getByTestId("reactflow-connect-handled-edge"));

    expect(onCreateEdge).toHaveBeenCalledWith("method:anchor", "method:tail", "source-bottom", "target-top");
  });
});
