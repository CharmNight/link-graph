import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { buildResourceRelationEdges, RESOURCE_RELATION_NODE_TYPES } from "./resourceRelationNodes";

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

vi.mock("../../components/graph/nodes/ResourceRelationNodeCard", () => ({
  ResourceRelationNodeCard: () => <div data-testid="resource-node-card">resource-node</div>,
}));

describe("RESOURCE_RELATION_NODE_TYPES", () => {
  it("refreshes React Flow internals after rendering a custom resource node", () => {
    updateNodeInternalsMock.mockClear();
    const ResourceRelationNode = RESOURCE_RELATION_NODE_TYPES.resourceRelationNode as (props: Record<string, unknown>) => JSX.Element;

    render(
      <ResourceRelationNode
        id="resource:sql"
        data={{
          node: {
            id: "resource:sql",
            type: "SQL",
            title: "order_mapper.xml#insertOrder",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        }}
        selected={false}
        isConnectable={false}
      />,
    );

    expect(updateNodeInternalsMock).toHaveBeenCalledWith("resource:sql");
  });

  it("renders left and right handles for custom resource nodes so relation edges keep anchors", () => {
    const ResourceRelationNode = RESOURCE_RELATION_NODE_TYPES.resourceRelationNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <ResourceRelationNode
        data={{
          node: {
            id: "resource:sql",
            type: "SQL",
            title: "order_mapper.xml#insertOrder",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        }}
        selected={false}
        isConnectable={false}
      />,
    );

    expect(screen.getAllByTestId("react-flow-handle")).toHaveLength(2);
    expect(container.querySelector('[data-handle-id="target-left"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-right"]')).toBeInTheDocument();
  });

  it("keeps resource relation handles visibly discoverable when the node is connectable", () => {
    const ResourceRelationNode = RESOURCE_RELATION_NODE_TYPES.resourceRelationNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <ResourceRelationNode
        data={{
          node: {
            id: "resource:http",
            type: "HTTP_ENDPOINT",
            title: "GET /common/download",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector(".resource-relation-react-node")).toHaveClass("is-connectable");
    expect(container.querySelector('[data-handle-id="target-left"]')).toHaveAttribute("data-style-opacity", "0.28");
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-style-opacity", "0.28");
  });

  it("switches resource relation edges to the shared routed edge renderer when ELK route data is present", () => {
    const builtEdge = buildResourceRelationEdges({
      edges: [
        {
          id: "edge:method->resource",
          type: "ROUTES_TO",
          source: "method:anchor",
          target: "resource:http",
          route: {
            sections: [
              {
                startPoint: { x: 120, y: 96 },
                bendPoints: [{ x: 260, y: 96 }],
                endPoint: { x: 360, y: 180 },
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
            endPoint: { x: 360, y: 180 },
          }),
        ],
      },
    });
  });
});
