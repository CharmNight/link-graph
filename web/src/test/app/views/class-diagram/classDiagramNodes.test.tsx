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
  it("renders a UML class box with member compartments and four side handles", () => {
    const ClassDiagramNode = CLASS_DIAGRAM_NODE_TYPES.classDiagramNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <ClassDiagramNode
        id="class:ApplicationFeedbackLevel"
        data={{
          node: {
            id: "class:ApplicationFeedbackLevel",
            type: "CLASS",
            title: "ApplicationFeedbackLevel",
            doc: "Coordinates the generated feedback level shown in the editor.",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "architecture.package": "com.example",
              "jvm.class.abstract": "true",
              "uml.field.items": "provider: TaskProvider",
              "uml.method.items": "run(): void",
            },
          },
        }}
        selected={false}
        isConnectable={false}
      />,
    );

    expect(updateNodeInternalsMock).toHaveBeenCalledWith("class:ApplicationFeedbackLevel");
    expect(screen.getByText("ApplicationFeedbackLevel")).toBeInTheDocument();
    expect(screen.getByText("<<abstract>>")).toBeInTheDocument();
    expect(screen.getByText("Coordinates the generated feedback level shown in the editor.")).toBeInTheDocument();
    expect(screen.getByText("provider: TaskProvider")).toBeInTheDocument();
    expect(screen.getByText("run(): void")).toBeInTheDocument();
    expect(screen.getAllByTestId("react-flow-handle")).toHaveLength(4);
    expect(container.querySelector('[data-handle-id="target-top"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-left"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-right"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-bottom"]')).toBeInTheDocument();
  });
});

describe("buildClassDiagramNodes", () => {
  it("uses compact class-card width and highlights the anchor lane", () => {
    const builtNodes = buildClassDiagramNodes({
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
            "layout.direction": "ANCHOR",
          },
        },
      ],
      selectedNodeId: "class:anchor",
      nodeSizeRegistry: createNodeSizeRegistry(),
    });

    expect(builtNodes[0]?.style).toMatchObject({
      width: 360,
    });
    expect(String(builtNodes[0]?.style?.border)).toContain("25, 90, 153");
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
        },
      ],
    })[0];

    expect(builtEdge).toMatchObject({
      type: "routedEdge",
      sourceHandle: "source-right",
      targetHandle: "target-left",
    });
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
    expect(String(implementsEdge?.style?.strokeDasharray)).toBe("7 5");
  });
});
