import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { buildFactGraphEdges, buildFactGraphNodes, FACT_GRAPH_NODE_TYPES } from "./factGraphNodes";

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

vi.mock("../../components/graph/nodes/FactGraphNodeCard", () => ({
  FactGraphNodeCard: () => <div data-testid="fact-graph-node-card">fact-node</div>,
}));

describe("FACT_GRAPH_NODE_TYPES", () => {
  it("refreshes React Flow internals after rendering a custom fact node", () => {
    updateNodeInternalsMock.mockClear();
    const FactGraphNode = FACT_GRAPH_NODE_TYPES.factGraphNode as (props: Record<string, unknown>) => JSX.Element;

    render(
      <FactGraphNode
        id="method:submit-order"
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
});
