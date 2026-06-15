import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createNodeSizeRegistry } from "../../../../app/graph/nodeSizeRegistry";
import { buildClassDiagramEdges, buildClassDiagramNodes, CLASS_DIAGRAM_NODE_TYPES } from "../../../../app/views/class-diagram/classDiagramNodes";

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
    Arrow: "arrow",
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

describe("CLASS_DIAGRAM_NODE_TYPES", () => {
  it("renders a UML class box with member compartments and distributed side handles", () => {
    const ClassDiagramNode = CLASS_DIAGRAM_NODE_TYPES.classDiagramNode as (props: Record<string, unknown>) => JSX.Element;
    const node = {
      id: "class:ApplicationFeedbackLevel",
      type: "CLASS" as const,
      title: "ApplicationFeedbackLevel",
      doc: "Coordinates the generated feedback level shown in the editor.",
      inputs: [],
      outputs: [],
      certainty: "PROVEN" as const,
      bindingStatus: "BOUND" as const,
      metadata: {
        "architecture.package": "com.example",
        "jvm.class.abstract": "true",
        "layout.direction": "ANCHOR",
        "uml.field.items": "provider: TaskProvider",
        "uml.method.items": "run(): void",
      },
    };

    const { container, rerender } = render(
      <ClassDiagramNode
        id="class:ApplicationFeedbackLevel"
        data={{
          node,
        }}
        selected={false}
        isConnectable={false}
      />,
    );

    expect(updateNodeInternalsMock).not.toHaveBeenCalled();
    expect(screen.getByText("ApplicationFeedbackLevel")).toBeInTheDocument();
    expect(screen.getByText("<<abstract>>")).toBeInTheDocument();
    expect(screen.getByText("当前类")).toBeInTheDocument();
    expect(screen.getByText("Coordinates the generated feedback level shown in the editor.")).toBeInTheDocument();
    expect(screen.getByText("字段")).toBeInTheDocument();
    expect(screen.getByText("provider: TaskProvider")).toBeInTheDocument();
    expect(screen.getByText("方法")).toBeInTheDocument();
    expect(screen.getByText("run(): void")).toBeInTheDocument();
    expect(screen.getAllByTestId("react-flow-handle")).toHaveLength(50);
    expect(container.querySelector('[data-handle-id="source-top"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-top"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-left"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-left"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-right"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-right"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-right-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-right-6"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-right-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-right-6"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-left-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-left-6"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-left-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-left-6"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-top-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-top-6"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-bottom-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-bottom-6"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-bottom"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-bottom"]')).toBeInTheDocument();

    rerender(
      <ClassDiagramNode
        id="class:ApplicationFeedbackLevel"
        data={{
          node: {
            ...node,
            metadata: {
              ...node.metadata,
              "uml.method.items": "run(): void\ncancel(): void",
            },
          },
        }}
        selected={false}
        isConnectable={false}
      />,
    );

    expect(updateNodeInternalsMock).toHaveBeenCalledWith("class:ApplicationFeedbackLevel");
  });

  it("renders peripheral class nodes as compact relationship summaries", () => {
    const ClassDiagramNode = CLASS_DIAGRAM_NODE_TYPES.classDiagramNode as (props: Record<string, unknown>) => JSX.Element;

    render(
      <ClassDiagramNode
        id="class:OrderRepository"
        data={{
          compact: true,
          node: {
            id: "class:OrderRepository",
            type: "CLASS",
            title: "OrderRepository",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "architecture.package": "infra",
              "presentation.role": "COLLABORATOR",
              "presentation.laneId": "collaborator",
              "uml.field.items": "jdbcTemplate: JdbcTemplate",
              "uml.method.items": "save(order): OrderId",
            },
          },
        }}
        selected={false}
        isConnectable={false}
      />,
    );

    expect(screen.getByText("OrderRepository")).toBeInTheDocument();
    expect(screen.getByText("协作对象")).toBeInTheDocument();
    expect(screen.getByText("infra")).toBeInTheDocument();
    expect(screen.getByText("save(order): OrderId")).toBeInTheDocument();
    expect(screen.queryByText("字段")).not.toBeInTheDocument();
    expect(screen.queryByText("方法")).not.toBeInTheDocument();
    expect(screen.queryByText("jdbcTemplate: JdbcTemplate")).not.toBeInTheDocument();
    expect(screen.queryByText("无")).not.toBeInTheDocument();
  });

  it("shortens peripheral method-qualified reasons while preserving the full reason as title text", () => {
    const ClassDiagramNode = CLASS_DIAGRAM_NODE_TYPES.classDiagramNode as (props: Record<string, unknown>) => JSX.Element;

    render(
      <ClassDiagramNode
        id="class:MetadataDelta"
        data={{
          compact: true,
          node: {
            id: "class:MetadataDelta",
            type: "CLASS",
            title: "MetadataDelta",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "presentation.role": "OUTPUT",
              "classDiagram.node.reason": "param onMetadataUpdate.delta",
            },
          },
        }}
        selected={false}
        isConnectable={false}
      />,
    );

    expect(screen.getByText("参数 delta")).toHaveAttribute("title", "参数 onMetadataUpdate.delta");
    expect(screen.queryByText("param onMetadataUpdate.delta")).not.toBeInTheDocument();
  });
});

describe("buildClassDiagramNodes", () => {
  it("uses compact class-card width and highlights the anchor presentation role", () => {
    const [anchorNode, collaboratorNode] = buildClassDiagramNodes({
      nodes: [
        {
          id: "class:anchor",
          type: "CLASS",
          title: "ApplicationFeedbackLevel",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "presentation.role": "ANCHOR",
            "presentation.compact": "false",
          },
        },
        {
          id: "class:collaborator",
          type: "CLASS",
          title: "OrderRepository",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "layout.direction": "ANCHOR",
            "presentation.role": "COLLABORATOR",
            "presentation.compact": "true",
          },
        },
      ],
      selectedNodeId: "class:anchor",
      nodeSizeRegistry: createNodeSizeRegistry(),
    });

    expect(anchorNode?.style).toMatchObject({
      width: 360,
    });
    expect(String(anchorNode?.style?.border)).toContain("52, 180, 255");
    expect(anchorNode?.data.compact).toBe(false);
    expect(String(collaboratorNode?.style?.border)).not.toContain("52, 180, 255");
    expect(collaboratorNode?.style).toMatchObject({
      width: 248,
    });
    expect(collaboratorNode?.data.compact).toBe(true);
  });

  it("uses theme-aware node backgrounds in the dark graph stage instead of hardcoded light cards", () => {
    const builtNodes = buildClassDiagramNodes({
      nodes: [
        {
          id: "class:anchor",
          type: "CLASS",
          title: "OrderService",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "presentation.role": "ANCHOR",
            "presentation.compact": "false",
          },
        },
        {
          id: "class:collaborator",
          type: "INTERFACE",
          title: "OrderRepository",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "presentation.role": "COLLABORATOR",
            "presentation.compact": "true",
          },
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

  it("marks UML node kind classes for visual differences", () => {
    const builtNodes = buildClassDiagramNodes({
      nodes: [
        {
          id: "class:contract",
          type: "INTERFACE",
          title: "TaskProvider",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
        },
        {
          id: "class:status",
          type: "ENUM",
          title: "TaskStatus",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
        },
      ],
      selectedNodeId: null,
      nodeSizeRegistry: createNodeSizeRegistry(),
    });

    expect(builtNodes[0]?.className).toContain("uml-kind-interface");
    expect(builtNodes[1]?.className).toContain("uml-kind-enum");
  });
});

describe("buildClassDiagramEdges", () => {
  it("passes layout handles through to React Flow edges", () => {
    const builtEdge = buildClassDiagramEdges({
      edges: [
        {
          id: "edge:anchor->dependency",
          type: "USES_TYPE",
          source: "class:anchor",
          target: "class:dependency",
          sourceHandle: "source-right",
          targetHandle: "target-left",
          route: {
            sections: [
              {
                startPoint: { x: 120, y: 96 },
                bendPoints: [{ x: 260, y: 96 }],
                endPoint: { x: 360, y: 180 },
              },
            ],
          },
          metadata: {
            "layout.route": "class-diagram-lane",
          },
        },
      ],
    })[0];

    expect(builtEdge).toMatchObject({
      type: "routedEdge",
      sourceHandle: "source-right",
      targetHandle: "target-left",
    });
    expect(builtEdge?.data).toMatchObject({
      labelVisibility: "always",
      routeMode: "stored",
      route: {
        sections: [
          expect.objectContaining({
            endPoint: { x: 360, y: 180 },
          }),
        ],
      },
    });
  });

  it("preserves globally repaired class diagram routes even when they were not produced by the manual lane router", () => {
    const builtEdge = buildClassDiagramEdges({
      edges: [
        {
          id: "edge:elk-repaired",
          type: "USES_TYPE",
          source: "class:source",
          target: "class:target",
          route: {
            sections: [
              {
                startPoint: { x: 100, y: 80 },
                bendPoints: [
                  { x: 260, y: 80 },
                  { x: 260, y: 220 },
                ],
                endPoint: { x: 420, y: 220 },
              },
            ],
          },
          metadata: {
            "layout.route": "class-diagram-elk",
            "layout.routeMode": "stored",
          },
        },
      ],
    })[0];

    expect(builtEdge?.data?.routeMode).toBe("stored");
  });

  it("uses an open UML inheritance marker for extends and dashed style for implements", () => {
    const [extendsEdge, implementsEdge] = buildClassDiagramEdges({
      edges: [
        {
          id: "edge:child->base",
          type: "EXTENDS",
          source: "class:child",
          target: "class:base",
          metadata: { "jvm.relation.kind": "EXTENDS" },
        },
        {
          id: "edge:child->contract",
          type: "IMPLEMENTS",
          source: "class:child",
          target: "class:contract",
          metadata: { "jvm.relation.kind": "IMPLEMENTS" },
        },
      ],
    });

    expect(extendsEdge?.markerEnd).toMatchObject({ type: "arrow" });
    expect(implementsEdge?.markerEnd).toMatchObject({ type: "arrow" });
    expect(String(implementsEdge?.style?.strokeDasharray)).toBe("8 5");
    expect(extendsEdge?.markerEnd).toMatchObject({ color: "#b58c55" });
  });

  it("uses UML class-diagram edge roles for color and labels", () => {
    const [realizationEdge, associationEdge, dependencyEdge] = buildClassDiagramEdges({
      edges: [
        {
          id: "edge:child->contract",
          type: "IMPLEMENTS",
          source: "class:child",
          target: "class:contract",
          metadata: {
            "jvm.relation.kind": "IMPLEMENTS",
            "uml.relation.kind": "REALIZATION",
            "uml.relation.label": "implements",
          },
        },
        {
          id: "edge:anchor->store",
          type: "USES_TYPE",
          source: "class:anchor",
          target: "class:store",
          metadata: {
            "jvm.relation.kind": "USES_TYPE",
            "uml.relation.kind": "ASSOCIATION",
            "uml.relation.label": "field repository",
          },
        },
        {
          id: "edge:anchor->entry",
          type: "USES_TYPE",
          source: "class:anchor",
          target: "class:entry",
          metadata: {
            "jvm.relation.kind": "USES_TYPE",
            "uml.relation.kind": "DEPENDENCY",
            "uml.relation.label": "param load.request",
          },
        },
      ],
    });

    expect(realizationEdge?.className).toContain("class-diagram-edge-realization");
    expect(realizationEdge?.style?.stroke).toBe("#b58c55");
    expect(realizationEdge?.style?.strokeDasharray).toBe("8 5");
    expect(associationEdge?.className).toContain("class-diagram-edge-association");
    expect(associationEdge?.style?.stroke).toBe("#4f8f72");
    expect(dependencyEdge?.className).toContain("class-diagram-edge-dependency");
    expect(dependencyEdge?.style?.stroke).toBe("#6f8fbc");
    expect(dependencyEdge?.style?.strokeDasharray).toBe("6 5");
    expect(associationEdge?.markerEnd).toMatchObject({ color: "#4f8f72" });
    expect(realizationEdge?.label).toBe("实现");
    expect(associationEdge?.label).toBe("字段 repository");
    expect(dependencyEdge?.label).toBe("参数 request");
    expect(dependencyEdge?.data?.labelTitle).toBe("参数 load.request");
  });

  it("localizes every class-diagram relation label source including aggregate detail labels", () => {
    const flowEdges = buildClassDiagramEdges({
      edges: [
        {
          id: "edge:extends",
          type: "EXTENDS",
          source: "class:child",
          target: "class:base",
          metadata: {
            "uml.relation.kind": "GENERALIZATION",
            "uml.relation.label": "extends",
          },
        },
        {
          id: "edge:implements",
          type: "IMPLEMENTS",
          source: "class:child",
          target: "class:contract",
          metadata: {
            "uml.relation.kind": "REALIZATION",
            "uml.relation.label": "implements",
          },
        },
        {
          id: "edge:field",
          type: "USES_TYPE",
          source: "class:owner",
          target: "class:repository",
          metadata: {
            "uml.relation.kind": "ASSOCIATION",
            "uml.relation.label": "field repository",
          },
        },
        {
          id: "edge:ctor",
          type: "USES_TYPE",
          source: "class:owner",
          target: "class:config",
          metadata: {
            "uml.relation.kind": "ASSOCIATION",
            "uml.relation.label": "ctor config",
          },
        },
        {
          id: "edge:call",
          type: "CALL",
          source: "class:owner",
          target: "class:gateway",
          metadata: {
            "uml.relation.kind": "DEPENDENCY",
            "uml.relation.label": "call send",
          },
        },
        {
          id: "edge:param",
          type: "USES_TYPE",
          source: "class:owner",
          target: "class:request",
          metadata: {
            "uml.relation.kind": "DEPENDENCY",
            "uml.relation.label": "param load.request",
          },
        },
        {
          id: "edge:local",
          type: "USES_TYPE",
          source: "class:owner",
          target: "class:timer",
          metadata: {
            "uml.relation.kind": "DEPENDENCY",
            "uml.relation.label": "local load.timer",
          },
        },
        {
          id: "edge:return",
          type: "USES_TYPE",
          source: "class:owner",
          target: "class:result",
          metadata: {
            "uml.relation.kind": "DEPENDENCY",
            "uml.relation.label": "return load",
          },
        },
        {
          id: "edge:throws",
          type: "USES_TYPE",
          source: "class:owner",
          target: "class:error",
          metadata: {
            "uml.relation.kind": "DEPENDENCY",
            "uml.relation.label": "throws load",
          },
        },
        {
          id: "edge:fallback",
          type: "USES_TYPE",
          source: "class:owner",
          target: "class:dependency",
          metadata: {
            "uml.relation.kind": "DEPENDENCY",
          },
        },
        {
          id: "edge:aggregate",
          type: "USES_TYPE",
          source: "class:owner",
          target: "class:metrics",
          metadata: {
            "uml.relation.kind": "ASSOCIATION",
            "uml.relation.aggregate.primaryLabel": "field metrics",
            "uml.relation.aggregate.secondaryLabels": "ctor metrics;return loadMetrics",
          },
        },
      ],
    });

    expect(flowEdges.map((edge) => edge.label)).toEqual([
      "继承",
      "实现",
      "字段 repository",
      "构造参数 config",
      "调用 send",
      "参数 request",
      "局部类型 timer",
      "返回 load",
      "抛出 load",
      "依赖",
      "字段 metrics +2",
    ]);
    expect(flowEdges.at(-1)?.data?.labelTitle).toBe("字段 metrics\n构造参数 metrics\n返回 loadMetrics");
  });

  it("de-emphasizes secondary candidate relations without changing anchor relations", () => {
    const [anchorEdge, sameLaneEdge] = buildClassDiagramEdges({
      edges: [
        {
          id: "edge:anchor->store",
          type: "USES_TYPE",
          source: "class:anchor",
          target: "class:store",
          metadata: {
            "jvm.relation.kind": "USES_TYPE",
            "layout.anchorRelation": "true",
            "layout.sameLaneRelation": "false",
          },
        },
        {
          id: "edge:store->pruner",
          type: "USES_TYPE",
          source: "class:store",
          target: "class:pruner",
          metadata: {
            "jvm.relation.kind": "USES_TYPE",
            "layout.anchorRelation": "false",
            "layout.sameLaneRelation": "true",
          },
        },
      ],
    });

    expect(anchorEdge?.style?.opacity).toBe(0.96);
    expect(anchorEdge?.style?.strokeWidth).toBe(2.8);
    expect(anchorEdge?.data?.labelVisibility).toBe("always");
    expect(sameLaneEdge?.data?.labelVisibility).toBe("focus");
    expect(sameLaneEdge?.style?.opacity).toBe(0.5);
    expect(Number(sameLaneEdge?.style?.strokeWidth)).toBeLessThan(2.8);
  });

  it("passes UML endpoint adornment and label placement data to routed edges", () => {
    const [compositionEdge, aggregationEdge] = buildClassDiagramEdges({
      edges: [
        {
          id: "edge:whole->part",
          type: "USES_TYPE",
          source: "class:whole",
          target: "class:part",
          metadata: {
            "uml.relation.kind": "COMPOSITION",
            "layout.labelPlacement": "target-stub",
          },
        },
        {
          id: "edge:owner->member",
          type: "USES_TYPE",
          source: "class:owner",
          target: "class:member",
          metadata: {
            "uml.relation.kind": "AGGREGATION",
          },
        },
      ],
    });

    expect(compositionEdge?.data).toMatchObject({
      labelPlacement: "target-stub",
      sourceAdornment: "dot",
      targetAdornment: "filled-diamond",
    });
    expect(compositionEdge?.markerEnd).toBeUndefined();
    expect(aggregationEdge?.data?.targetAdornment).toBe("diamond");
  });
});
