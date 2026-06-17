import type { ComponentProps, ReactNode } from "react";
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react/pure";
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
    nodesDraggable,
    panOnDrag,
    panOnScroll,
    panOnScrollMode,
    panOnScrollSpeed,
    zoomOnScroll,
    preventScrolling,
    nodeClickDistance,
    paneClickDistance,
    multiSelectionKeyCode,
    selectNodesOnDrag,
    children,
  }: {
    nodes: Array<{ id: string; data: { label: ReactNode }; position?: { x: number; y: number } }>;
    edges: Array<{
      id: string;
      label?: string;
      selected?: boolean;
      data?: {
        route?: LinkGraphEdge["route"];
      };
    }>;
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
    nodesDraggable?: boolean;
    panOnDrag?: boolean | number[];
    panOnScroll?: boolean;
    panOnScrollMode?: string;
    panOnScrollSpeed?: number;
    zoomOnScroll?: boolean;
    preventScrolling?: boolean;
    nodeClickDistance?: number;
    paneClickDistance?: number;
    multiSelectionKeyCode?: string | string[] | null;
    selectNodesOnDrag?: boolean;
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
        data-nodes-draggable={String(nodesDraggable)}
        data-pan-on-drag={Array.isArray(panOnDrag) ? panOnDrag.join(",") : String(panOnDrag)}
        data-pan-on-scroll={String(panOnScroll)}
        data-pan-on-scroll-mode={String(panOnScrollMode ?? "")}
        data-pan-on-scroll-speed={String(panOnScrollSpeed ?? "")}
        data-zoom-on-scroll={String(zoomOnScroll)}
        data-prevent-scrolling={String(preventScrolling)}
        data-node-click-distance={String(nodeClickDistance ?? "")}
        data-pane-click-distance={String(paneClickDistance ?? "")}
        data-multi-selection-key-code={multiSelectionKeyCode === null ? "null" : String(multiSelectionKeyCode)}
        data-select-nodes-on-drag={String(selectNodesOnDrag)}
        onClick={() => onPaneClick?.()}
        onContextMenu={(event) => onPaneContextMenu?.(event)}
      >
        <div data-testid="reactflow-node-layer">
          {nodes.map((node) => (
            <div
              key={node.id}
              data-testid={`reactflow-node-${node.id}`}
              data-position={`${node.position?.x ?? 0},${node.position?.y ?? 0}`}
              onClick={(event) => {
                event.stopPropagation();
                onNodeClick?.(event, { id: node.id });
              }}
              onDoubleClick={(event) => onNodeDoubleClick?.(event, { id: node.id })}
              onContextMenu={(event) => {
                event.stopPropagation();
                onNodeContextMenu?.(event, { id: node.id });
              }}
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
              data-selected={String(edge.selected === true)}
              data-route-start={
                edge.data?.route?.sections[0]?.startPoint
                  ? `${edge.data.route.sections[0].startPoint.x},${edge.data.route.sections[0].startPoint.y}`
                  : "none"
              }
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

  function MockViewportPortal({ children }: { children?: ReactNode }) {
    return <div className="react-flow__viewport-portal" data-testid="reactflow-viewport-portal">{children}</div>;
  }

  return {
    __esModule: true,
    ReactFlow: MockReactFlow,
    ViewportPortal: MockViewportPortal,
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

function surfaceProps(overrides: Partial<ComponentProps<typeof GraphFlowSurface>> = {}): ComponentProps<typeof GraphFlowSurface> {
  const node = baseNode();
  const edge: LinkGraphEdge = {
    id: "edge:anchor->tail",
    type: "CALL",
    source: "method:anchor",
    target: "method:tail",
  };
  return {
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
}

function renderSurface(overrides: Partial<ComponentProps<typeof GraphFlowSurface>> = {}) {
  const props = surfaceProps(overrides);
  return render(<GraphFlowSurface {...props} />);
}

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  vi.restoreAllMocks();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
  delete window.linkGraphDebugTrace;
  delete window.__linkGraphDebugEnabled;
  delete window.__linkGraphTraceHistory;
  delete window.__linkGraphLastTrace;
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

  it("renders viewport overlays inside the React Flow viewport instead of the outer canvas shell", () => {
    installResizeObserverStub();

    const { container } = renderSurface({
      viewportOverlay: () => <div data-testid="viewport-layer-overlay" />,
    });

    const shellChildren = Array.from(container.querySelector("[data-testid='graph-canvas-shell']")?.children ?? []);
    expect(screen.getByTestId("viewport-layer-overlay")).toBeInTheDocument();
    expect(within(screen.getByTestId("reactflow-viewport-portal")).getByTestId("viewport-layer-overlay")).toBeInTheDocument();
    expect(shellChildren).not.toContain(screen.getByTestId("viewport-layer-overlay"));
  });

  it("builds viewport overlays from the same live positions used to render dragged nodes", () => {
    installResizeObserverStub();

    renderSurface({
      viewportOverlay: ({ nodes }) => {
        const position = nodes[0]?.position;
        return (
          <div data-testid="viewport-overlay-position">
            {position ? `${position.x},${position.y}` : ""}
          </div>
        );
      },
    });

    expect(screen.getByTestId("viewport-overlay-position")).toHaveTextContent("120,96");

    fireEvent.click(screen.getByTestId("reactflow-drag-progress-node"));

    expect(screen.getByTestId("viewport-overlay-position")).toHaveTextContent("420,240");
  });

  it("drops stale routed-edge geometry while an incident node is being dragged", () => {
    installResizeObserverStub();
    const anchor = baseNode("method:anchor");
    const tail: LinkGraphNode = {
      ...baseNode("method:tail"),
      position: { x: 440, y: 96 },
    };
    const edge: LinkGraphEdge = {
      id: "edge:anchor->tail",
      type: "CALL",
      source: anchor.id,
      target: tail.id,
    };

    renderSurface({
      nodes: [anchor, tail],
      edges: [edge],
      flowNodes: [anchor, tail].map((node) => ({
        id: node.id,
        data: { label: node.title },
        position: node.position ?? { x: 0, y: 0 },
      })),
      flowEdges: [
        {
          id: edge.id,
          source: edge.source,
          target: edge.target,
          label: edge.id,
          type: "routedEdge",
          data: {
            route: {
              sections: [
                {
                  startPoint: { x: 240, y: 156 },
                  bendPoints: [{ x: 340, y: 156 }],
                  endPoint: { x: 440, y: 156 },
                },
              ],
            },
          },
        },
      ],
    });

    expect(screen.getByTestId("reactflow-edge-edge:anchor->tail")).toHaveAttribute("data-route-start", "240,156");

    fireEvent.click(screen.getByTestId("reactflow-drag-progress-node"));

    expect(screen.getByTestId("reactflow-node-method:anchor")).toHaveAttribute("data-position", "420,240");
    expect(screen.getByTestId("reactflow-edge-edge:anchor->tail")).toHaveAttribute("data-route-start", "none");
  });

  it("does not include removed architecture layer overlays in the debug DOM probe", () => {
    installResizeObserverStub();
    vi.useFakeTimers();
    const traceSink = vi.fn();
    window.__linkGraphDebugEnabled = true;
    window.linkGraphDebugTrace = traceSink;

    renderSurface({
      viewportMode: "ARCHITECTURE_GRAPH",
    });

    act(() => {
      vi.advanceTimersByTime(200);
    });
    vi.useRealTimers();

    const domProbeTrace = traceSink.mock.calls
      .map(([payload]) => JSON.parse(String(payload)))
      .find((trace) => trace.event === "graphFlowSurface.domProbe");

    expect(domProbeTrace?.payload.selectors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ selector: "[data-testid='graph-canvas-shell']" }),
      ]),
    );
    expect(domProbeTrace?.payload.selectors).not.toEqual(
      expect.arrayContaining([
        expect.objectContaining({ selector: ".architecture-layer-overlay" }),
        expect.objectContaining({ selector: ".architecture-layer-frame" }),
      ]),
    );
  });

  it("does not repeat render-commit traces for local initialization rerenders when graph input is unchanged", () => {
    installResizeObserverStub();
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;

    renderSurface();

    const renderCommitTraces = traceSink.mock.calls
      .map(([payload]) => JSON.parse(String(payload)))
      .filter((trace) => trace.event === "graphFlowSurface.renderCommitted");

    expect(renderCommitTraces).toHaveLength(1);
  });

  it("does not repeat render-commit traces when rerendered with equivalent graph arrays", () => {
    installResizeObserverStub();
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;
    const props = surfaceProps();

    const { rerender } = render(<GraphFlowSurface {...props} />);
    rerender(
      <GraphFlowSurface
        {...props}
        nodes={props.nodes.map((node) => ({ ...node }))}
        edges={props.edges.map((edge) => ({ ...edge }))}
        flowNodes={props.flowNodes.map((node) => ({ ...node, position: { ...node.position } }))}
        flowEdges={props.flowEdges.map((edge) => ({ ...edge }))}
      />,
    );

    const renderCommitTraces = traceSink.mock.calls
      .map(([payload]) => JSON.parse(String(payload)))
      .filter((trace) => trace.event === "graphFlowSurface.renderCommitted");

    expect(renderCommitTraces).toHaveLength(1);
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

  it("marks the selected edge in the controlled edge list so custom edges can reveal contextual labels", () => {
    installResizeObserverStub();

    renderSurface();

    expect(screen.getByTestId("reactflow-edge-edge:anchor->tail")).toHaveAttribute("data-selected", "false");

    fireEvent.click(screen.getByTestId("reactflow-edge-edge:anchor->tail"));

    expect(screen.getByTestId("reactflow-edge-edge:anchor->tail")).toHaveAttribute("data-selected", "true");
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
    vi.useRealTimers();
    expect(reactFlowFitViewMock).toHaveBeenCalledTimes(2);
  });

  it("schedules a new viewport fit when the caller changes the viewport reset key", () => {
    installResizeObserverStub();
    vi.useFakeTimers();

    const anchorNode = baseNode();
    const tailNode = {
      ...baseNode("method:tail"),
      position: { x: 440, y: 96 },
    };

    const { rerender } = render(
      <GraphFlowSurface
        nodes={[anchorNode]}
        edges={[]}
        flowNodes={[
          {
            id: anchorNode.id,
            data: { label: anchorNode.title },
            position: anchorNode.position ?? { x: 0, y: 0 },
          },
        ]}
        flowEdges={[]}
        viewportResetKey="layer:ALL"
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
    expect(reactFlowFitViewMock).toHaveBeenCalledTimes(2);

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
        viewportResetKey="layer:JDK"
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
    vi.useRealTimers();

    expect(reactFlowFitViewMock).toHaveBeenCalledTimes(4);
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
    vi.useRealTimers();

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
    vi.useRealTimers();

    expect(reactFlowSetCenterMock).toHaveBeenCalledWith(560, 156, { zoom: 0.76, duration: 0 });
    expect(reactFlowFitViewMock).toHaveBeenCalledTimes(2);
  });

  it("opens class diagrams with readable-fit instead of shrinking labels to fit the whole UML window", () => {
    installResizeObserverStub();
    vi.useFakeTimers();
    const traceSink = vi.fn();
    window.__linkGraphDebugEnabled = true;
    window.linkGraphDebugTrace = traceSink;
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function getBoundingClientRect(this: HTMLElement) {
      if ((this as HTMLElement).dataset.testid === "graph-canvas-shell") {
        return {
          left: 0,
          top: 0,
          right: 1280,
          bottom: 720,
          width: 1280,
          height: 720,
          x: 0,
          y: 0,
          toJSON: () => undefined,
        };
      }
      return {
        left: 0,
        top: 0,
        right: 0,
        bottom: 0,
        width: 0,
        height: 0,
        x: 0,
        y: 0,
        toJSON: () => undefined,
      };
    });

    renderSurface({
      viewportMode: "CLASS_DIAGRAM",
      viewportPolicy: "readable-fit",
      shouldFocusAnchorOnLoad: true,
      fitViewPadding: 0.12,
      fitViewMaxZoom: 0.9,
      nodeViewportSize: () => ({ width: 240, height: 120 }),
    });

    act(() => {
      vi.runAllTimers();
    });
    vi.useRealTimers();

    expect(screen.getByTestId("reactflow")).toHaveAttribute("data-min-zoom", "0.54");
    expect(reactFlowSetCenterMock).toHaveBeenCalledWith(240, 156, { zoom: 0.82, duration: 0 });
    expect(reactFlowFitViewMock).not.toHaveBeenCalled();
    const viewportApplyTrace = traceSink.mock.calls
      .map(([payload]) => JSON.parse(String(payload)))
      .find((trace) => trace.event === "graphFlowSurface.viewport.apply");
    expect(viewportApplyTrace?.payload.branch).toBe("readableFit");
  });

  it("preserves the class diagram viewport when a node position changes after dragging", () => {
    installResizeObserverStub();
    vi.useFakeTimers();
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function getBoundingClientRect(this: HTMLElement) {
      if ((this as HTMLElement).dataset.testid === "graph-canvas-shell") {
        return {
          left: 0,
          top: 0,
          right: 1280,
          bottom: 720,
          width: 1280,
          height: 720,
          x: 0,
          y: 0,
          toJSON: () => undefined,
        };
      }
      return {
        left: 0,
        top: 0,
        right: 0,
        bottom: 0,
        width: 0,
        height: 0,
        x: 0,
        y: 0,
        toJSON: () => undefined,
      };
    });
    const initialNode = { ...baseNode("class:OrderService"), type: "CLASS", position: { x: 120, y: 96 } };
    const movedNode = { ...initialNode, position: { x: 420, y: 240 } };
    const renderNode = (node: LinkGraphNode) => ({
      id: node.id,
      data: { label: node.title },
      position: node.position ?? { x: 0, y: 0 },
    });
    const props: ComponentProps<typeof GraphFlowSurface> = {
      nodes: [initialNode],
      edges: [],
      flowNodes: [renderNode(initialNode)],
      flowEdges: [],
      anchorNodeId: initialNode.id,
      selectedNodeId: null,
      selectedGroupNodeIds: [],
      editable: true,
      viewportMode: "CLASS_DIAGRAM",
      viewportPolicy: "readable-fit",
      viewportResetKey: "class-diagram:stable",
      shouldFocusAnchorOnLoad: true,
      emptyState: <div>empty</div>,
      buildPaneActions: () => [],
      buildNodeActions: () => [],
      buildEdgeActions: () => [],
      onSelectNode: () => undefined,
      onSelectionGroupChange: () => undefined,
      onInspectNode: () => undefined,
      onCreateEdge: () => undefined,
      onMoveNode: () => undefined,
      onMoveNodes: () => undefined,
      nodeViewportSize: () => ({ width: 240, height: 120 }),
    };
    const { rerender } = render(<GraphFlowSurface {...props} />);

    act(() => {
      vi.runAllTimers();
    });
    expect(reactFlowSetCenterMock).toHaveBeenCalledTimes(1);

    rerender(<GraphFlowSurface {...props} nodes={[movedNode]} flowNodes={[renderNode(movedNode)]} />);
    act(() => {
      vi.runAllTimers();
    });
    vi.useRealTimers();

    expect(reactFlowSetCenterMock).toHaveBeenCalledTimes(1);
  });

  it("can keep node dragging enabled while disabling left-button pane panning and grouped dragging", () => {
    installResizeObserverStub();

    renderSurface({
      editable: false,
      layoutEditable: true,
      panOnDrag: [1],
      groupSelectionEnabled: false,
    });

    const reactFlow = screen.getByTestId("reactflow");
    expect(reactFlow).toHaveAttribute("data-nodes-draggable", "true");
    expect(reactFlow).toHaveAttribute("data-pan-on-drag", "1");
    expect(reactFlow).toHaveAttribute("data-multi-selection-key-code", "null");
    expect(reactFlow).toHaveAttribute("data-select-nodes-on-drag", "false");

    fireEvent.contextMenu(screen.getByTestId("reactflow-node-method:anchor"), {
      clientX: 360,
      clientY: 260,
    });
    expect(within(screen.getByRole("menu")).getByRole("menuitem", { name: "Node Action method:anchor" })).toBeInTheDocument();
  });

  it("passes scroll and click-distance interaction settings through to React Flow", () => {
    installResizeObserverStub();

    renderSurface({
      panOnScroll: true,
      panOnScrollMode: "vertical",
      panOnScrollSpeed: 0.8,
      zoomOnScroll: false,
      preventScrolling: false,
      nodeClickDistance: 6,
      paneClickDistance: 6,
    });

    const reactFlow = screen.getByTestId("reactflow");
    expect(reactFlow).toHaveAttribute("data-pan-on-scroll", "true");
    expect(reactFlow).toHaveAttribute("data-pan-on-scroll-mode", "vertical");
    expect(reactFlow).toHaveAttribute("data-pan-on-scroll-speed", "0.8");
    expect(reactFlow).toHaveAttribute("data-zoom-on-scroll", "false");
    expect(reactFlow).toHaveAttribute("data-prevent-scrolling", "false");
    expect(reactFlow).toHaveAttribute("data-node-click-distance", "6");
    expect(reactFlow).toHaveAttribute("data-pane-click-distance", "6");
  });

  it("keeps node selection switching and node context menus active after a layout drag", () => {
    installResizeObserverStub();
    const onSelectNode = vi.fn();
    const anchor = baseNode("method:anchor");
    const tail = { ...baseNode("method:tail"), position: { x: 420, y: 240 } };

    renderSurface({
      nodes: [anchor, tail],
      flowNodes: [anchor, tail].map((node) => ({
        id: node.id,
        data: { label: node.title },
        position: node.position ?? { x: 0, y: 0 },
      })),
      selectedNodeId: "method:anchor",
      layoutEditable: true,
      panOnDrag: [1],
      groupSelectionEnabled: false,
      nodeClickDistance: 6,
      paneClickDistance: 6,
      onSelectNode,
    });

    fireEvent.click(screen.getByTestId("reactflow-drag-node"));
    fireEvent.click(screen.getByTestId("reactflow-node-method:tail"));

    expect(onSelectNode).toHaveBeenLastCalledWith("method:tail");

    fireEvent.contextMenu(screen.getByTestId("reactflow-node-method:tail"), {
      clientX: 420,
      clientY: 240,
    });

    expect(onSelectNode).toHaveBeenLastCalledWith("method:tail");
    expect(within(screen.getByRole("menu")).getByRole("menuitem", { name: "Node Action method:tail" })).toBeInTheDocument();
  });

  it("does not crop dense current-class diagrams to only the current class on first open", () => {
    installResizeObserverStub();
    vi.useFakeTimers();
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function getBoundingClientRect(this: HTMLElement) {
      if ((this as HTMLElement).dataset.testid === "graph-canvas-shell") {
        return {
          left: 0,
          top: 0,
          right: 1280,
          bottom: 720,
          width: 1280,
          height: 720,
          x: 0,
          y: 0,
          toJSON: () => undefined,
        };
      }
      return {
        left: 0,
        top: 0,
        right: 0,
        bottom: 0,
        width: 0,
        height: 0,
        x: 0,
        y: 0,
        toJSON: () => undefined,
      };
    });
    const nodes: LinkGraphNode[] = [
      { ...baseNode("class:validator"), type: "CLASS", position: { x: 1120, y: 420 } },
      { ...baseNode("class:publisher"), type: "INTERFACE", position: { x: 1120, y: 120 } },
      { ...baseNode("class:config"), type: "CLASS", position: { x: 1880, y: 360 } },
      { ...baseNode("class:fault"), type: "CLASS", position: { x: 1880, y: 580 } },
      { ...baseNode("class:delta"), type: "CLASS", position: { x: 2460, y: 120 } },
      { ...baseNode("class:image"), type: "CLASS", position: { x: 2460, y: 340 } },
      { ...baseNode("class:version"), type: "CLASS", position: { x: 3040, y: 340 } },
    ];
    const edges: LinkGraphEdge[] = [
      {
        id: "edge:validator->publisher",
        type: "IMPLEMENTS",
        source: "class:validator",
        target: "class:publisher",
      },
      {
        id: "edge:validator->config",
        type: "USES_TYPE",
        source: "class:validator",
        target: "class:config",
      },
      {
        id: "edge:validator->fault",
        type: "USES_TYPE",
        source: "class:validator",
        target: "class:fault",
      },
      {
        id: "edge:image->version",
        type: "USES_TYPE",
        source: "class:image",
        target: "class:version",
        route: {
          sections: [
            {
              startPoint: { x: 2820, y: 400 },
              bendPoints: [{ x: 2960, y: 400 }],
              endPoint: { x: 3040, y: 400 },
            },
          ],
        },
      },
    ];

    renderSurface({
      nodes,
      edges,
      flowNodes: nodes.map((node) => ({
        id: node.id,
        data: { label: node.title },
        position: node.position ?? { x: 0, y: 0 },
      })),
      flowEdges: edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
      })),
      anchorNodeId: "class:validator",
      viewportMode: "CLASS_DIAGRAM",
      shouldFocusAnchorOnLoad: true,
      fitViewPadding: 0.12,
      fitViewMaxZoom: 0.9,
      nodeViewportSize: () => ({ width: 360, height: 160 }),
    });

    act(() => {
      vi.runAllTimers();
    });
    vi.useRealTimers();

    expect(reactFlowFitViewMock).toHaveBeenCalledWith({
      padding: 0.12,
      duration: 0,
      maxZoom: 0.9,
      includeHiddenNodes: true,
    });
    expect(reactFlowFitViewMock).toHaveBeenCalledTimes(1);
    expect(reactFlowSetCenterMock).not.toHaveBeenCalled();
  });

  it("opens large class diagrams by fitting the backend-visible relation cluster", () => {
    installResizeObserverStub();
    vi.useFakeTimers();
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function getBoundingClientRect(this: HTMLElement) {
      if ((this as HTMLElement).dataset.testid === "graph-canvas-shell") {
        return {
          left: 0,
          top: 0,
          right: 1280,
          bottom: 720,
          width: 1280,
          height: 720,
          x: 0,
          y: 0,
          toJSON: () => undefined,
        };
      }
      return {
        left: 0,
        top: 0,
        right: 0,
        bottom: 0,
        width: 0,
        height: 0,
        x: 0,
        y: 0,
        toJSON: () => undefined,
      };
    });
    const nodes: LinkGraphNode[] = [
      { ...baseNode("class:anchor"), type: "CLASS", position: { x: 120, y: 96 } },
      { ...baseNode("class:caller"), type: "CLASS", position: { x: -1320, y: 96 } },
      { ...baseNode("class:callee"), type: "CLASS", position: { x: 2040, y: 96 } },
      { ...baseNode("class:data-left"), type: "CLASS", position: { x: -1080, y: 1280 } },
      { ...baseNode("class:data-right"), type: "CLASS", position: { x: 2600, y: 1480 } },
    ];
    const edges: LinkGraphEdge[] = [
      {
        id: "edge:caller->anchor",
        type: "USES_TYPE",
        source: "class:caller",
        target: "class:anchor",
        metadata: {
          "classDiagram.relation.role": "FIELD",
          "classDiagram.relation.weight": "90",
        },
      },
      {
        id: "edge:anchor->callee",
        type: "USES_TYPE",
        source: "class:anchor",
        target: "class:callee",
        metadata: {
          "classDiagram.relation.role": "METHOD_PARAMETER",
          "classDiagram.relation.weight": "10",
        },
        route: {
          sections: [
            {
              startPoint: { x: 480, y: 226 },
              bendPoints: [{ x: 3180, y: 226 }],
              endPoint: { x: 3180, y: 226 },
            },
          ],
        },
      },
      {
        id: "edge:anchor->data-left",
        type: "USES_TYPE",
        source: "class:anchor",
        target: "class:data-left",
        metadata: {
          "classDiagram.relation.role": "METHOD_PARAMETER",
          "classDiagram.relation.weight": "10",
        },
      },
      {
        id: "edge:anchor->data-right",
        type: "USES_TYPE",
        source: "class:anchor",
        target: "class:data-right",
        metadata: {
          "classDiagram.relation.role": "METHOD_PARAMETER",
          "classDiagram.relation.weight": "10",
        },
      },
    ];

    renderSurface({
      nodes,
      edges,
      flowNodes: nodes.map((node) => ({
        id: node.id,
        data: { label: node.title },
        position: node.position ?? { x: 0, y: 0 },
      })),
      flowEdges: edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
      })),
      anchorNodeId: "class:anchor",
      viewportMode: "CLASS_DIAGRAM",
      shouldFocusAnchorOnLoad: false,
      fitViewPadding: 0.12,
      fitViewMaxZoom: 0.9,
      nodeViewportSize: () => ({ width: 360, height: 260 }),
    });

    act(() => {
      vi.runAllTimers();
    });
    vi.useRealTimers();

    expect(reactFlowFitViewMock).toHaveBeenCalledWith({
      padding: 0.12,
      duration: 0,
      maxZoom: 0.9,
      includeHiddenNodes: true,
    });
    expect(reactFlowSetCenterMock).not.toHaveBeenCalled();
  });

  it("opens project structure architecture graphs by fitting the real structure bounds without dropping below readable zoom", () => {
    installResizeObserverStub();
    vi.useFakeTimers();
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function getBoundingClientRect(this: HTMLElement) {
      if ((this as HTMLElement).dataset.testid === "graph-canvas-shell") {
        return {
          left: 0,
          top: 0,
          right: 1280,
          bottom: 720,
          width: 1280,
          height: 720,
          x: 0,
          y: 0,
          toJSON: () => undefined,
        };
      }
      return {
        left: 0,
        top: 0,
        right: 0,
        bottom: 0,
        width: 0,
        height: 0,
        x: 0,
        y: 0,
        toJSON: () => undefined,
      };
    });
    const structureNodes: LinkGraphNode[] = [
      { ...baseNode("entry:rest"), position: { x: 168, y: 170 } },
      { ...baseNode("app:orders"), position: { x: 168, y: 454 } },
      { ...baseNode("app:billing"), position: { x: 576, y: 454 } },
      { ...baseNode("app:shipping"), position: { x: 984, y: 454 } },
      { ...baseNode("app:invoice"), position: { x: 168, y: 620 } },
      { ...baseNode("app:notify"), position: { x: 576, y: 620 } },
      { ...baseNode("app:report"), position: { x: 984, y: 620 } },
    ];

    renderSurface({
      nodes: structureNodes,
      flowNodes: structureNodes.map((node) => ({
        id: node.id,
        data: { label: node.title },
        position: node.position ?? { x: 0, y: 0 },
      })),
      edges: [],
      flowEdges: [],
      anchorNodeId: "entry:rest",
      viewportMode: "ARCHITECTURE_GRAPH",
      viewportPolicy: "readable-fit",
      shouldFocusAnchorOnLoad: true,
      layoutEditable: false,
      nodeViewportSize: () => ({ width: 340, height: 116 }),
    });

    const reactFlow = screen.getByTestId("reactflow");

    act(() => {
      vi.runAllTimers();
    });
    vi.useRealTimers();

    expect(reactFlow).toHaveAttribute("data-min-zoom", "0.54");
    expect(reactFlowSetCenterMock).toHaveBeenCalledWith(746, 453, { zoom: 0.82, duration: 0 });
    expect(reactFlowFitViewMock).not.toHaveBeenCalled();
  });

  it("uses fitView for the initial flowchart viewport so branch nodes are not clipped in the hybrid graph stage", () => {
    installResizeObserverStub();
    vi.useFakeTimers();

    renderSurface({
      nodeViewportSize: () => ({ width: 240, height: 120 }),
      viewportMode: "FLOWCHART",
    });

    act(() => {
      vi.runAllTimers();
    });
    vi.useRealTimers();

    expect(reactFlowFitViewMock).toHaveBeenCalledWith({
      padding: 0.16,
      duration: 0,
      maxZoom: 1,
      includeHiddenNodes: true,
    });
    expect(reactFlowSetCenterMock).not.toHaveBeenCalled();
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

  it("summarizes graph shape traces without sending every node and edge id through the bridge", () => {
    installResizeObserverStub();
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;
    const nodes = Array.from({ length: 4 }, (_, index) => ({
      ...baseNode(`method:very-long-render-chain-node-${index}`),
      position: { x: 120 + index * 240, y: 96 },
    }));
    const edges: LinkGraphEdge[] = nodes.slice(1).map((node, index) => ({
      id: `edge:very-long-render-chain-edge-${index}`,
      type: "CALL",
      source: nodes[index]!.id,
      target: node.id,
    }));

    renderSurface({
      nodes,
      edges,
      flowNodes: nodes.map((node) => ({
        id: node.id,
        data: { label: node.title },
        position: node.position ?? { x: 0, y: 0 },
      })),
      flowEdges: edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
      })),
      anchorNodeId: nodes[0]!.id,
    });

    const graphEffectTrace = traceSink.mock.calls
      .map(([payload]) => JSON.parse(String(payload)))
      .find((trace) => trace.event === "graphFlowSurface.viewport.graphEffect");

    expect(graphEffectTrace?.payload.graphShapeSignature).toBeUndefined();
    expect(graphEffectTrace?.payload.graphShape).toEqual({
      length: expect.any(Number),
      hash: expect.any(String),
    });
    expect(JSON.stringify(graphEffectTrace)).not.toContain(
      "method:very-long-render-chain-node-0|method:very-long-render-chain-node-1",
    );
    expect(JSON.stringify(graphEffectTrace)).not.toContain("edge:very-long-render-chain-edge-2");
  });
});
