import type { ReactNode } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FlowchartView } from "./FlowchartView";
import type { FlowchartViewDocument } from "../../types";
import { defaultNodeSizeRegistry } from "../../graph/nodeSizeRegistry";

const { useMeasuredLayoutMock } = vi.hoisted(() => ({
  useMeasuredLayoutMock: vi.fn(),
}));

vi.mock("../../reactflow/useMeasuredLayout", () => ({
  useMeasuredLayout: useMeasuredLayoutMock,
}));

vi.mock("../../reactflow/GraphFlowSurface", () => ({
  GraphFlowSurface: (props: {
    anchorNodeId?: string | null;
    editable?: boolean;
    viewportMode?: string;
    header?: ReactNode;
    emptyState?: ReactNode;
    nodes: Array<{ position?: { x: number; y: number } }>;
    edges: Array<{ id: string }>;
    flowEdges: Array<{ id: string }>;
    buildPaneActions: (context: {
      position?: { x: number; y: number };
      hasGroupedSelection: boolean;
      visibleNodeCount: number;
      close: () => void;
    }) => Array<{ id: string; onSelect: () => void }>;
  }) => {
    const paneActions = props.buildPaneActions({
      position: undefined,
      hasGroupedSelection: false,
      visibleNodeCount: props.nodes.length,
      close: () => undefined,
    });
    const formatAction = paneActions.find((action) => action.id === "format-layout");
    return (
      <div
        data-testid="graph-flow-surface"
        data-anchor={props.anchorNodeId ?? ""}
        data-editable={String(props.editable)}
        data-viewport-mode={props.viewportMode ?? ""}
        data-node-position={props.nodes[0]?.position ? `${props.nodes[0].position.x}:${props.nodes[0].position.y}` : ""}
        data-pane-action-ids={paneActions.map((action) => action.id).join("|")}
        data-edge-ids={props.edges.map((edge) => edge.id).join("|")}
        data-flow-edge-ids={props.flowEdges.map((edge) => edge.id).join("|")}
      >
        {props.header}
        {props.nodes.length === 0 ? props.emptyState : null}
        {props.nodes.length}:{props.edges.length}
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
        certainty: "PROVEN",
        bindingStatus: "BOUND",
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
        certainty: "PROVEN",
        bindingStatus: "BOUND",
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
        certainty: "PROVEN",
        bindingStatus: "BOUND",
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
        certainty: "PROVEN",
        bindingStatus: "BOUND",
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

  it("shows truncation and semantic incompleteness warnings in the flowchart summary", () => {
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

    expect(screen.getByText("已截断")).toBeInTheDocument();
    expect(screen.getByText("语义不完整")).toBeInTheDocument();
    expect(screen.getByText("当前仅展示 2/5 个节点，隐藏 3 个节点、2 条边。")).toBeInTheDocument();
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
