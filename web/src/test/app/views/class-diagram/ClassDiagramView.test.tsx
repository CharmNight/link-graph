import type { ReactNode } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ClassDiagramView } from "../../../../app/views/class-diagram/ClassDiagramView";
import type { AsyncRequestState, ClassDiagramViewDocument, GraphViewPresentation, IndexedGraphSummary, LinkGraphEdge, LinkGraphNode } from "../../../../app/types";

const { useMeasuredLayoutMock } = vi.hoisted(() => ({
  useMeasuredLayoutMock: vi.fn(),
}));

vi.mock("../../../../app/reactflow/useMeasuredLayout", () => ({
  useMeasuredLayout: useMeasuredLayoutMock,
}));

vi.mock("../../../../app/reactflow/GraphFlowSurface", () => ({
  GraphFlowSurface: (props: {
    anchorNodeId?: string | null;
    editable?: boolean;
    layoutEditable?: boolean;
    viewportResetKey?: string | null;
    viewportPolicy?: string;
    shouldFocusAnchorOnLoad?: boolean;
    header?: ReactNode;
    emptyState?: ReactNode;
    nodes: LinkGraphNode[];
    edges: LinkGraphEdge[];
    buildNodeActions: (context: {
      nodeId: string;
      close: () => void;
    }) => Array<{ id: string; onSelect: () => void }>;
    viewportOverlay?: (context: {
      nodes: LinkGraphNode[];
      edges: LinkGraphEdge[];
    }) => ReactNode;
  }) => {
    const viewportOverlay = props.viewportOverlay?.({
      nodes: props.nodes,
      edges: props.edges,
    });
    const nodeActionEntries = props.nodes.map((node) => {
      const actions = props.buildNodeActions({
        nodeId: node.id,
        close: () => undefined,
      });
      return `${node.id}:${actions.map((action) => action.id).join(",")}`;
    });
    const nodeActionButtons = props.nodes.flatMap((node) =>
      props.buildNodeActions({
        nodeId: node.id,
        close: () => undefined,
      }).map((action) => (
        <button
          key={`${node.id}:${action.id}`}
          type="button"
          data-testid={`node-action-${node.id}-${action.id}`}
          onClick={() => action.onSelect()}
        >
          {action.id}
        </button>
      )),
    );
    return (
      <div
        data-testid="graph-flow-surface"
        data-anchor={props.anchorNodeId ?? ""}
        data-editable={String(props.editable)}
        data-layout-editable={String(props.layoutEditable)}
        data-viewport-reset-key={props.viewportResetKey ?? ""}
        data-viewport-policy={props.viewportPolicy ?? ""}
        data-should-focus-anchor-on-load={String(props.shouldFocusAnchorOnLoad)}
        data-node-ids={props.nodes.map((node) => node.id).join(",")}
        data-edge-ids={props.edges.map((edge) => edge.id).join(",")}
        data-edge-count={String(props.edges.length)}
        data-edge-labels={props.edges.map((edge) => edge.label ?? "").join("|")}
        data-node-action-ids={nodeActionEntries.join("|")}
        data-has-viewport-overlay={String(Boolean(viewportOverlay))}
      >
        {props.header}
        {viewportOverlay}
        {props.nodes.length === 0 ? props.emptyState : null}
        {nodeActionButtons}
      </div>
    );
  },
}));

const emptyPresentation: GraphViewPresentation = {
  target: {
    nodeId: "class:OrderService",
    title: "OrderService",
    subtitle: "com.example.OrderService",
    location: null,
  },
  lanes: [
    { id: "abstraction", label: "抽象与接口", axis: "ZONE", order: 10, role: "INTERFACE" },
    { id: "caller", label: "调用方", axis: "ZONE", order: 20, role: "CALLER" },
    { id: "anchor", label: "当前类", axis: "ZONE", order: 30, role: "ANCHOR" },
    { id: "collaborator", label: "协作对象", axis: "ZONE", order: 40, role: "COLLABORATOR" },
    { id: "output", label: "输出类型", axis: "ZONE", order: 50, role: "OUTPUT" },
  ],
  hiddenBuckets: [
    { id: "output", label: "输出类型", count: 2, nodeIds: ["class:OrderResult"], edgeIds: [] },
  ],
  controls: {
    primaryScope: "",
    availableScopes: [],
    searchable: true,
    expandable: true,
  },
};

const nodes: LinkGraphNode[] = [
  {
    id: "class:OrderService",
    type: "CLASS",
    title: "OrderService",
    signature: "com.example.OrderService",
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata: {
      "architecture.qualifiedName": "com.example.OrderService",
      "presentation.role": "ANCHOR",
      "presentation.laneId": "anchor",
      "presentation.compact": "false",
    },
  },
];

const view: ClassDiagramViewDocument = {
  visibleGraph: {
    nodes,
    edges: [],
  },
  fullGraph: {
    nodes,
    edges: [],
  },
  anchorNodeId: "class:OrderService",
  summary: {
    classCount: 1,
    interfaceCount: 0,
    enumCount: 0,
    annotationCount: 0,
    recordCount: 0,
    objectCount: 0,
    relationCount: 0,
    anchorTypeNodeId: "class:OrderService",
    anchorTypeTitle: "OrderService",
    anchorTypeQualifiedName: "com.example.OrderService",
  },
  presentation: emptyPresentation,
};

const noop = () => undefined;

describe("ClassDiagramView", () => {
  beforeEach(() => {
    useMeasuredLayoutMock.mockReset();
    useMeasuredLayoutMock.mockReturnValue({
      nodes,
      edges: [],
      layoutPending: false,
      requestRelayout: vi.fn(),
    });
  });

  it("adds a node context action for reopening the class diagram around the selected class", async () => {
    const onRequestClassDiagram = vi.fn();

    render(
      <ClassDiagramView
        view={view}
        selectedNodeId="class:OrderService"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onRequestClassDiagram={onRequestClassDiagram}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-action-ids",
      expect.stringContaining("open-class-diagram-anchor"),
    );

    await userEvent.click(screen.getByTestId("node-action-class:OrderService-open-class-diagram-anchor"));

    expect(onRequestClassDiagram).toHaveBeenCalledWith("class:OrderService");
  });

  it("opens class diagrams around the anchor without adding in-canvas summary chrome", () => {
    render(
      <ClassDiagramView
        view={view}
        selectedNodeId="class:OrderService"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-should-focus-anchor-on-load", "true");
    expect(screen.queryByLabelText("类图概览")).not.toBeInTheDocument();
    expect(screen.queryByText("CLASS DIAGRAM")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("类图关系图例")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("类图阅读顺序")).not.toBeInTheDocument();
    expect(screen.queryByText("1 继承 / 实现")).not.toBeInTheDocument();
    expect(screen.queryByText("更多类型")).not.toBeInTheDocument();
  });

  it("passes the backend visible class diagram to layout without creating a second frontend projection", () => {
    const repositoryNode: LinkGraphNode = {
      id: "class:OrderRepository",
      type: "CLASS",
      title: "OrderRepository",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
    };
    const paymentPortNode: LinkGraphNode = {
      id: "class:PaymentPort",
      type: "INTERFACE",
      title: "PaymentPort",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
    };
    const resultNode: LinkGraphNode = {
      id: "class:OrderResult",
      type: "RECORD",
      title: "OrderResult",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
    };
    const localTypeNode: LinkGraphNode = {
      id: "class:OrderServiceTest",
      type: "CLASS",
      title: "OrderServiceTest",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
    };
    const secondaryNode: LinkGraphNode = {
      id: "class:SecondaryMapper",
      type: "CLASS",
      title: "SecondaryMapper",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
    };
    const graphNodes = [
      nodes[0],
      repositoryNode,
      paymentPortNode,
      resultNode,
      localTypeNode,
      secondaryNode,
    ];
    const graphEdges: LinkGraphEdge[] = [
      {
        id: "implements:OrderService->PaymentPort",
        type: "USES_TYPE",
        source: "class:OrderService",
        target: "class:PaymentPort",
        metadata: {
          "classDiagram.relation.role": "IMPLEMENTS",
          "classDiagram.relation.weight": "100",
        },
      },
      {
        id: "field:OrderService->OrderRepository",
        type: "USES_TYPE",
        source: "class:OrderService",
        target: "class:OrderRepository",
        metadata: {
          "classDiagram.relation.role": "FIELD",
          "classDiagram.relation.weight": "90",
        },
      },
      {
        id: "param:OrderService->OrderResult",
        type: "USES_TYPE",
        source: "class:OrderService",
        target: "class:OrderResult",
        metadata: {
          "classDiagram.relation.role": "METHOD_RETURN",
          "classDiagram.relation.weight": "62",
        },
      },
      {
        id: "local:OrderServiceTest->OrderService",
        type: "USES_TYPE",
        source: "class:OrderServiceTest",
        target: "class:OrderService",
        metadata: {
          "classDiagram.relation.role": "LOCAL_TYPE",
          "classDiagram.relation.weight": "20",
        },
      },
      {
        id: "secondary:OrderRepository->SecondaryMapper",
        type: "USES_TYPE",
        source: "class:OrderRepository",
        target: "class:SecondaryMapper",
        metadata: {
          "classDiagram.relation.role": "METHOD_PARAMETER",
          "classDiagram.relation.weight": "25",
        },
      },
    ];
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: graph.nodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <ClassDiagramView
        view={{
          ...view,
          visibleGraph: { nodes: graphNodes, edges: graphEdges },
          fullGraph: { nodes: graphNodes, edges: graphEdges },
          summary: {
            ...view.summary,
            relationCompleteness: "COMPLETE",
          },
        }}
        selectedNodeId="class:OrderService"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-ids",
      "class:OrderService,class:OrderRepository,class:PaymentPort,class:OrderResult,class:OrderServiceTest,class:SecondaryMapper",
    );
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-edge-ids",
      "implements:OrderService->PaymentPort,field:OrderService->OrderRepository,param:OrderService->OrderResult,local:OrderServiceTest->OrderService,secondary:OrderRepository->SecondaryMapper",
    );
  });

  it("passes backend class diagram relation labels to the rendered graph surface", () => {
    const configNode: LinkGraphNode = {
      id: "class:KafkaConfig",
      type: "CLASS",
      title: "KafkaConfig",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "presentation.role": "COLLABORATOR",
        "presentation.laneId": "collaborator",
      },
    };
    const fieldEdge: LinkGraphEdge = {
      id: "edge:validator->config",
      type: "USES_TYPE",
      source: "class:OrderService",
      target: "class:KafkaConfig",
      label: "dependency",
      metadata: {
        "uml.relation.kind": "DEPENDENCY",
        "uml.relation.label": "dependency",
        "classDiagram.relation.role": "FIELD",
        "classDiagram.relation.label": "field config",
        "classDiagram.relation.weight": "90",
      },
    };
    useMeasuredLayoutMock.mockReturnValue({
      nodes: [...nodes, configNode],
      edges: [fieldEdge],
      layoutPending: false,
      requestRelayout: vi.fn(),
    });

    render(
      <ClassDiagramView
        view={{
          ...view,
          visibleGraph: {
            nodes: [...nodes, configNode],
            edges: [fieldEdge],
          },
          fullGraph: {
            nodes: [...nodes, configNode],
            edges: [fieldEdge],
          },
        }}
        selectedNodeId="class:OrderService"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-edge-labels", "field config");
  });

  it("renders class diagram through the shared graph shell toolbar without canvas zone backgrounds", async () => {
    const onSelectNode = vi.fn();
    const onRequestClassDiagramWithOptions = vi.fn();
    const contractNode: LinkGraphNode = {
      id: "class:PaymentPort",
      type: "INTERFACE",
      title: "PaymentPort",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "presentation.role": "INTERFACE",
        "presentation.laneId": "abstraction",
      },
      position: { x: 40, y: 40 },
    };
    const callerNode: LinkGraphNode = {
      id: "class:OrderController",
      type: "CLASS",
      title: "OrderController",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "presentation.role": "CALLER",
        "presentation.laneId": "caller",
      },
      position: { x: 80, y: 260 },
    };
    const collaboratorNode: LinkGraphNode = {
      id: "class:OrderRepository",
      type: "CLASS",
      title: "OrderRepository",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "presentation.role": "COLLABORATOR",
        "presentation.laneId": "collaborator",
      },
      position: { x: 520, y: 260 },
    };
    const outputNode: LinkGraphNode = {
      id: "class:OrderResult",
      type: "RECORD",
      title: "OrderResult",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "presentation.role": "OUTPUT",
        "presentation.laneId": "output",
      },
      position: { x: 880, y: 260 },
    };
    const laidOutNodes = [
      { ...contractNode },
      { ...callerNode },
      { ...nodes[0], position: { x: 360, y: 260 } },
      { ...collaboratorNode },
      { ...outputNode },
    ];
    useMeasuredLayoutMock.mockReturnValue({
      nodes: laidOutNodes,
      edges: [],
      layoutPending: false,
      requestRelayout: vi.fn(),
    });

    render(
      <ClassDiagramView
        view={{
          ...view,
          visibleGraph: { nodes: laidOutNodes, edges: [] },
          fullGraph: { nodes: [...laidOutNodes, { ...outputNode, id: "class:HiddenResult", title: "HiddenResult" }], edges: [] },
        }}
        selectedNodeId="class:OrderService"
        onSelectNode={onSelectNode}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onRequestClassDiagramWithOptions={onRequestClassDiagramWithOptions}
      />,
    );

    expect(screen.getByText("OrderService")).toBeInTheDocument();
    expect(screen.getByText("com.example.OrderService")).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "范围" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "当前类" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "邻近类" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("折叠内容")).toHaveTextContent("输出类型 2");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-has-viewport-overlay", "false");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-ids",
      "class:PaymentPort,class:OrderController,class:OrderService,class:OrderRepository,class:OrderResult",
    );
    expect(screen.queryByTestId("class-diagram-zone-overlay")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "定位" }));
    expect(onSelectNode).toHaveBeenCalledWith("class:OrderService");

    await userEvent.click(screen.getByRole("button", { name: "展开" }));
    expect(onRequestClassDiagramWithOptions).toHaveBeenCalledWith("class:OrderService", {
      neighborhoodLimit: 48,
      memberLimit: 5,
    });
  });

  it("resets the viewport when class diagram completeness or visible shape changes", () => {
    const { rerender } = render(
      <ClassDiagramView
        view={{
          ...view,
          summary: {
            ...view.summary,
            relationCompleteness: "STRUCTURE_ONLY",
          },
        }}
        selectedNodeId="class:OrderService"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-viewport-reset-key",
      "STRUCTURE_ONLY:1:0:class:OrderService",
    );

    useMeasuredLayoutMock.mockReturnValue({
      nodes,
      edges: [
        {
          id: "edge:OrderService->OrderRepository",
          type: "USES_TYPE",
          source: "class:OrderService",
          target: "class:OrderService",
        },
      ],
      layoutPending: false,
      requestRelayout: vi.fn(),
    });

    rerender(
      <ClassDiagramView
        view={{
          ...view,
          summary: {
            ...view.summary,
            relationCompleteness: "COMPLETE",
          },
        }}
        selectedNodeId="class:OrderService"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-viewport-reset-key",
      "COMPLETE:1:1:class:OrderService",
    );
  });

  it("passes backend class diagram edges without creating frontend aggregate edge ids", () => {
    const edges: LinkGraphEdge[] = [
      {
        id: "edge:OrderService->OrderRepository:field",
        type: "USES_TYPE",
        source: "class:OrderService",
        target: "class:OrderRepository",
        label: "uses",
        metadata: {
          "jvm.relation.kind": "USES_TYPE",
          "uml.relation.kind": "ASSOCIATION",
          "uml.relation.label": "field repository",
        },
      },
      {
        id: "edge:OrderService->OrderRepository:method",
        type: "USES_TYPE",
        source: "class:OrderService",
        target: "class:OrderRepository",
        label: "uses",
        metadata: {
          "jvm.relation.kind": "USES_TYPE",
          "uml.relation.kind": "DEPENDENCY",
          "uml.relation.label": "param load.request",
        },
      },
    ];
    const repositoryNode: LinkGraphNode = {
      id: "class:OrderRepository",
      type: "CLASS",
      title: "OrderRepository",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
    };
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: graph.nodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <ClassDiagramView
        view={{
          ...view,
          visibleGraph: {
            nodes: [...nodes, repositoryNode],
            edges,
          },
          fullGraph: {
            nodes: [...nodes, repositoryNode],
            edges,
          },
          summary: {
            ...view.summary,
            relationCompleteness: "COMPLETE",
          },
        }}
        selectedNodeId="class:OrderService"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-edge-count", "2");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-edge-ids",
      "edge:OrderService->OrderRepository:field,edge:OrderService->OrderRepository:method",
    );
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-edge-labels", "field repository|param request");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-viewport-reset-key",
      "COMPLETE:2:2:class:OrderService",
    );
  });

  it("does not render fullGraph nodes when the backend indexed window has nodes but visibleGraph is empty", () => {
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: graph.nodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));
    const fallbackView: ClassDiagramViewDocument = {
      ...view,
      visibleGraph: { nodes: [], edges: [] },
      fullGraph: { nodes, edges: [] },
      summary: {
        ...view.summary,
        indexed: indexedSummary(1, 1),
      },
    };

    render(
      <ClassDiagramView
        view={fallbackView}
        selectedNodeId="class:OrderService"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-node-ids", "");
    expect(screen.queryByText("类图数据已返回，但当前画布没有可渲染节点")).not.toBeInTheDocument();
    expect(screen.getByText("索引完成，但当前项目范围没有可展示的类关系")).toBeInTheDocument();
  });

  it("does not let stale hidden ids filter every class diagram node out of the canvas", () => {
    render(
      <ClassDiagramView
        view={view}
        selectedNodeId="class:OrderService"
        hiddenNodeIds={["class:OrderService"]}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-node-ids", "class:OrderService");
  });

  it("uses readable-fit for class diagrams so the default viewport preserves readable labels", () => {
    render(
      <ClassDiagramView
        view={view}
        selectedNodeId="class:OrderService"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-viewport-policy", "readable-fit");
  });

  it("uses the indexed request lifecycle for empty class diagram states", () => {
    useMeasuredLayoutMock.mockReturnValue({
      nodes: [],
      edges: [],
      layoutPending: false,
      requestRelayout: vi.fn(),
    });
    const emptyView: ClassDiagramViewDocument = {
      ...view,
      visibleGraph: { nodes: [], edges: [] },
      fullGraph: { nodes: [], edges: [] },
      anchorNodeId: null,
      summary: {
        ...view.summary,
        indexed: indexedSummary(0, 0),
      },
    };
    const { rerender } = render(
      <ClassDiagramView
        view={emptyView}
        selectedNodeId={null}
        indexedGraphRequestStates={{
          CLASS_DIAGRAM: requestState("RUNNING", "正在构建项目类图。"),
        }}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByText("正在构建项目类图")).toBeInTheDocument();
    expect(screen.queryByText("正在构建项目类图。")).not.toBeInTheDocument();
    expect(document.querySelector(".canvas-empty-state .muted")).not.toBeInTheDocument();

    rerender(
      <ClassDiagramView
        view={emptyView}
        selectedNodeId={null}
        indexedGraphRequestStates={{
          CLASS_DIAGRAM: requestState("FAILED", null, "类图索引失败"),
        }}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByText("类图加载失败")).toBeInTheDocument();
    expect(screen.queryByText("类图索引失败")).not.toBeInTheDocument();
    expect(document.querySelector(".canvas-empty-state .muted")).not.toBeInTheDocument();

    rerender(
      <ClassDiagramView
        view={emptyView}
        selectedNodeId={null}
        indexedGraphRequestStates={{
          CLASS_DIAGRAM: requestState("SUCCEEDED"),
        }}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByText("索引完成，但当前项目范围没有可展示的类关系")).toBeInTheDocument();
  });

  it("does not render structure-only class diagrams as the current graph while complete relations are still loading", () => {
    const repositoryNode: LinkGraphNode = {
      id: "class:OrderRepository",
      type: "CLASS",
      title: "OrderRepository",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
    };
    const structureOnlyView: ClassDiagramViewDocument = {
      ...view,
      visibleGraph: {
        nodes: [...nodes, repositoryNode],
        edges: [
          {
            id: "edge:OrderService->OrderRepository",
            type: "USES_TYPE",
            source: "class:OrderService",
            target: "class:OrderRepository",
          },
        ],
      },
      fullGraph: {
        nodes: [...nodes, repositoryNode],
        edges: [
          {
            id: "edge:OrderService->OrderRepository",
            type: "USES_TYPE",
            source: "class:OrderService",
            target: "class:OrderRepository",
          },
        ],
      },
      summary: {
        ...view.summary,
        relationCompleteness: "STRUCTURE_ONLY",
      },
    };
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: graph.nodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <ClassDiagramView
        view={structureOnlyView}
        selectedNodeId="class:OrderService"
        indexedGraphRequestStates={{
          CLASS_DIAGRAM: requestState("RUNNING", "正在补齐完整关系。"),
        }}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-node-ids", "");
    expect(screen.getByText("正在补齐类图关系")).toBeInTheDocument();
  });
});

function requestState(
  phase: AsyncRequestState["phase"],
  statusMessage: string | null = null,
  errorMessage: string | null = null,
): AsyncRequestState {
  return {
    phase,
    statusMessage,
    errorMessage,
  };
}

function indexedSummary(visibleNodeCount: number, candidateNodeCount: number): IndexedGraphSummary {
  return {
    view: "CLASS_DIAGRAM",
    anchorKind: null,
    anchorNodeId: null,
    anchorTitle: null,
    anchorQualifiedName: null,
    scopeKind: "PROJECT",
    scopeLabel: "Project",
    relationKinds: [],
    depth: 1,
    projectNodeCount: candidateNodeCount,
    projectClassCount: candidateNodeCount,
    externalNodeCount: 0,
    jdkNodeCount: 0,
    scopedNodeCount: candidateNodeCount,
    visibleNodeCount,
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
    candidateNodeCount,
    candidateEdgeCount: 0,
    truncated: false,
    completeness: "Interactive",
    cacheState: "REUSED_FULL_INDEX",
  };
}
