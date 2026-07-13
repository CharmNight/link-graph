import type { ReactNode } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ResourceRelationView } from "../../../../app/views/resource/ResourceRelationView";
import type { DraftCompareProjection, ResourceRelationViewDocument } from "../../../../app/types";
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
    emptyState?: ReactNode;
    nodes: Array<{ position?: { x: number; y: number } }>;
    edges: Array<unknown>;
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
    return (
      <div
        data-testid="graph-flow-surface"
        data-anchor={props.anchorNodeId ?? ""}
        data-editable={String(props.editable)}
        data-node-position={props.nodes[0]?.position ? `${props.nodes[0].position.x}:${props.nodes[0].position.y}` : ""}
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

const view: ResourceRelationViewDocument = {
  visibleGraph: {
    nodes: [
      {
        id: "sql:insert-order",
        type: "SQL",
        title: "order_mapper.xml#insertOrder",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        metadata: {
          "resource.lane": "DATA",
        },
      },
    ],
    edges: [],
  },
  fullGraph: {
    nodes: [],
    edges: [],
  },
  anchorNodeId: "sql:insert-order",
  summary: {
    visibleNodeCount: 1,
    relationCount: 0,
    resourceCount: 1,
    fallbackReason: "NO_BINDING_RELATIONS",
    laneCounts: {
      DATA: 1,
    },
  },
};

const noop = () => undefined;
const draftCompareProjection: DraftCompareProjection = {
  entryId: "draft-change-compensate",
  entryTitle: "补充失败补偿说明",
  compareGraph: view.visibleGraph,
  nodeStatuses: {
    "sql:insert-order": "MODIFIED",
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
    position: { x: 920, y: 184 },
  },
];

describe("ResourceRelationView", () => {
  beforeEach(() => {
    useMeasuredLayoutMock.mockReset();
    useMeasuredLayoutMock.mockReturnValue({
      nodes: laidOutNodes,
      edges: view.visibleGraph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    });
  });

  it("uses measured ELK layout output instead of passing the raw resource document nodes directly to the shared surface", () => {
    render(
      <ResourceRelationView
        view={view}
        selectedNodeId="sql:insert-order"
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

    expect(screen.getByTestId("resource-relation-view")).toBeInTheDocument();
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-anchor", "sql:insert-order");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-editable", "true");
    expect(screen.getByTestId("graph-flow-surface")).toHaveTextContent("1:0");
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-node-position", "920:184");
    expect(screen.getByLabelText("资源关系摘要")).toBeInTheDocument();
    const measuredLayoutArgs = useMeasuredLayoutMock.mock.calls.at(-1)?.[0];
    expect(useMeasuredLayoutMock).toHaveBeenCalledWith(expect.objectContaining({
      anchorNodeId: "sql:insert-order",
      graph: view.visibleGraph,
    }));
    expect(measuredLayoutArgs?.nodeSizeRegistry).not.toBe(defaultNodeSizeRegistry);
  });

  it("renders a draft compare summary above the resource view when compare annotations are active", () => {
    render(
      <ResourceRelationView
        view={view}
        selectedNodeId="sql:insert-order"
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

  it("handles resource view relayout inside the view module instead of delegating back to the upstream format callback", async () => {
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
      <ResourceRelationView
        view={view}
        selectedNodeId="sql:insert-order"
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

  it("shows a loading empty state while a non-empty resource graph is still waiting for stable layout coordinates", () => {
    useMeasuredLayoutMock.mockReturnValue({
      nodes: [],
      edges: [],
      layoutPending: true,
      requestRelayout: vi.fn(),
    });

    render(
      <ResourceRelationView
        view={view}
        selectedNodeId="sql:insert-order"
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

    expect(screen.getByText("正在整理资源关系")).toBeInTheDocument();
    expect(screen.queryByText("当前没有可展示的资源关系")).not.toBeInTheDocument();
  });
});
