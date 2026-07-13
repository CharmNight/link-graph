import type { ReactNode } from "react";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FlowchartView } from "../../../../app/views/flowchart/FlowchartView";
import type { DraftCompareProjection, FlowchartViewDocument, LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import { defaultNodeSizeRegistry } from "../../../../app/graph/nodeSizeRegistry";

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
    viewportMode?: string;
    invocationExpansionFocus?: {
      expansionId: string;
      sourceNodeId: string;
      rootNodeId: string;
    } | null;
    header?: ReactNode;
    emptyState?: ReactNode;
    nodes: Array<{ id?: string; className?: string; position?: { x: number; y: number } }>;
    edges: Array<{ id: string }>;
    flowEdges: Array<{ id: string }>;
    flowNodes: Array<{ id?: string; className?: string }>;
    viewportOverlay?: (context: {
      nodes: Array<{ id?: string; className?: string; position?: { x: number; y: number } }>;
      edges: Array<{ id: string }>;
    }) => ReactNode;
    buildPaneActions: (context: {
      position?: { x: number; y: number };
      hasGroupedSelection: boolean;
      visibleNodeCount: number;
      close: () => void;
    }) => Array<{ id: string; onSelect: () => void }>;
    buildNodeActions: (context: {
      nodeId: string;
      close: () => void;
    }) => Array<{ id: string; onSelect: () => void }>;
  }) => {
    const paneActions = props.buildPaneActions({
      position: undefined,
      hasGroupedSelection: false,
      visibleNodeCount: props.nodes.length,
      close: () => undefined,
    });
    const nodeActionEntries = props.nodes.map((node) => {
      const actions = props.buildNodeActions({
        nodeId: (node as { id: string }).id,
        close: () => undefined,
      });
      return `${(node as { id: string }).id}:${actions.map((action) => action.id).join(",")}`;
    });
    const nodeActionButtons = props.nodes.flatMap((node) => {
      const nodeId = (node as { id: string }).id;
      return props.buildNodeActions({
        nodeId,
        close: () => undefined,
      }).map((action) => (
        <button
          key={`${nodeId}:${action.id}`}
          type="button"
          data-testid={`node-action-${nodeId}-${action.id}`}
          onClick={() => action.onSelect()}
        >
          {action.id}
        </button>
      ));
    });
    const formatAction = paneActions.find((action) => action.id === "format-layout");
    return (
      <div
        data-testid="graph-flow-surface"
        data-anchor={props.anchorNodeId ?? ""}
        data-editable={String(props.editable)}
        data-viewport-mode={props.viewportMode ?? ""}
        data-invocation-expansion-focus={props.invocationExpansionFocus
          ? `${props.invocationExpansionFocus.expansionId}:${props.invocationExpansionFocus.sourceNodeId}:${props.invocationExpansionFocus.rootNodeId}`
          : ""}
        data-node-position={props.nodes[0]?.position ? `${props.nodes[0].position.x}:${props.nodes[0].position.y}` : ""}
        data-node-class-names={props.flowNodes.map((node) => `${node.id ?? ""}=${node.className ?? ""}`).join("|")}
        data-pane-action-ids={paneActions.map((action) => action.id).join("|")}
        data-node-action-ids={nodeActionEntries.join("|")}
        data-edge-ids={props.edges.map((edge) => edge.id).join("|")}
        data-flow-edge-ids={props.flowEdges.map((edge) => edge.id).join("|")}
      >
        {props.header}
        {props.viewportOverlay?.({ nodes: props.nodes, edges: props.edges })}
        {props.nodes.length === 0 ? props.emptyState : null}
        {props.nodes.length}:{props.edges.length}
        {nodeActionButtons}
        <button type="button" onClick={() => formatAction?.onSelect()}>
          format
        </button>
      </div>
    );
  },
}));

const view: FlowchartViewDocument = {
  visibleGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flowchart.kind": "ENTRY",
        },
      },
      {
        id: "scope:guard",
        type: "FLOW_SCOPE",
        title: "!FileUtils.checkAllowDownload(fileName)",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flowchart.kind": "DECISION",
        },
      },
    ],
    edges: [
      {
        id: "contains-entry",
        type: "CONTAINS_FLOW",
        source: "method:submit-order",
        target: "scope:guard",
      },
      {
        id: "control-entry",
        type: "CONTROL_FLOW",
        source: "method:submit-order",
        target: "scope:guard",
      },
    ],
  },
  fullGraph: {
    nodes: [],
    edges: [],
  },
  anchorNodeId: "method:submit-order",
  summary: {
    nodeCount: 1,
    branchCount: 0,
    exceptionPathCount: 0,
  },
};

const loopSummaryView: FlowchartViewDocument = {
  visibleGraph: {
    nodes: [
      {
        id: "method:render",
        type: "METHOD",
        title: "ScopedCallChain.render",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flowchart.kind": "ENTRY",
        },
      },
      {
        id: "scope:foreach",
        type: "FLOW_SCOPE",
        title: "for (line : lines)",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flow.kind": "FOREACH",
          "flowchart.kind": "DECISION",
        },
      },
    ],
    edges: [
      {
        id: "control-entry-loop",
        type: "CONTROL_FLOW",
        source: "method:render",
        target: "scope:foreach",
      },
    ],
  },
  fullGraph: {
    nodes: [],
    edges: [],
  },
  anchorNodeId: "method:render",
  summary: {
    nodeCount: 2,
    branchCount: 1,
    exceptionPathCount: 0,
  },
};

const fidelityWarningView: FlowchartViewDocument = {
  ...view,
  summary: {
    nodeCount: 2,
    branchCount: 1,
    exceptionPathCount: 0,
    fullNodeCount: 5,
    fullEdgeCount: 4,
    hiddenNodeCount: 3,
    hiddenEdgeCount: 2,
    truncated: true,
    incompleteNodeCount: 1,
    incompleteEdgeCount: 0,
    semanticallyIncomplete: true,
    syntheticEdgeCount: 0,
    syntheticEntryEdgeCount: 0,
  },
};

const syntheticEntryView: FlowchartViewDocument = {
  ...view,
  visibleGraph: {
    nodes: view.visibleGraph.nodes,
    edges: [
      {
        id: "contains-entry",
        type: "CONTAINS_FLOW",
        source: "method:submit-order",
        target: "scope:guard",
      },
    ],
  },
  summary: {
    nodeCount: 2,
    branchCount: 1,
    exceptionPathCount: 0,
    fullNodeCount: 2,
    fullEdgeCount: 1,
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
    truncated: false,
    incompleteNodeCount: 0,
    incompleteEdgeCount: 0,
    semanticallyIncomplete: false,
    syntheticEdgeCount: 0,
    syntheticEntryEdgeCount: 0,
  },
};

const noop = () => undefined;
const draftCompareProjection: DraftCompareProjection = {
  entryId: "draft-change-compensate",
  entryTitle: "补充失败补偿说明",
  compareGraph: view.visibleGraph,
  nodeStatuses: {
    "method:submit-order": "MODIFIED",
  },
  edgeStatuses: {
    "control-entry": "MODIFIED",
  },
  summary: {
    scopeNodeCount: 1,
    visibleNodeCount: 1,
    visibleEdgeCount: 1,
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
  },
};
const laidOutNodes = [
  {
    ...view.visibleGraph.nodes[0]!,
    position: { x: 640, y: 144 },
  },
  {
    ...view.visibleGraph.nodes[1]!,
    position: { x: 640, y: 344 },
  },
];

describe("FlowchartView", () => {
  beforeEach(() => {
    useMeasuredLayoutMock.mockReset();
    useMeasuredLayoutMock.mockImplementation(({ graph }: { graph: FlowchartViewDocument["visibleGraph"] }) => ({
      nodes: laidOutNodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));
  });

  it("uses measured ELK layout output instead of passing the raw flowchart document nodes directly to the shared surface", () => {
    render(
      <FlowchartView
        view={view}
        selectedNodeId="method:submit-order"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByTestId("flowchart-view")).toBeInTheDocument();
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-anchor", "method:submit-order");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-editable", "true");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-viewport-mode", "FLOWCHART");
    expect(screen.getByTestId("graph-flow-surface")).toHaveTextContent("2:1");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-node-position", "640:144");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-pane-action-ids",
      expect.stringContaining("add-method"),
    );
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-edge-ids", "control-entry");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-flow-edge-ids", "control-entry");
    expect(screen.getByLabelText("流程图摘要")).toBeInTheDocument();
    const measuredLayoutArgs = useMeasuredLayoutMock.mock.calls.at(-1)?.[0];
    expect(useMeasuredLayoutMock).toHaveBeenCalledWith(expect.objectContaining({
      anchorNodeId: "method:submit-order",
      graph: expect.objectContaining({
        edges: [
          expect.objectContaining({ id: "control-entry", type: "CONTROL_FLOW" }),
        ],
      }),
    }));
    expect(measuredLayoutArgs?.nodeSizeRegistry).not.toBe(defaultNodeSizeRegistry);
  });

  it("renders a single-graph draft compare summary when compare annotations are active", () => {
    render(
      <FlowchartView
        view={view}
        selectedNodeId="method:submit-order"
        draftCompareProjection={draftCompareProjection}
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByLabelText("草稿对比摘要")).toBeInTheDocument();
    expect(screen.getByText("补充失败补偿说明")).toBeInTheDocument();
    expect(screen.getByText("当前对比会直接高亮修改后的真实节点和连线。")).toBeInTheDocument();
  });

  it("uses the base layout graph for ELK while still rendering the overlaid draft title", () => {
    const overlaidView: FlowchartViewDocument = {
      ...view,
      visibleGraph: {
        ...view.visibleGraph,
        nodes: view.visibleGraph.nodes.map((node) => (
          node.id === "method:submit-order"
            ? {
              ...node,
              title: "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
            }
            : node
        )),
      },
    };

    render(
      <FlowchartView
        view={overlaidView}
        layoutView={view}
        selectedNodeId="method:submit-order"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    const measuredLayoutArgs = useMeasuredLayoutMock.mock.calls.at(-1)?.[0];
    expect(measuredLayoutArgs?.graph.nodes[0]?.title).toBe("OrderController.submit");
    expect(screen.getAllByText("if (Boolean.TRUE.equals(delete) && fileExists(filePath))").length).toBeGreaterThan(0);
  });


  it("keeps draft compare overlay out of the ELK layout source so switching entries does not relayout the graph", () => {
    const projectionWithExtraNode: DraftCompareProjection = {
      ...draftCompareProjection,
      compareGraph: {
        nodes: [
          {
            ...view.visibleGraph.nodes[0]!,
            title: "OrderController.submit after draft",
          },
          view.visibleGraph.nodes[1]!,
          {
            id: "scope:extra-draft-node",
            type: "FLOW_ACTION",
            title: "Only in selected draft change",
            inputs: [],
            outputs: [],
            confidence: "SUGGESTED",
            binding: "CODE_BOUND",
            metadata: {
              "flowchart.kind": "PROCESS",
            },
          },
        ],
        edges: [
          ...view.visibleGraph.edges,
          {
            id: "control-extra-draft-node",
            type: "CONTROL_FLOW",
            source: "scope:guard",
            target: "scope:extra-draft-node",
          },
        ],
      },
      nodeStatuses: {
        "method:submit-order": "MODIFIED",
        "scope:extra-draft-node": "ADDED",
      },
      edgeStatuses: {
        "control-extra-draft-node": "ADDED",
      },
    };

    render(
      <FlowchartView
        view={view}
        layoutView={view}
        selectedNodeId="method:submit-order"
        draftCompareProjection={projectionWithExtraNode}
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    const measuredLayoutArgs = useMeasuredLayoutMock.mock.calls.at(-1)?.[0];
    expect(measuredLayoutArgs?.graph.nodes.map((node: LinkGraphNode) => node.id)).toEqual([
      "method:submit-order",
      "scope:guard",
    ]);
    expect(measuredLayoutArgs?.graph.nodes[0]?.title).toBe("OrderController.submit");
    expect(measuredLayoutArgs?.graph.edges.map((edge: LinkGraphEdge) => edge.id)).toEqual(["control-entry"]);
    expect(screen.getAllByText("OrderController.submit after draft").length).toBeGreaterThan(0);
  });

  it("handles flowchart relayout inside the view module instead of delegating back to the upstream format callback", async () => {
    const user = userEvent.setup();
    const requestRelayout = vi.fn();
    const upstreamFormatLayout = vi.fn();
    useMeasuredLayoutMock.mockImplementation(({ graph }: { graph: FlowchartViewDocument["visibleGraph"] }) => ({
      nodes: laidOutNodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout,
    }));

    render(
      <FlowchartView
        view={view}
        selectedNodeId="method:submit-order"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
        onFormatLayout={upstreamFormatLayout}
      />,
    );

    await user.click(screen.getByRole("button", { name: "format" }));

    expect(requestRelayout).toHaveBeenCalledTimes(1);
    expect(upstreamFormatLayout).not.toHaveBeenCalled();
  });


  it("keeps the method entry as the ELK layout anchor when the scene anchor is a selected flow node", () => {
    render(
      <FlowchartView
        view={{
          ...view,
          anchorNodeId: "scope:guard",
        }}
        selectedNodeId="scope:guard"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    const measuredLayoutArgs = useMeasuredLayoutMock.mock.calls.at(-1)?.[0];
    expect(measuredLayoutArgs?.anchorNodeId).toBe("method:submit-order");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-anchor", "scope:guard");
  });

  it("keeps the current-method summary pinned to the anchor method instead of replacing it with the selected flow node", () => {
    render(
      <FlowchartView
        view={view}
        selectedNodeId="scope:guard"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByText("当前方法")).toBeInTheDocument();
    expect(screen.getByText("当前选中")).toBeInTheDocument();
    expect(screen.getByText("OrderController.submit")).toBeInTheDocument();
    expect(screen.getByText("!FileUtils.checkAllowDownload(fileName)")).toBeInTheDocument();
  });

  it("keeps the current-method summary pinned to the method entry even when the anchor points at a selected flow node", () => {
    render(
      <FlowchartView
        view={{
          ...view,
          anchorNodeId: "scope:guard",
        }}
        selectedNodeId="scope:guard"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    const summary = screen.getByLabelText("流程图摘要");
    const currentMethodCard = within(summary).getByText("当前方法").closest("article");
    const currentSelectionCard = within(summary).getByText("当前选中").closest("article");
    expect(currentMethodCard).not.toBeNull();
    expect(currentSelectionCard).not.toBeNull();
    expect(within(currentMethodCard as HTMLElement).getByText("OrderController.submit")).toBeInTheDocument();
    expect(within(currentSelectionCard as HTMLElement).getByText("!FileUtils.checkAllowDownload(fileName)")).toBeInTheDocument();
  });

  it("keeps the method entry visible when the selected flow node carries the owner-method signature but the entry node itself does not", () => {
    render(
      <FlowchartView
        view={{
          ...view,
          visibleGraph: {
            ...view.visibleGraph,
            nodes: [
              {
                ...view.visibleGraph.nodes[0]!,
                metadata: {
                  "flowchart.kind": "ENTRY",
                },
              },
              {
                ...view.visibleGraph.nodes[1]!,
                metadata: {
                  "flow.kind": "IF",
                  "flowchart.kind": "DECISION",
                  "flow.ownerMethod": "com.example.OrderController.submit(java.lang.String):void",
                },
              },
            ],
          },
          anchorNodeId: "scope:guard",
        }}
        selectedNodeId="scope:guard"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    const summary = screen.getByLabelText("流程图摘要");
    const currentMethodCard = within(summary).getByText("当前方法").closest("article");
    expect(currentMethodCard).not.toBeNull();
    expect(within(currentMethodCard as HTMLElement).getByText("OrderController.submit")).toBeInTheDocument();
  });

  it("adds unified hover titles to truncated summary titles", () => {
    render(
      <FlowchartView
        view={view}
        selectedNodeId="scope:guard"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByText("OrderController.submit")).toHaveAttribute("title", "OrderController.submit");
    expect(screen.getByText("!FileUtils.checkAllowDownload(fileName)")).toHaveAttribute(
      "title",
      "!FileUtils.checkAllowDownload(fileName)",
    );
  });

  it("shows loop scopes in the branch summary when they are projected as decision nodes", () => {
    render(
      <FlowchartView
        view={loopSummaryView}
        selectedNodeId="scope:foreach"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByText("共 2 个流程节点，1 个分支判断，异常路径 0 条。")).toBeInTheDocument();
  });

  it("shows semantic incompleteness warnings without rendering truncation copy in the flowchart summary", () => {
    render(
      <FlowchartView
        view={fidelityWarningView}
        selectedNodeId="scope:guard"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByText("语义不完整")).toBeInTheDocument();
    expect(screen.queryByText("已截断")).not.toBeInTheDocument();
    expect(screen.queryByText("当前仅展示 2/5 个节点，隐藏 3 个节点、2 条边。")).not.toBeInTheDocument();
  });

  it("marks the summary when the view relies on a synthetic entry edge to connect the anchor method", () => {
    render(
      <FlowchartView
        view={syntheticEntryView}
        selectedNodeId="scope:guard"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-edge-ids",
      "flowchart-entry:method:submit-order->scope:guard",
    );
    expect(screen.getByText("含合成入口")).toBeInTheDocument();
  });

  it("offers collapse and removal on an expanded invocation source without duplicating controls on owned nodes", () => {
    const invocationView: FlowchartViewDocument = {
      ...view,
      visibleGraph: {
        nodes: [
          view.visibleGraph.nodes[0]!,
          {
            id: "invoke:create-info",
            type: "FLOW_ACTION",
            title: "systemService.createInfo()",
            signature: "com.example.SystemService.createInfo():void",
            location: "src/main/java/com/example/OrderController.java:42",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flow.kind": "INVOCATION",
              "flowchart.kind": "SUBROUTINE",
            },
          },
          {
            id: "action:expanded-save",
            type: "FLOW_ACTION",
            title: "saveInfo()",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flow.kind": "ACTION",
              "flowchart.kind": "PROCESS",
              "linkGraph.expansion.id": "invocation:expansion-1",
            },
          },
        ],
        edges: [
          {
            id: "control-invoke",
            type: "CONTROL_FLOW",
            source: "method:submit-order",
            target: "invoke:create-info",
          },
          {
            id: "control-expanded",
            type: "CONTROL_FLOW",
            source: "invoke:create-info",
            target: "action:expanded-save",
          },
        ],
      },
      fullGraph: {
        nodes: [],
        edges: [],
        invocationExpansionRegistry: {
          entries: [
            {
              expansionId: "invocation:expansion-1",
              sourceInvocationNodeId: "invoke:create-info",
              rootNodeId: "action:expanded-save",
              targetSignature: "com.example.SystemService.createInfo():void",
              createdAt: "2026-07-01T00:00:00Z",
              parentExpansionId: null,
              depth: 1,
              ownedNodeIds: ["action:expanded-save"],
              borrowedNodeIds: [],
              callEdgeIds: [],
              internalEdgeIds: ["control-expanded"],
              childExpansionIds: [],
              warnings: [],
            },
          ],
        },
      },
      anchorNodeId: "method:submit-order",
    };
    useMeasuredLayoutMock.mockImplementation(({ graph }: { graph: FlowchartViewDocument["visibleGraph"] }) => ({
      nodes: graph.nodes.map((node, index) => ({
        ...node,
        position: { x: 640, y: 144 + index * 200 },
      })),
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <FlowchartView
        view={invocationView}
        selectedNodeId="invoke:create-info"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
        onExpandInvocation={noop}
        onRemoveInvocationExpansion={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-action-ids",
      expect.stringContaining("invoke:create-info:"),
    );
    const actionIds = screen.getByTestId("graph-flow-surface").getAttribute("data-node-action-ids") ?? "";
    expect(actionIds).toContain(
      "invoke:create-info:inspect-node,open-source,beautify-node,qa-node,set-qa-anchor,format-layout,collapse-invocation-expansion,remove-invocation-expansion",
    );
    expect(actionIds).toContain("action:expanded-save:inspect-node");
    expect(actionIds).not.toContain("expand-invocation");
    expect(actionIds).not.toContain("open-invocation-expansion");
    expect(actionIds).not.toMatch(/action:expanded-save:[^|]*(?:collapse|remove)-invocation-expansion/);
    expect(actionIds).not.toContain("activate-invocation-expansion");
  });

  it("offers invocation expansion for readable projected invocation nodes and expands the original invocation", async () => {
    const user = userEvent.setup();
    const onExpandInvocation = vi.fn();
    const readableProjectionView: FlowchartViewDocument = {
      ...view,
      visibleGraph: {
        nodes: [
          view.visibleGraph.nodes[0]!,
          {
            id: "action:write-bytes",
            type: "FLOW_ACTION",
            title: "调用 FileUtils.writeBytes",
            signature: "com.example.FileUtils.writeBytes(java.lang.String,java.lang.String):void",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flow.kind": "ACTION",
              "flowchart.kind": "PROCESS",
              "flowchart.projectedFromNodeIds": "invoke:write-bytes",
            },
          },
        ],
        edges: [
          {
            id: "control-write",
            type: "CONTROL_FLOW",
            source: "method:submit-order",
            target: "action:write-bytes",
          },
        ],
      },
      fullGraph: {
        nodes: [
          view.visibleGraph.nodes[0]!,
          {
            id: "action:write-bytes",
            type: "FLOW_ACTION",
            title: "FileUtils.writeBytes(filePath, response.toString())",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flow.kind": "ACTION",
              "flowchart.kind": "PROCESS",
            },
          },
          {
            id: "invoke:write-bytes",
            type: "FLOW_ACTION",
            title: "调用 FileUtils.writeBytes",
            signature: "com.example.FileUtils.writeBytes(java.lang.String,java.lang.String):void",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flow.kind": "INVOCATION",
              "flowchart.kind": "SUBROUTINE",
            },
          },
        ],
        edges: [],
      },
      anchorNodeId: "method:submit-order",
    };
    useMeasuredLayoutMock.mockImplementation(({ graph }: { graph: FlowchartViewDocument["visibleGraph"] }) => ({
      nodes: graph.nodes.map((node, index) => ({
        ...node,
        position: { x: 640, y: 144 + index * 200 },
      })),
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <FlowchartView
        view={readableProjectionView}
        selectedNodeId="action:write-bytes"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
        onExpandInvocation={onExpandInvocation}
        onRemoveInvocationExpansion={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-action-ids",
      expect.stringContaining("action:write-bytes:"),
    );
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-action-ids",
      expect.stringContaining("expand-invocation"),
    );

    await user.click(screen.getByTestId("node-action-action:write-bytes-expand-invocation"));

    expect(onExpandInvocation).toHaveBeenCalledWith("invoke:write-bytes");
  });

  it("keeps expanded invocation batches visible even when they belong to a different owner method", () => {
    const expandedView: FlowchartViewDocument = {
      ...view,
      visibleGraph: {
        nodes: [
          {
            ...view.visibleGraph.nodes[0]!,
            signature: "com.example.OrderController.submit(java.lang.String):void",
            metadata: {
              "flowchart.kind": "ENTRY",
              "flow.ownerMethod": "com.example.OrderController.submit(java.lang.String):void",
            },
          },
          {
            id: "invoke:create-info",
            type: "FLOW_ACTION",
            title: "调用 SystemService.createInfo",
            signature: "com.example.SystemService.createInfo():void",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flow.kind": "INVOCATION",
              "flowchart.kind": "SUBROUTINE",
              "flow.ownerMethod": "com.example.OrderController.submit(java.lang.String):void",
            },
          },
          {
            id: "method:create-info",
            type: "METHOD",
            title: "SystemService.createInfo",
            signature: "com.example.SystemService.createInfo():void",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flowchart.kind": "ENTRY",
              "flow.ownerMethod": "com.example.SystemService.createInfo():void",
              "linkGraph.expansion.id": "invocation:expansion-1",
              "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
            },
          },
          {
            id: "action:save-info",
            type: "FLOW_ACTION",
            title: "saveInfo()",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flow.kind": "ACTION",
              "flowchart.kind": "PROCESS",
              "flow.ownerMethod": "com.example.SystemService.createInfo():void",
              "linkGraph.expansion.id": "invocation:expansion-1",
              "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
            },
          },
        ],
        edges: [
          {
            id: "control-current",
            type: "CONTROL_FLOW",
            source: "method:submit-order",
            target: "invoke:create-info",
          },
          {
            id: "call-expanded",
            type: "CALL",
            source: "invoke:create-info",
            target: "method:create-info",
            metadata: {
              "linkGraph.expansion.id": "invocation:expansion-1",
              "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
            },
          },
          {
            id: "control-expanded",
            type: "CONTROL_FLOW",
            source: "method:create-info",
            target: "action:save-info",
            metadata: {
              "linkGraph.expansion.id": "invocation:expansion-1",
              "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
            },
          },
        ],
      },
      anchorNodeId: "method:submit-order",
    };
    useMeasuredLayoutMock.mockImplementation(({ graph }: { graph: FlowchartViewDocument["visibleGraph"] }) => ({
      nodes: graph.nodes.map((node, index) => ({
        ...node,
        position: { x: 640, y: 144 + index * 200 },
      })),
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <FlowchartView
        view={expandedView}
        selectedNodeId="invoke:create-info"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-action-ids",
      expect.stringContaining("method:create-info:"),
    );
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-action-ids",
      expect.stringContaining("action:save-info:"),
    );
  });

  it("renders opened invocation expansion batches inside a visible subflow frame", () => {
    const expandedNodes: LinkGraphNode[] = [
      {
        ...view.visibleGraph.nodes[0]!,
        signature: "com.example.OrderController.submit(java.lang.String):void",
        metadata: {
          "flowchart.kind": "ENTRY",
          "flow.ownerMethod": "com.example.OrderController.submit(java.lang.String):void",
        },
      },
      {
        id: "action:create-info",
        type: "FLOW_ACTION",
        title: "调用 SystemService.createInfo",
        signature: "com.example.SystemService.createInfo():void",
        location: "src/main/java/com/example/OrderController.java:42",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flow.kind": "ACTION",
          "flowchart.kind": "PROCESS",
          "flow.ownerMethod": "com.example.OrderController.submit(java.lang.String):void",
          "flowchart.projectedFromNodeIds": "invoke:create-info",
        },
      },
      {
        id: "method:create-info",
        type: "METHOD",
        title: "SystemService.createInfo",
        signature: "com.example.SystemService.createInfo():void",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flowchart.kind": "ENTRY",
          "flow.ownerMethod": "com.example.SystemService.createInfo():void",
          "linkGraph.expansion.id": "invocation:expansion-1",
          "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
          "linkGraph.expansion.rootNodeId": "method:create-info",
          "linkGraph.expansion.targetSignature": "com.example.SystemService.createInfo():void",
        },
      },
      {
        id: "action:save-info",
        type: "FLOW_ACTION",
        title: "saveInfo()",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flow.kind": "ACTION",
          "flowchart.kind": "PROCESS",
          "flow.ownerMethod": "com.example.SystemService.createInfo():void",
          "linkGraph.expansion.id": "invocation:expansion-1",
          "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
          "linkGraph.expansion.rootNodeId": "method:create-info",
          "linkGraph.expansion.targetSignature": "com.example.SystemService.createInfo():void",
        },
      },
    ];
    const expandedEdges: LinkGraphEdge[] = [
      {
        id: "control-current",
        type: "CONTROL_FLOW",
        source: "method:submit-order",
        target: "action:create-info",
      },
      {
        id: "call-expanded",
        type: "CALL",
        source: "action:create-info",
        target: "method:create-info",
        metadata: {
          "linkGraph.expansion.id": "invocation:expansion-1",
          "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
        },
      },
      {
        id: "control-expanded",
        type: "CONTROL_FLOW",
        source: "method:create-info",
        target: "action:save-info",
        metadata: {
          "linkGraph.expansion.id": "invocation:expansion-1",
          "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
        },
      },
    ];
    const expandedView: FlowchartViewDocument = {
      ...view,
      visibleGraph: {
        nodes: expandedNodes,
        edges: expandedEdges,
      },
      fullGraph: {
        nodes: expandedNodes,
        edges: expandedEdges,
        invocationExpansionRegistry: {
          entries: [
            {
              expansionId: "invocation:expansion-1",
              sourceInvocationNodeId: "invoke:create-info",
              rootNodeId: "method:create-info",
              targetSignature: "com.example.SystemService.createInfo():void",
              createdAt: "2026-07-01T00:00:00Z",
              parentExpansionId: null,
              depth: 1,
              ownedNodeIds: ["method:create-info", "action:save-info"],
              borrowedNodeIds: [],
              callEdgeIds: ["call-expanded"],
              internalEdgeIds: ["control-expanded"],
              childExpansionIds: [],
              warnings: [],
            },
          ],
        },
      },
      anchorNodeId: "method:submit-order",
    };
    useMeasuredLayoutMock.mockImplementation(({ graph }: { graph: FlowchartViewDocument["visibleGraph"] }) => ({
      nodes: graph.nodes.map((node, index) => ({
        ...node,
        position: node.id === "method:create-info"
          ? { x: 760, y: 144 }
          : node.id === "action:save-info"
            ? { x: 760, y: 344 }
            : { x: 240, y: 144 + index * 160 },
      })),
      edges: graph.edges,
      layoutPending: false,
      sizeSnapshot: new Map([
        ["method:create-info", { width: 324, height: 260 }],
        ["action:save-info", { width: 324, height: 220 }],
      ]),
      requestRelayout: vi.fn(),
    }));

    render(
      <FlowchartView
        view={expandedView}
        selectedNodeId="action:create-info"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    const frame = screen.getByTestId("flowchart-invocation-expansion-frame-invocation:expansion-1");
    expect(frame).toHaveTextContent("调用展开");
    expect(frame).toHaveTextContent("调用 SystemService.createInfo");
    expect(frame).toHaveTextContent("SystemService.createInfo");
    expect(frame).toHaveAttribute(
      "aria-label",
      "调用展开 调用 SystemService.createInfo 到 SystemService.createInfo",
    );
    expect(within(frame).getByTestId("flowchart-invocation-expansion-label-invocation:expansion-1"))
      .toHaveAttribute("data-compact", "true");
    expect(frame).toHaveStyle({ height: "476px" });
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-invocation-expansion-focus",
      "invocation:expansion-1:invoke:create-info:method:create-info",
    );
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-class-names",
      expect.stringContaining("action:create-info=flowchart-rf-node kind-process is-invocation-expansion-source"),
    );
  });

  it("keeps a collapsed invocation expansion on its source node without synthetic layout nodes or call edges", async () => {
    const user = userEvent.setup();
    const onOpenInvocationExpansion = vi.fn();
    const onRemoveInvocationExpansion = vi.fn();
    const mainNodes: LinkGraphNode[] = [
      {
        ...view.visibleGraph.nodes[0]!,
        signature: "com.example.OrderController.submit(java.lang.String):void",
        metadata: {
          "flowchart.kind": "ENTRY",
          "flow.ownerMethod": "com.example.OrderController.submit(java.lang.String):void",
        },
      },
      {
        id: "invoke:create-info",
        type: "FLOW_ACTION",
        title: "调用 SystemService.createInfo",
        signature: "com.example.SystemService.createInfo():void",
        location: "src/main/java/com/example/OrderController.java:42",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flow.kind": "INVOCATION",
          "flowchart.kind": "SUBROUTINE",
          "flow.ownerMethod": "com.example.OrderController.submit(java.lang.String):void",
        },
      },
    ];
    const expansionNodes: LinkGraphNode[] = [
      {
        id: "method:create-info",
        type: "METHOD",
        title: "SystemService.createInfo",
        signature: "com.example.SystemService.createInfo():void",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flowchart.kind": "ENTRY",
          "flow.ownerMethod": "com.example.SystemService.createInfo():void",
          "linkGraph.expansion.id": "invocation:expansion-1",
        },
      },
      {
        id: "invoke:audit",
        type: "FLOW_ACTION",
        title: "auditService.audit()",
        signature: "com.example.AuditService.audit():void",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flow.kind": "INVOCATION",
          "flowchart.kind": "SUBROUTINE",
          "flow.ownerMethod": "com.example.SystemService.createInfo():void",
          "linkGraph.expansion.id": "invocation:expansion-1",
        },
      },
      {
        id: "method:audit",
        type: "METHOD",
        title: "AuditService.audit",
        signature: "com.example.AuditService.audit():void",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flowchart.kind": "ENTRY",
          "flow.ownerMethod": "com.example.AuditService.audit():void",
          "linkGraph.expansion.id": "invocation:child",
        },
      },
      {
        id: "action:audit-log",
        type: "FLOW_ACTION",
        title: "writeAuditLog()",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "flow.kind": "ACTION",
          "flowchart.kind": "PROCESS",
          "flow.ownerMethod": "com.example.AuditService.audit():void",
          "linkGraph.expansion.id": "invocation:child",
        },
      },
    ];
    const mainEdge: LinkGraphEdge = {
      id: "control-current",
      type: "CONTROL_FLOW",
      source: "method:submit-order",
      target: "invoke:create-info",
    };
    const expansionEdges: LinkGraphEdge[] = [
      {
        id: "call-expanded",
        type: "CALL",
        source: "invoke:create-info",
        target: "method:create-info",
        metadata: { "linkGraph.expansion.id": "invocation:expansion-1" },
      },
      {
        id: "control-expanded",
        type: "CONTROL_FLOW",
        source: "method:create-info",
        target: "invoke:audit",
        metadata: { "linkGraph.expansion.id": "invocation:expansion-1" },
      },
      {
        id: "call-child",
        type: "CALL",
        source: "invoke:audit",
        target: "method:audit",
        metadata: { "linkGraph.expansion.id": "invocation:child" },
      },
      {
        id: "control-child",
        type: "CONTROL_FLOW",
        source: "method:audit",
        target: "action:audit-log",
        metadata: { "linkGraph.expansion.id": "invocation:child" },
      },
    ];
    const collapsedView: FlowchartViewDocument = {
      ...view,
      visibleGraph: { nodes: mainNodes, edges: [mainEdge] },
      fullGraph: {
        nodes: [...mainNodes, ...expansionNodes],
        edges: [mainEdge, ...expansionEdges],
        invocationExpansionRegistry: {
          entries: [
            {
              expansionId: "invocation:expansion-1",
              sourceInvocationNodeId: "invoke:create-info",
              rootNodeId: "method:create-info",
              targetSignature: "com.example.SystemService.createInfo():void",
              createdAt: "2026-07-01T00:00:00Z",
              parentExpansionId: null,
              depth: 1,
              ownedNodeIds: ["method:create-info", "invoke:audit"],
              borrowedNodeIds: [],
              callEdgeIds: ["call-expanded"],
              internalEdgeIds: ["control-expanded"],
              childExpansionIds: ["invocation:child"],
              warnings: [],
            },
            {
              expansionId: "invocation:child",
              sourceInvocationNodeId: "invoke:audit",
              rootNodeId: "method:audit",
              targetSignature: "com.example.AuditService.audit():void",
              createdAt: "2026-07-01T00:01:00Z",
              parentExpansionId: "invocation:expansion-1",
              depth: 2,
              ownedNodeIds: ["method:audit", "action:audit-log"],
              borrowedNodeIds: [],
              callEdgeIds: ["call-child"],
              internalEdgeIds: ["control-child"],
              childExpansionIds: [],
              warnings: [],
            },
          ],
        },
      },
      anchorNodeId: "method:submit-order",
    };
    useMeasuredLayoutMock.mockImplementation(({ graph }: { graph: FlowchartViewDocument["visibleGraph"] }) => ({
      nodes: graph.nodes.map((node, index) => ({ ...node, position: { x: 240, y: 144 + index * 160 } })),
      edges: graph.edges,
      layoutPending: false,
      sizeSnapshot: new Map(),
      requestRelayout: vi.fn(),
    }));

    render(
      <FlowchartView
        view={collapsedView}
        selectedNodeId="invoke:create-info"
        invocationExpansionState={{
          activeExpansionId: null,
          activeExpansionPath: [],
          collapsedExpansionIds: ["invocation:expansion-1"],
          activeSiblingByParentContext: {},
          contextMode: "ACTIVE_CHAIN",
        }}
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
        onOpenInvocationExpansion={onOpenInvocationExpansion}
        onRemoveInvocationExpansion={onRemoveInvocationExpansion}
      />,
    );

    const surface = screen.getByTestId("graph-flow-surface");
    expect(surface).not.toHaveAttribute("data-node-action-ids", expect.stringContaining("expansion-block:"));
    expect(surface).not.toHaveAttribute("data-node-action-ids", expect.stringContaining("invoke:audit:"));
    expect(surface).not.toHaveAttribute("data-node-action-ids", expect.stringContaining("method:audit:"));
    expect(surface).not.toHaveAttribute("data-node-action-ids", expect.stringContaining("action:audit-log:"));
    expect(surface).not.toHaveAttribute("data-edge-ids", expect.stringContaining("call-expanded"));
    expect(surface).not.toHaveAttribute("data-edge-ids", expect.stringContaining("call-child"));
    expect(surface).not.toHaveAttribute("data-edge-ids", expect.stringContaining("control-child"));
    expect(surface).toHaveAttribute(
      "data-node-action-ids",
      expect.stringContaining("invoke:create-info:inspect-node,open-source,beautify-node,qa-node,set-qa-anchor,format-layout,open-invocation-expansion,remove-invocation-expansion"),
    );
    expect(surface).not.toHaveAttribute("data-node-action-ids", expect.stringContaining("expand-invocation"));
    expect(surface).not.toHaveAttribute("data-node-action-ids", expect.stringContaining("collapse-invocation-expansion"));

    await user.click(screen.getByTestId("node-action-invoke:create-info-open-invocation-expansion"));
    await user.click(screen.getByTestId("node-action-invoke:create-info-remove-invocation-expansion"));

    expect(onOpenInvocationExpansion).toHaveBeenCalledWith("invocation:expansion-1");
    expect(onRemoveInvocationExpansion).toHaveBeenCalledWith("invocation:expansion-1");
  });

  it("shows a loading empty state while a non-empty flowchart is still waiting for stable layout coordinates", () => {
    useMeasuredLayoutMock.mockReturnValue({
      nodes: [],
      edges: [],
      layoutPending: true,
      requestRelayout: vi.fn(),
    });

    render(
      <FlowchartView
        view={view}
        selectedNodeId="method:submit-order"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByText("正在整理流程图")).toBeInTheDocument();
    expect(screen.queryByText("当前没有可展示的流程节点")).not.toBeInTheDocument();
  });
});
