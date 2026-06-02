import type { ReactNode } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FactGraphView } from "../../../../app/views/fact/FactGraphView";
import type {
  DraftCompareProjection,
  FactGraphViewDocument,
  GraphViewPresentation,
  LinkGraphEdge,
  LinkGraphNode,
} from "../../../../app/types";
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
    header?: ReactNode;
    viewportOverlay?: (context: { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] }) => ReactNode;
    emptyState?: ReactNode;
    nodes: LinkGraphNode[];
    edges: LinkGraphEdge[];
    buildPaneActions: (context: {
      position?: { x: number; y: number };
      hasGroupedSelection: boolean;
      visibleNodeCount: number;
      close: () => void;
    }) => Array<{ id: string; onSelect: () => void }>;
  }) => {
    const formatAction = props.buildPaneActions({
      position: undefined,
      hasGroupedSelection: false,
      visibleNodeCount: props.nodes.length,
      close: () => undefined,
    }).find((action) => action.id === "format-layout");
    const viewportOverlay = props.viewportOverlay?.({
      nodes: props.nodes,
      edges: props.edges,
    });
    return (
      <div
        data-testid="graph-flow-surface"
        data-anchor={props.anchorNodeId ?? ""}
        data-editable={String(props.editable)}
        data-node-position={props.nodes[0]?.position ? `${props.nodes[0].position.x}:${props.nodes[0].position.y}` : ""}
        data-has-viewport-overlay={String(Boolean(viewportOverlay))}
      >
        {props.header}
        {viewportOverlay}
        {props.nodes.length === 0 ? props.emptyState : null}
        {props.nodes.length}:{props.edges.length}
        <button type="button" onClick={() => formatAction?.onSelect()}>
          format
        </button>
      </div>
    );
  },
}));

const factPresentation: GraphViewPresentation = {
  target: {
    nodeId: "method:submit-order",
    title: "OrderController.submit",
    subtitle: "当前方法",
    location: null,
  },
  lanes: [
    { id: "upstream", label: "上游事实", axis: "COLUMN", order: 10, role: "UPSTREAM" },
    { id: "current", label: "当前方法", axis: "COLUMN", order: 20, role: "ANCHOR" },
    { id: "downstream", label: "下游事实", axis: "COLUMN", order: 30, role: "DOWNSTREAM" },
  ],
  hiddenBuckets: [
    { id: "downstream", label: "下游事实", count: 3, nodeIds: ["method:tail"], edgeIds: [] },
  ],
  controls: {
    primaryScope: "主链",
    availableScopes: ["主链", "全部"],
    searchable: true,
    expandable: true,
  },
};

const view: FactGraphViewDocument = {
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
      },
    ],
    edges: [],
  },
  fullGraph: {
    nodes: [],
    edges: [],
  },
  anchorNodeId: "method:submit-order",
  summary: {
    anchorTitle: "OrderController.submit",
    visibleNodeCount: 1,
    fullNodeCount: 1,
  },
  presentation: factPresentation,
};

const noop = () => undefined;
const draftCompareProjection: DraftCompareProjection = {
  entryId: "draft-change-compensate",
  entryTitle: "补充失败补偿说明",
  compareGraph: view.visibleGraph,
  nodeStatuses: {
    "method:submit-order": "MODIFIED",
  },
  edgeStatuses: {},
  summary: {
    scopeNodeCount: 1,
    visibleNodeCount: 1,
    visibleEdgeCount: 0,
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
  },
};
const laidOutNodes = [
  {
    ...view.visibleGraph.nodes[0]!,
    position: { x: 480, y: 144 },
    metadata: {
      "presentation.laneId": "current",
      "presentation.role": "ANCHOR",
    },
  },
];

describe("FactGraphView", () => {
  beforeEach(() => {
    useMeasuredLayoutMock.mockReset();
    useMeasuredLayoutMock.mockReturnValue({
      nodes: laidOutNodes,
      edges: view.visibleGraph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    });
  });

  it("consumes factGraphView through measured layout output instead of passing the raw fact document nodes directly to the shared surface", () => {
    render(
      <FactGraphView
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

    expect(screen.getByTestId("fact-graph-view")).toBeInTheDocument();
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-anchor", "method:submit-order");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-editable", "true");
    expect(screen.getByTestId("graph-flow-surface")).toHaveTextContent("1:0");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-node-position", "480:144");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-has-viewport-overlay", "true");
    expect(screen.getByText("OrderController.submit")).toBeInTheDocument();
    expect(screen.getByText("1 / 1")).toBeInTheDocument();
    expect(screen.getByText("下游事实 3")).toBeInTheDocument();
    expect(screen.getByTestId("graph-canvas-lanes")).toBeInTheDocument();
    expect(screen.queryByLabelText("图阅读摘要")).not.toBeInTheDocument();
    expect(document.querySelector(".canvas-reading-summary")).not.toBeInTheDocument();
    const measuredLayoutArgs = useMeasuredLayoutMock.mock.calls.at(-1)?.[0];
    expect(useMeasuredLayoutMock).toHaveBeenCalledWith(expect.objectContaining({
      anchorNodeId: "method:submit-order",
      graph: view.visibleGraph,
    }));
    expect(measuredLayoutArgs?.nodeSizeRegistry).not.toBe(defaultNodeSizeRegistry);
  });

  it("renders a draft compare summary above the fact graph when compare annotations are active", () => {
    render(
      <FactGraphView
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
  });

  it("handles fact-graph relayout inside the view module instead of delegating back to the upstream format callback", async () => {
    const user = userEvent.setup();
    const requestRelayout = vi.fn();
    const upstreamFormatLayout = vi.fn();
    useMeasuredLayoutMock.mockReturnValue({
      nodes: laidOutNodes,
      edges: view.visibleGraph.edges,
      layoutPending: false,
      requestRelayout,
    });

    render(
      <FactGraphView
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

  it("shows a loading empty state while a non-empty fact graph is still waiting for stable layout coordinates", () => {
    useMeasuredLayoutMock.mockReturnValue({
      nodes: [],
      edges: [],
      layoutPending: true,
      requestRelayout: vi.fn(),
    });

    render(
      <FactGraphView
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

    expect(screen.getByText("正在整理链路画布")).toBeInTheDocument();
    expect(screen.queryByText("画布里还没有节点")).not.toBeInTheDocument();
  });
});
