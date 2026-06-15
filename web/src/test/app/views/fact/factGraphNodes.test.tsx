import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createNodeSizeRegistry } from "../../../../app/graph/nodeSizeRegistry";
import { buildFactGraphEdges, buildFactGraphNodes, FACT_GRAPH_NODE_TYPES } from "../../../../app/views/fact/factGraphNodes";

const updateNodeInternalsMock = vi.fn();

vi.mock("@xyflow/react", () => ({
  Handle: ({
    id,
    type,
    position,
    style,
  }: {
    id?: string;
    type: string;
    position: string;
    style?: Record<string, string | number>;
  }) => (
    <div
      data-testid="react-flow-handle"
      data-handle-id={id}
      data-type={type}
      data-position={position}
      data-style-opacity={style?.opacity != null ? String(style.opacity) : ""}
    />
  ),
  MarkerType: {
    ArrowClosed: "arrowclosed",
  },
  Position: {
    Left: "left",
    Right: "right",
    Top: "top",
    Bottom: "bottom",
  },
  useUpdateNodeInternals: () => updateNodeInternalsMock,
}));

vi.mock("../../../../app/components/graph/nodes/FactGraphNodeCard", () => ({
  FactGraphNodeCard: ({ draftCompareStatus }: { draftCompareStatus?: string }) => (
    <div data-testid="fact-graph-node-card" data-compare-status={draftCompareStatus ?? ""}>fact-node</div>
  ),
}));

describe("FACT_GRAPH_NODE_TYPES", () => {
  it("refreshes React Flow internals only after a custom fact node structure changes", () => {
    updateNodeInternalsMock.mockClear();
    const FactGraphNode = FACT_GRAPH_NODE_TYPES.factGraphNode as (props: Record<string, unknown>) => JSX.Element;
    const node = {
      id: "method:submit-order",
      type: "METHOD" as const,
      title: "OrderService.submit",
      inputs: [],
      outputs: [],
      certainty: "PROVEN" as const,
      bindingStatus: "BOUND" as const,
    };

    const { rerender } = render(
      <FactGraphNode
        id="method:submit-order"
        data={{
          node,
          collapsed: false,
          onExpandOverflow: vi.fn(),
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(updateNodeInternalsMock).not.toHaveBeenCalled();

    rerender(
      <FactGraphNode
        id="method:submit-order"
        data={{
          node: {
            ...node,
            title: "OrderService.submitOrder",
          },
          collapsed: false,
          onExpandOverflow: vi.fn(),
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(updateNodeInternalsMock).toHaveBeenCalledWith("method:submit-order");
  });

  it("renders left and right handles for custom fact nodes so React Flow edges have stable anchors", () => {
    const FactGraphNode = FACT_GRAPH_NODE_TYPES.factGraphNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FactGraphNode
        data={{
          node: {
            id: "method:submit-order",
            type: "METHOD",
            title: "OrderService.submit",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
          collapsed: false,
          onExpandOverflow: vi.fn(),
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector(".fact-graph-react-node")).toHaveClass("is-connectable");
    expect(screen.getAllByTestId("react-flow-handle")).toHaveLength(2);
    expect(container.querySelector('[data-handle-id="target-left"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-right"]')).toBeInTheDocument();
  });

  it("keeps fact graph handles visible enough to expose drag-to-connect affordances", () => {
    const FactGraphNode = FACT_GRAPH_NODE_TYPES.factGraphNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FactGraphNode
        data={{
          node: {
            id: "method:submit-order",
            type: "METHOD",
            title: "OrderService.submit",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
          collapsed: false,
          onExpandOverflow: vi.fn(),
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector('[data-handle-id="target-left"]')).toHaveAttribute("data-style-opacity", "0.28");
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-style-opacity", "0.28");
  });

  it("marks explanation focus nodes and draft change nodes with dedicated React Flow classes", () => {
    const builtNodes = buildFactGraphNodes({
      nodes: [
        {
          id: "method:submit-order",
          type: "METHOD",
          title: "OrderService.submit",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
        },
        {
          id: "flow-action:guard",
          type: "FLOW_ACTION",
          title: "校验条件",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
        },
      ],
      selectedNodeId: "method:submit-order",
      explanationFocusNodeId: "method:submit-order",
      draftChangedNodeIds: ["flow-action:guard"],
      onExpandOverflowNode: vi.fn(),
      nodeSizeRegistry: createNodeSizeRegistry(),
    });

    expect(builtNodes.find((node) => node.id === "method:submit-order")?.className ?? "").toContain("is-explanation-focus");
    expect(builtNodes.find((node) => node.id === "flow-action:guard")?.className ?? "").toContain("is-draft-change");
  });

  it("reuses fact node measurement reporters when only explanation focus changes", () => {
    const registry = createNodeSizeRegistry();
    const nodes = [
      {
        id: "method:submit-order",
        type: "METHOD" as const,
        title: "OrderService.submit",
        inputs: [],
        outputs: [],
        certainty: "PROVEN" as const,
        bindingStatus: "BOUND" as const,
      },
      {
        id: "flow-action:guard",
        type: "FLOW_ACTION" as const,
        title: "校验条件",
        inputs: [],
        outputs: [],
        certainty: "PROVEN" as const,
        bindingStatus: "BOUND" as const,
      },
    ];

    const initialNodes = buildFactGraphNodes({
      nodes,
      selectedNodeId: "method:submit-order",
      onExpandOverflowNode: vi.fn(),
      nodeSizeRegistry: registry,
    });
    const hoveredNodes = buildFactGraphNodes({
      nodes,
      selectedNodeId: "method:submit-order",
      explanationFocusNodeId: "flow-action:guard",
      onExpandOverflowNode: vi.fn(),
      nodeSizeRegistry: registry,
    });

    expect(initialNodes[0]?.data.onMeasure).toBe(hoveredNodes[0]?.data.onMeasure);
    expect(initialNodes[1]?.data.onMeasure).toBe(hoveredNodes[1]?.data.onMeasure);
  });

  it("emphasizes the anchor role even when neighboring fact nodes share the same node type", () => {
    const builtNodes = buildFactGraphNodes({
      nodes: [
        {
          id: "method:caller",
          type: "METHOD",
          title: "OrderController.submit",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "presentation.role": "UPSTREAM",
          },
        },
        {
          id: "method:anchor",
          type: "METHOD",
          title: "OrderService.place",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "presentation.role": "ANCHOR",
          },
        },
      ],
      selectedNodeId: "method:anchor",
      onExpandOverflowNode: vi.fn(),
      nodeSizeRegistry: createNodeSizeRegistry(),
    });
    const callerStyle = builtNodes.find((node) => node.id === "method:caller")?.style;
    const anchorStyle = builtNodes.find((node) => node.id === "method:anchor")?.style;

    expect(anchorStyle).toMatchObject({
      border: expect.stringContaining("14, 139, 114"),
      boxShadow: expect.stringContaining("14, 139, 114"),
    });
    expect(anchorStyle?.boxShadow).not.toBe(callerStyle?.boxShadow);
  });

  it("uses theme-aware node backgrounds in the dark graph stage instead of hardcoded light cards", () => {
    const builtNodes = buildFactGraphNodes({
      nodes: [
        {
          id: "method:caller",
          type: "METHOD",
          title: "OrderController.submit",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: { "presentation.role": "UPSTREAM" },
        },
        {
          id: "method:anchor",
          type: "METHOD",
          title: "OrderService.place",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: { "presentation.role": "ANCHOR" },
        },
        {
          id: "flow-action:guard",
          type: "FLOW_ACTION",
          title: "校验条件",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
        },
      ],
      selectedNodeId: null,
      onExpandOverflowNode: vi.fn(),
      nodeSizeRegistry: createNodeSizeRegistry(),
    });

    for (const node of builtNodes) {
      const background = String(node.style?.background ?? "");
      expect(background).toContain("var(--panel");
      expect(background).not.toMatch(/#(?:f|fff)|rgba\(255/i);
    }
  });

  it("switches fact edges to the shared routed edge renderer when ELK route data is present", () => {
    const builtEdge = buildFactGraphEdges({
      edges: [
        {
          id: "edge:anchor->tail",
          type: "CALL",
          source: "method:anchor",
          target: "method:tail",
          route: {
            sections: [
              {
                startPoint: { x: 120, y: 96 },
                bendPoints: [{ x: 220, y: 96 }],
                endPoint: { x: 320, y: 160 },
              },
            ],
          },
        },
      ],
    })[0];

    expect(builtEdge?.type).toBe("routedEdge");
    expect(builtEdge?.data).toMatchObject({
      route: {
        sections: [
          expect.objectContaining({
            startPoint: { x: 120, y: 96 },
          }),
        ],
      },
    });
  });

  it("marks fact nodes and edges with draft compare annotations when single-graph compare is active", () => {
    const builtNodes = buildFactGraphNodes({
      nodes: [
        {
          id: "method:submit-order",
          type: "METHOD",
          title: "OrderService.submit",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
        },
      ],
      selectedNodeId: "method:submit-order",
      draftCompareNodeStatuses: {
        "method:submit-order": "MODIFIED",
      },
      onExpandOverflowNode: vi.fn(),
      nodeSizeRegistry: createNodeSizeRegistry(),
    });
    const builtEdge = buildFactGraphEdges({
      edges: [
        {
          id: "edge:anchor->tail",
          type: "CALL",
          source: "method:anchor",
          target: "method:tail",
        },
      ],
      draftCompareEdgeStatuses: {
        "edge:anchor->tail": "MODIFIED",
      },
    })[0];

    expect(builtNodes[0]?.className ?? "").toContain("is-draft-compare-modified");
    expect(builtNodes[0]?.data.draftCompareStatus).toBe("MODIFIED");
    expect(builtEdge?.className ?? "").toContain("is-draft-compare-modified");
  });
});
