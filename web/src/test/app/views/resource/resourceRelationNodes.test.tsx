import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createNodeSizeRegistry } from "../../../../app/graph/nodeSizeRegistry";
import { buildResourceRelationEdges, buildResourceRelationNodes, RESOURCE_RELATION_NODE_TYPES } from "../../../../app/views/resource/resourceRelationNodes";

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

vi.mock("../../../../app/components/graph/nodes/ResourceRelationNodeCard", () => ({
  ResourceRelationNodeCard: ({ draftCompareStatus }: { draftCompareStatus?: string }) => (
    <div data-testid="resource-node-card" data-compare-status={draftCompareStatus ?? ""}>resource-node</div>
  ),
}));

describe("RESOURCE_RELATION_NODE_TYPES", () => {
  it("refreshes React Flow internals only after a custom resource node structure changes", () => {
    updateNodeInternalsMock.mockClear();
    const ResourceRelationNode = RESOURCE_RELATION_NODE_TYPES.resourceRelationNode as (props: Record<string, unknown>) => JSX.Element;
    const node = {
      id: "resource:sql",
      type: "SQL" as const,
      title: "order_mapper.xml#insertOrder",
      inputs: [],
      outputs: [],
      confidence: "VERIFIED" as const,
      binding: "CODE_BOUND" as const,
    };

    const { rerender } = render(
      <ResourceRelationNode
        id="resource:sql"
        data={{
          node,
        }}
        selected={false}
        isConnectable={false}
      />,
    );

    expect(updateNodeInternalsMock).not.toHaveBeenCalled();

    rerender(
      <ResourceRelationNode
        id="resource:sql"
        data={{
          node: {
            ...node,
            title: "order_mapper.xml#updateOrder",
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
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
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
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
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

  it("marks explanation focus nodes and draft change nodes with dedicated React Flow classes", () => {
    const builtNodes = buildResourceRelationNodes({
      nodes: [
        {
          id: "resource:http",
          type: "HTTP_ENDPOINT",
          title: "GET /common/download",
          inputs: [],
          outputs: [],
          confidence: "VERIFIED",
          binding: "CODE_BOUND",
        },
        {
          id: "resource:sql",
          type: "SQL",
          title: "order_mapper.xml#insertOrder",
          inputs: [],
          outputs: [],
          confidence: "VERIFIED",
          binding: "CODE_BOUND",
        },
      ],
      selectedNodeId: "resource:http",
      explanationFocusNodeId: "resource:http",
      draftChangedNodeIds: ["resource:sql"],
      nodeSizeRegistry: createNodeSizeRegistry(),
    });

    expect(builtNodes.find((node) => node.id === "resource:http")?.className ?? "").toContain("is-explanation-focus");
    expect(builtNodes.find((node) => node.id === "resource:sql")?.className ?? "").toContain("is-draft-change");
  });

  it("uses theme-aware node backgrounds in the dark graph stage instead of hardcoded light cards", () => {
    const builtNodes = buildResourceRelationNodes({
      nodes: [
        {
          id: "resource:http",
          type: "HTTP_ENDPOINT",
          title: "GET /common/download",
          inputs: [],
          outputs: [],
          confidence: "VERIFIED",
          binding: "CODE_BOUND",
        },
        {
          id: "resource:sql",
          type: "SQL",
          title: "order_mapper.xml#insertOrder",
          inputs: [],
          outputs: [],
          confidence: "VERIFIED",
          binding: "CODE_BOUND",
        },
      ],
      selectedNodeId: null,
      nodeSizeRegistry: createNodeSizeRegistry(),
    });

    for (const node of builtNodes) {
      const background = String(node.style?.background ?? "");
      expect(background).toContain("var(--panel");
      expect(background).not.toMatch(/#(?:f|fff)|rgba\(255/i);
    }
  });

  it("reuses resource node measurement reporters when only explanation focus changes", () => {
    const registry = createNodeSizeRegistry();
    const nodes = [
      {
        id: "resource:http",
        type: "HTTP_ENDPOINT" as const,
        title: "GET /common/download",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED" as const,
        binding: "CODE_BOUND" as const,
      },
      {
        id: "resource:sql",
        type: "SQL" as const,
        title: "order_mapper.xml#insertOrder",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED" as const,
        binding: "CODE_BOUND" as const,
      },
    ];

    const initialNodes = buildResourceRelationNodes({
      nodes,
      selectedNodeId: "resource:http",
      nodeSizeRegistry: registry,
    });
    const hoveredNodes = buildResourceRelationNodes({
      nodes,
      selectedNodeId: "resource:http",
      explanationFocusNodeId: "resource:sql",
      nodeSizeRegistry: registry,
    });

    expect(initialNodes[0]?.data.onMeasure).toBe(hoveredNodes[0]?.data.onMeasure);
    expect(initialNodes[1]?.data.onMeasure).toBe(hoveredNodes[1]?.data.onMeasure);
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

  it("marks resource nodes and edges with draft compare annotations when single-graph compare is active", () => {
    const builtNodes = buildResourceRelationNodes({
      nodes: [
        {
          id: "resource:http",
          type: "HTTP_ENDPOINT",
          title: "GET /common/download",
          inputs: [],
          outputs: [],
          confidence: "VERIFIED",
          binding: "CODE_BOUND",
        },
      ],
      selectedNodeId: "resource:http",
      draftCompareNodeStatuses: {
        "resource:http": "MODIFIED",
      },
      nodeSizeRegistry: createNodeSizeRegistry(),
    });
    const builtEdge = buildResourceRelationEdges({
      edges: [
        {
          id: "edge:method->resource",
          type: "ROUTES_TO",
          source: "method:anchor",
          target: "resource:http",
        },
      ],
      draftCompareEdgeStatuses: {
        "edge:method->resource": "MODIFIED",
      },
    })[0];

    expect(builtNodes[0]?.className ?? "").toContain("is-draft-compare-modified");
    expect(builtNodes[0]?.data.draftCompareStatus).toBe("MODIFIED");
    expect(builtEdge?.className ?? "").toContain("is-draft-compare-modified");
  });
});
