import type { ReactNode } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ArchitectureGraphView } from "../../../../app/views/architecture/ArchitectureGraphView";
import type { ArchitectureGraphViewDocument, AsyncRequestState, GraphViewPresentation, LinkGraphEdge, LinkGraphNode } from "../../../../app/types";

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
    viewportPolicy?: "fit" | "readable-fit";
    fitViewPadding?: number;
    fitViewMaxZoom?: number;
    showViewportControls?: boolean;
    showLocateAnchorButton?: boolean;
    header?: ReactNode;
    viewportOverlay?: (context: { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] }) => ReactNode;
    emptyState?: ReactNode;
    nodes: LinkGraphNode[];
    edges: LinkGraphEdge[];
    flowNodes?: Array<{ id: string; type?: string; className?: string }>;
    flowEdges?: Array<{ id: string; data?: { labelVisibility?: string } }>;
    viewportResetKey?: string | null;
    buildNodeActions: (context: {
      nodeId: string;
      close: () => void;
    }) => Array<{ id: string; onSelect: () => void }>;
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
        data-viewport-policy={props.viewportPolicy ?? ""}
        data-node-ids={props.nodes.map((node) => node.id).join("|")}
        data-edge-ids={props.edges.map((edge) => edge.id).join("|")}
        data-viewport-reset-key={props.viewportResetKey ?? ""}
        data-node-action-ids={nodeActionEntries.join("|")}
        data-flow-node-classnames={(props.flowNodes ?? []).map((node) => `${node.id}:${node.className ?? ""}`).join("|")}
        data-flow-node-types={(props.flowNodes ?? []).map((node) => `${node.id}:${node.type ?? ""}`).join("|")}
        data-flow-edge-label-visibility={(props.flowEdges ?? []).map((edge) => `${edge.id}:${edge.data?.labelVisibility ?? ""}`).join("|")}
        data-has-viewport-overlay={String(Boolean(viewportOverlay))}
        data-fit-view-padding={String(props.fitViewPadding ?? "")}
        data-fit-view-max-zoom={String(props.fitViewMaxZoom ?? "")}
        data-show-viewport-controls={String(props.showViewportControls ?? true)}
        data-show-locate-anchor-button={String(props.showLocateAnchorButton ?? true)}
      >
        {props.header}
        {viewportOverlay}
        {props.nodes.length === 0 ? props.emptyState : null}
        {props.nodes.length}:{props.edges.length}
        {nodeActionButtons}
      </div>
    );
  },
}));

const emptyPresentation: GraphViewPresentation = {
  target: {
    nodeId: "layer:api",
    title: "项目架构",
    subtitle: "Order context",
    location: null,
  },
  lanes: [
    { id: "entry", label: "入口层", axis: "COLUMN", order: 0, role: "ENTRY" },
    { id: "application", label: "应用层", axis: "COLUMN", order: 1, role: "APPLICATION" },
    { id: "domain", label: "领域层", axis: "COLUMN", order: 2, role: "DOMAIN" },
    { id: "data", label: "基础设施", axis: "COLUMN", order: 3, role: "DATA" },
  ],
  hiddenBuckets: [
    { id: "external", label: "三方依赖", count: 2, nodeIds: [], edgeIds: [] },
    { id: "jdk", label: "JDK", count: 1, nodeIds: [], edgeIds: [] },
  ],
  controls: {
    primaryScope: "组件",
    availableScopes: ["组件", "包", "类"],
    searchable: true,
    expandable: true,
  },
};

const nodes: LinkGraphNode[] = [
  architectureNode({
    id: "layer:api",
    type: "LAYER",
    title: "API",
    nodeRole: "API",
    presentationRole: "ENTRY",
    presentationLaneId: "entry",
    layerKind: "PROJECT_SOURCE",
    memberClassCount: "2",
  }),
  architectureNode({
    id: "service:order",
    type: "SERVICE",
    title: "OrderService",
    nodeRole: "SERVICE",
    presentationRole: "APPLICATION",
    presentationLaneId: "application",
    memberClassCount: "5",
  }),
  architectureNode({
    id: "component:domain",
    type: "COMPONENT",
    title: "OrderDomain",
    nodeRole: "SERVICE",
    presentationRole: "DOMAIN",
    presentationLaneId: "domain",
    memberClassCount: "3",
  }),
  architectureNode({
    id: "resource:application.yml",
    type: "RESOURCE",
    title: "application.yml",
    nodeRole: "RESOURCE",
    presentationRole: "DATA",
    presentationLaneId: "data",
    layerKind: "RESOURCE",
    memberResourceCount: "1",
  }),
];

const edges: LinkGraphEdge[] = [
  {
    id: "edge:api->service",
    type: "CALL",
    source: "layer:api",
    target: "service:order",
    metadata: {
      "jvm.relation.kind": "CALLS",
      "jvm.relation.confidence": "PROVEN",
      "indexed.sourceCount": "3",
    },
  },
  {
    id: "edge:service->resource",
    type: "BINDS_CONFIG",
    source: "component:domain",
    target: "resource:application.yml",
    metadata: {
      "jvm.relation.kind": "RESOURCE_BINDS",
      "jvm.relation.confidence": "RULE_INFERRED",
      "indexed.sourceCount": "1",
    },
  },
];

const view: ArchitectureGraphViewDocument = {
  visibleGraph: {
    nodes,
    edges,
  },
  fullGraph: {
    nodes,
    edges,
  },
  anchorNodeId: "layer:api",
  summary: {
    moduleCount: 0,
    packageCount: 0,
    serviceCount: 1,
    resourceCount: 1,
    layerCount: 1,
    relationCount: 2,
    classCount: 7,
    indexed: {
      view: "ARCHITECTURE",
      anchorNodeId: "layer:api",
      anchorTitle: "API",
      scopeKind: "PROJECT",
      scopeLabel: "整个项目",
      relationKinds: [],
      depth: 2,
      projectNodeCount: 2,
      projectClassCount: 7,
      externalNodeCount: 0,
      jdkNodeCount: 0,
    scopedNodeCount: 4,
    visibleNodeCount: 4,
      hiddenNodeCount: 0,
      hiddenEdgeCount: 0,
      candidateNodeCount: 4,
      candidateEdgeCount: 2,
      truncated: false,
      completeness: "COMPLETE",
      cacheState: "HIT",
      collapsedLayerCounts: {
        projectSource: 0,
        resource: 0,
      },
    },
  },
  presentation: emptyPresentation,
};

const noop = () => undefined;

describe("ArchitectureGraphView", () => {
  beforeEach(() => {
    useMeasuredLayoutMock.mockReset();
    useMeasuredLayoutMock.mockReturnValue({
      nodes: withProjectLayout(nodes),
      edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    });
  });

  it("uses the shared presentation shell with lanes, scope controls, and hidden bucket chips", () => {
    render(
      <ArchitectureGraphView
        view={view}
        selectedNodeId="layer:api"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    const surface = screen.getByTestId("graph-flow-surface");
    expect(surface).toHaveAttribute("data-node-ids", "layer:api|service:order|component:domain|resource:application.yml");
    expect(surface).toHaveAttribute("data-edge-ids", "edge:api->service|edge:service->resource");
    expect(surface).toHaveAttribute("data-editable", "false");
    expect(surface).toHaveAttribute("data-layout-editable", "false");
    expect(surface).toHaveAttribute("data-viewport-policy", "readable-fit");
    expect(screen.getByText("项目架构")).toBeInTheDocument();
    expect(screen.getByText("Order context")).toBeInTheDocument();
    expect(screen.getByText("4 / 4")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "组件" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "包" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "类" })).toBeInTheDocument();
    expect(screen.getByLabelText("折叠内容")).toHaveTextContent("三方依赖 2");
    expect(screen.getByLabelText("折叠内容")).toHaveTextContent("JDK 1");
    expect(surface).toHaveAttribute("data-has-viewport-overlay", "false");
    expect(screen.queryByTestId("architecture-layer-overlay")).not.toBeInTheDocument();
    expect(surface).toHaveAttribute("data-flow-node-types", expect.not.stringContaining("architecture-band:"));
    expect(screen.queryByText("详情")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("当前架构图说明")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("当前关键关系")).not.toBeInTheDocument();
  });

  it("renders project structure as a readable layer canvas without selected-node dimming", () => {
    const root = architectureNode({
      id: "arch:component:com.example",
      type: "COMPONENT",
      title: "com.example",
      nodeRole: "UNKNOWN",
      nodeKind: "COMPONENT",
      qualifiedName: "com.example",
    });
    const api = architectureNode({
      id: "layer:api",
      type: "LAYER",
      title: "API",
      nodeRole: "API",
      nodeKind: "LAYER",
      presentationLaneId: "entry",
    });
    const service = architectureNode({
      id: "component:com.example.orders",
      type: "COMPONENT",
      title: "orders",
      nodeRole: "SERVICE",
      nodeKind: "COMPONENT",
      qualifiedName: "com.example.orders",
    });
    const resource = architectureNode({
      id: "resource:application.yml",
      type: "RESOURCE",
      title: "application.yml",
      nodeRole: "RESOURCE",
      nodeKind: "RESOURCE",
      layerKind: "RESOURCE",
      presentationLaneId: "data",
    });
    const hiddenNoise = Array.from({ length: 12 }, (_, index) => architectureNode({
      id: `component:hidden-${index}`,
      type: "COMPONENT",
      title: `hidden-${index}`,
      nodeRole: "SERVICE",
      nodeKind: "COMPONENT",
    }));
    const graphNodes = [root, api, service, resource, ...hiddenNoise];
    const overviewMetadata = {
      "architecture.aggregate.level": "OVERVIEW",
      "jvm.relation.kind": "CALLS",
      "indexed.relationKind": "CALLS",
      "indexed.sourceCount": "1",
    };
    const graphEdges: LinkGraphEdge[] = [
      { id: "root-api", type: "CALL", source: root.id, target: api.id, label: "calls", metadata: overviewMetadata },
      { id: "api-service", type: "CALL", source: api.id, target: service.id, label: "calls", metadata: overviewMetadata },
      { id: "service-resource", type: "CALL", source: service.id, target: resource.id, label: "calls", metadata: overviewMetadata },
      ...hiddenNoise.map((node, index) => ({
        id: `hidden-edge-${index}`,
        type: "CALL" as const,
        source: root.id,
        target: node.id,
        label: "calls",
        metadata: overviewMetadata,
      })),
    ];
    const requestRelayout = vi.fn();
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: withProjectLayout(graph.nodes),
      edges: graph.edges,
      layoutPending: false,
      requestRelayout,
    }));

    render(
      <ArchitectureGraphView
        view={{
          ...view,
          visibleGraph: {
            nodes: graphNodes,
            edges: graphEdges,
          },
          fullGraph: {
            nodes: graphNodes,
            edges: graphEdges,
          },
          anchorNodeId: root.id,
          summary: {
            ...view.summary,
            indexed: {
              ...view.summary.indexed!,
              scopedNodeCount: 40,
              visibleNodeCount: graphNodes.length,
              candidateNodeCount: 40,
              candidateEdgeCount: graphEdges.length,
              hiddenNodeCount: 40 - graphNodes.length,
            },
          },
        }}
        selectedNodeId={root.id}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    const firstLayoutCall = useMeasuredLayoutMock.mock.calls[0]?.[0];
    expect(firstLayoutCall.anchorNodeId).toBe(root.id);
    expect(firstLayoutCall.graph.nodes.map((node: LinkGraphNode) => node.id)).toEqual([
      root.id,
      api.id,
      service.id,
      resource.id,
      ...hiddenNoise.map((node) => node.id),
    ]);
    expect(firstLayoutCall.graph.edges.map((edge: LinkGraphEdge) => edge.id)).toEqual([
      "root-api",
      "api-service",
      "service-resource",
      ...hiddenNoise.map((_, index) => `hidden-edge-${index}`),
    ]);
    expect(screen.getByText("项目架构")).toBeInTheDocument();
    expect(screen.queryByText("架构聚合")).not.toBeInTheDocument();
    expect(screen.queryByText("项目架构聚合总览")).not.toBeInTheDocument();
    expect(screen.queryByText(/模块、组件\/服务边界和资源入口的聚合关系/)).not.toBeInTheDocument();
    expect(screen.queryByText("项目结构")).not.toBeInTheDocument();
    const surface = screen.getByTestId("graph-flow-surface");
    expect(surface).toHaveAttribute(
      "data-node-ids",
      [root.id, api.id, service.id, resource.id, ...hiddenNoise.map((node) => node.id)].join("|"),
    );
    expect(screen.getByText("16 / 40")).toBeInTheDocument();
    expect(surface).toHaveAttribute(
      "data-flow-node-classnames",
      expect.not.stringContaining("is-dimmed"),
    );
    expect(surface).toHaveAttribute(
      "data-flow-edge-label-visibility",
      expect.not.stringContaining(":always"),
    );
    expect(surface).toHaveAttribute(
      "data-flow-edge-label-visibility",
      expect.stringContaining("root-api:selected"),
    );
    expect(surface).toHaveAttribute("data-show-viewport-controls", "false");
    expect(surface).toHaveAttribute("data-viewport-policy", "readable-fit");
    expect(surface).toHaveAttribute("data-has-viewport-overlay", "false");
    expect(surface).toHaveAttribute("data-flow-node-types", expect.not.stringContaining("architecture-band:"));
    expect(screen.queryByTestId("architecture-layer-overlay")).not.toBeInTheDocument();
    expect(screen.queryByTestId("architecture-layer-frame-entry")).not.toBeInTheDocument();
    expect(screen.queryByText("入口层")).not.toBeInTheDocument();
    expect(screen.queryByText("应用层")).not.toBeInTheDocument();
    expect(screen.queryByText("领域层")).not.toBeInTheDocument();
    expect(screen.queryByText("数据与资源")).not.toBeInTheDocument();
  });

  it("does not render every project structure edge label permanently in dense relation windows", () => {
    const graphNodes = [
      architectureNode({
        id: "entry:rest",
        type: "COMPONENT",
        title: "rest",
        nodeRole: "API",
        presentationLaneId: "entry",
      }),
      ...Array.from({ length: 8 }, (_, index) => architectureNode({
        id: `component:${index}`,
        type: "COMPONENT",
        title: `component-${index}`,
        nodeRole: "SERVICE",
        presentationLaneId: index < 4 ? "application" : "domain",
      })),
    ];
    const graphEdges: LinkGraphEdge[] = Array.from({ length: 18 }, (_, index) => ({
      id: `edge:${index}`,
      type: index % 2 === 0 ? "CALL" : "USES_TYPE",
      source: graphNodes[index % graphNodes.length]!.id,
      target: graphNodes[(index + 1) % graphNodes.length]!.id,
      label: index % 2 === 0 ? "calls" : "uses",
      metadata: {
        "architecture.aggregate.level": "OVERVIEW",
        "jvm.relation.kind": index % 2 === 0 ? "CALLS" : "USES_TYPE",
      },
    }));
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: withProjectLayout(graph.nodes),
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <ArchitectureGraphView
        view={{
          ...view,
          visibleGraph: {
            nodes: graphNodes,
            edges: graphEdges,
          },
          fullGraph: {
            nodes: graphNodes,
            edges: graphEdges,
          },
          anchorNodeId: "entry:rest",
          summary: {
            ...view.summary,
            indexed: {
              ...view.summary.indexed!,
              scopedNodeCount: graphNodes.length,
              visibleNodeCount: graphNodes.length,
              candidateNodeCount: graphNodes.length,
              candidateEdgeCount: graphEdges.length,
              hiddenNodeCount: 0,
            },
          },
        }}
        selectedNodeId={null}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    const surface = screen.getByTestId("graph-flow-surface");
    expect(surface).toHaveAttribute("data-flow-edge-label-visibility", expect.not.stringContaining(":always"));
    expect(surface).toHaveAttribute("data-flow-edge-label-visibility", expect.stringContaining(":selected"));
  });

  it("uses the source selector as the dependency entry instead of rendering inclusion toggles", () => {
    const onRequestArchitectureGraph = vi.fn();
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: graph.nodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <ArchitectureGraphView
        view={view}
        selectedNodeId="layer:api"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onRequestArchitectureGraph={onRequestArchitectureGraph}
      />,
    );

    expect(screen.queryByRole("group", { name: "架构图纳入依赖" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "纳入三方库" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "纳入 JDK" })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "JDK" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("节点来源"), { target: { value: "JDK" } });

    expect(onRequestArchitectureGraph).toHaveBeenCalledWith({
      includeExternalLibraries: false,
      includeJdk: true,
    });
    expect(screen.getByText("4 / 4")).toBeInTheDocument();
    expect(screen.queryByText("尚未加载架构聚合")).not.toBeInTheDocument();
  });

  it("opens the package dependency graph with dependency layers enabled", () => {
    const onRequestPackageDependencyGraph = vi.fn();

    render(
      <ArchitectureGraphView
        view={view}
        selectedNodeId="layer:api"
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onRequestPackageDependencyGraph={onRequestPackageDependencyGraph}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "包" }));

    expect(onRequestPackageDependencyGraph).toHaveBeenCalledWith(null, {
      includeExternalLibraries: true,
      includeJdk: true,
    });
  });

  it("filters dependency layers from the full graph with their connected project nodes", () => {
    const projectNode = architectureNode({
      id: "component:project",
      type: "COMPONENT",
      title: "project",
      nodeRole: "SERVICE",
      nodeKind: "COMPONENT",
      layerKind: "PROJECT_SOURCE",
    });
    const jdkNode = architectureNode({
      id: "library:jdk",
      type: "LIBRARY",
      title: "JDK",
      nodeRole: "EXTERNAL",
      nodeKind: "JDK",
      layerKind: "JDK",
    });
    const hiddenProjectNode = architectureNode({
      id: "component:hidden",
      type: "COMPONENT",
      title: "hidden",
      nodeRole: "SERVICE",
      nodeKind: "COMPONENT",
      layerKind: "PROJECT_SOURCE",
    });
    const graphEdges: LinkGraphEdge[] = [
      { id: "project-jdk", type: "USES_TYPE", source: projectNode.id, target: jdkNode.id },
      { id: "hidden-project", type: "CALL", source: projectNode.id, target: hiddenProjectNode.id },
    ];
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: graph.nodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <ArchitectureGraphView
        view={{
          ...view,
          visibleGraph: {
            nodes: [projectNode],
            edges: [],
          },
          fullGraph: {
            nodes: [projectNode, jdkNode, hiddenProjectNode],
            edges: graphEdges,
          },
          anchorNodeId: projectNode.id,
          summary: {
            ...view.summary,
            indexed: {
              ...view.summary.indexed!,
              includeJdk: true,
              scopedNodeCount: 3,
              visibleNodeCount: 1,
              candidateNodeCount: 3,
              candidateEdgeCount: 2,
            },
          },
        }}
        selectedNodeId={projectNode.id}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    fireEvent.change(screen.getByLabelText("节点来源"), { target: { value: "JDK" } });

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-ids",
      `${projectNode.id}|${jdkNode.id}`,
    );
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-edge-ids", "project-jdk");
    expect(screen.getByText("2 / 3")).toBeInTheDocument();
  });

  it("offers source navigation for architecture aggregates with a source navigation anchor", () => {
    const onRequestSourceNavigation = vi.fn();
    const aggregate = architectureNode({
      id: "component:orders",
      type: "COMPONENT",
      title: "orders",
      nodeRole: "SERVICE",
      memberClassCount: "3",
    });
    aggregate.metadata = {
      ...(aggregate.metadata ?? {}),
      "source.navigation.nodeId": "class:com.example.orders.OrderService",
      "source.navigation.filePath": "src/main/java/com/example/orders/OrderService.java",
      "source.navigation.startLine": "12",
      "source.navigation.reason": "architecture-member-class:component:orders",
    };
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: graph.nodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <ArchitectureGraphView
        view={{
          ...view,
          visibleGraph: {
            nodes: [aggregate],
            edges: [],
          },
          fullGraph: {
            nodes: [aggregate],
            edges: [],
          },
          anchorNodeId: aggregate.id,
        }}
        selectedNodeId={aggregate.id}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={onRequestSourceNavigation}
      />,
    );

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-action-ids",
      expect.stringContaining("component:orders:inspect-node,open-class-diagram,open-package-graph,toggle-collapse,open-source"),
    );

    fireEvent.click(screen.getByTestId("node-action-component:orders-open-source"));

    expect(onRequestSourceNavigation).toHaveBeenCalledWith("component:orders");
  });

  it("filters visible nodes by indexed layer kind instead of treating JDK as a node type", () => {
    const projectNode = architectureNode({
      id: "component:project",
      type: "COMPONENT",
      title: "project",
      nodeRole: "SERVICE",
      nodeKind: "COMPONENT",
      layerKind: "PROJECT_SOURCE",
    });
    const libraryNode = architectureNode({
      id: "library:guava",
      type: "LIBRARY",
      title: "guava",
      nodeRole: "EXTERNAL",
      nodeKind: "LIBRARY",
      layerKind: "EXTERNAL_LIBRARY",
    });
    const jdkNode = architectureNode({
      id: "library:jdk",
      type: "LIBRARY",
      title: "JDK",
      nodeRole: "EXTERNAL",
      nodeKind: "JDK",
      layerKind: "JDK",
    });
    const graphNodes = [projectNode, libraryNode, jdkNode];
    const graphEdges: LinkGraphEdge[] = [
      { id: "project-guava", type: "USES_TYPE", source: projectNode.id, target: libraryNode.id },
      { id: "project-jdk", type: "USES_TYPE", source: projectNode.id, target: jdkNode.id },
    ];
    useMeasuredLayoutMock.mockImplementation(({ graph }) => ({
      nodes: graph.nodes,
      edges: graph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    }));

    render(
      <ArchitectureGraphView
        view={{
          ...view,
          visibleGraph: {
            nodes: graphNodes,
            edges: graphEdges,
          },
          fullGraph: {
            nodes: graphNodes,
            edges: graphEdges,
          },
          anchorNodeId: projectNode.id,
          summary: {
            ...view.summary,
            indexed: {
              ...view.summary.indexed!,
              includeExternalLibraries: true,
              includeJdk: true,
              scopedNodeCount: 3,
              visibleNodeCount: 3,
              candidateNodeCount: 3,
              candidateEdgeCount: 2,
            },
          },
        }}
        selectedNodeId={projectNode.id}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    fireEvent.change(screen.getByLabelText("节点来源"), { target: { value: "JDK" } });
    fireEvent.change(screen.getByRole("searchbox", { name: "搜索" }), { target: { value: "jdk" } });

    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute(
      "data-node-ids",
      `${projectNode.id}|${jdkNode.id}`,
    );
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-edge-ids", "project-jdk");
    expect(screen.getByText("2 / 3")).toBeInTheDocument();
  });

  it("uses the indexed request lifecycle for empty architecture states", () => {
    useMeasuredLayoutMock.mockReturnValue({
      nodes: [],
      edges: [],
      layoutPending: false,
      requestRelayout: vi.fn(),
    });
    const emptyView: ArchitectureGraphViewDocument = {
      ...view,
      visibleGraph: { nodes: [], edges: [] },
      fullGraph: { nodes: [], edges: [] },
      anchorNodeId: null,
    };
    const { rerender } = render(
      <ArchitectureGraphView
        view={emptyView}
        selectedNodeId={null}
        indexedGraphRequestStates={{
          ARCHITECTURE: requestState("RUNNING", "正在构建架构聚合索引。"),
        }}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByText("正在构建架构聚合索引")).toBeInTheDocument();
    expect(screen.queryByText("正在构建架构聚合索引。")).not.toBeInTheDocument();
    expect(document.querySelector(".canvas-empty-state .muted")).not.toBeInTheDocument();

    rerender(
      <ArchitectureGraphView
        view={emptyView}
        selectedNodeId={null}
        indexedGraphRequestStates={{
          ARCHITECTURE: requestState("FAILED", null, "索引构建失败"),
        }}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByText("架构聚合加载失败")).toBeInTheDocument();
    expect(screen.queryByText("索引构建失败")).not.toBeInTheDocument();
    expect(document.querySelector(".canvas-empty-state .muted")).not.toBeInTheDocument();

    rerender(
      <ArchitectureGraphView
        view={emptyView}
        selectedNodeId={null}
        indexedGraphRequestStates={{
          ARCHITECTURE: requestState("SUCCEEDED"),
        }}
        onSelectNode={noop}
        onInspectNode={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
      />,
    );

    expect(screen.getByText("索引完成，但当前项目范围没有可展示的架构聚合")).toBeInTheDocument();
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

function withProjectLayout(layoutNodes: LinkGraphNode[]): LinkGraphNode[] {
  const laneRows: Record<string, number> = {
    entry: 0,
    application: 1,
    domain: 2,
    data: 3,
    resource: 3,
    external: 3,
  };
  const bandBounds: Record<string, { x: number; y: number; width: number; height: number }> = {
    entry: { x: 72, y: 60, width: 1580, height: 170 },
    application: { x: 72, y: 240, width: 1580, height: 338 },
    domain: { x: 72, y: 588, width: 1580, height: 338 },
    data: { x: 72, y: 936, width: 1580, height: 338 },
  };
  return layoutNodes.map((node, index) => {
    const lane = node.metadata?.["presentation.laneId"] ?? "application";
    const row = laneRows[lane] ?? 1;
    const band = row === 0 ? "entry" : row === 2 ? "domain" : row === 3 ? "data" : "application";
    const bounds = bandBounds[band]!;
    return {
      ...node,
      position: {
        x: 120 + (index % 4) * 360,
        y: 96 + row * 180 + Math.floor(index / 4) * 168,
      },
      metadata: {
        ...(node.metadata ?? {}),
        "architecture.layoutMode": "PROJECT_STRUCTURE",
        "architecture.layoutBand": band,
        "architecture.layoutBandX": String(bounds.x),
        "architecture.layoutBandY": String(bounds.y),
        "architecture.layoutBandWidth": String(bounds.width),
        "architecture.layoutBandHeight": String(bounds.height),
        "architecture.layoutNodeWidth": "408",
        "architecture.layoutNodeHeight": "116",
      },
    };
  });
}

function architectureNode({
  id,
  type,
  title,
  nodeRole,
  nodeKind = type,
  layerKind = "PROJECT_SOURCE",
  memberClassCount = "0",
  memberResourceCount = "0",
  qualifiedName,
  presentationRole,
  presentationLaneId,
}: {
  id: string;
  type: LinkGraphNode["type"];
  title: string;
  nodeRole: string;
  nodeKind?: string;
  layerKind?: string;
  memberClassCount?: string;
  memberResourceCount?: string;
  qualifiedName?: string;
  presentationRole?: string;
  presentationLaneId?: string;
}): LinkGraphNode {
  return {
    id,
    type,
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata: {
      "architecture.node.kind": nodeKind,
      "indexed.layerKind": layerKind,
      "indexed.nodeRole": nodeRole,
      "indexed.memberClassCount": memberClassCount,
      "indexed.memberResourceCount": memberResourceCount,
      ...(qualifiedName ? { "architecture.qualifiedName": qualifiedName } : {}),
      ...(presentationRole ? { "presentation.role": presentationRole } : {}),
      ...(presentationLaneId ? { "presentation.laneId": presentationLaneId } : {}),
    },
  };
}
