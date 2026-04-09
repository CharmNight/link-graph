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
});
