import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  ArchitectureNodeCard,
  buildArchitectureGraphEdges,
} from "../../../../app/views/architecture/architectureGraphNodes";
import type { LinkGraphEdge, LinkGraphNode } from "../../../../app/types";

vi.mock("@xyflow/react", () => ({
  Handle: () => null,
  MarkerType: {
    ArrowClosed: "arrowclosed",
  },
  Position: {
    Left: "left",
    Right: "right",
  },
  useUpdateNodeInternals: () => vi.fn(),
}));

describe("buildArchitectureGraphEdges", () => {
  it("aggregates parallel architecture relations so dense views do not render overlapping edge labels", () => {
    const edges: LinkGraphEdge[] = [
      {
        id: "edge:service->diff:call",
        type: "CALL",
        source: "service:linkgraph",
        target: "service:diff",
      },
      {
        id: "edge:service->diff:reflect",
        type: "REFLECTS_TO",
        source: "service:linkgraph",
        target: "service:diff",
      },
      {
        id: "edge:service->diff:spi",
        type: "SPI_RESOLVES_TO",
        source: "service:linkgraph",
        target: "service:diff",
        metadata: {
          "jvm.relation.confidence": "AMBIGUOUS",
        },
      },
    ];

    const [builtEdge] = buildArchitectureGraphEdges({
      edges,
      draftCompareEdgeStatuses: {
        "edge:service->diff:reflect": "MODIFIED",
      },
    });

    expect(builtEdge).toMatchObject({
      id: "architecture-edge-group:service:linkgraph->service:diff",
      source: "service:linkgraph",
      target: "service:diff",
      type: "routedEdge",
      label: expect.stringContaining("+1"),
      ariaLabel: expect.stringContaining("调用"),
      data: {
        labelVisibility: "selected",
      },
    });
    expect(builtEdge?.className).toContain("edge-architecture");
    expect(builtEdge?.className).toContain("is-aggregated");
    expect(builtEdge?.className).toContain("is-draft-compare-modified");
    expect(builtEdge?.style?.strokeDasharray).toBe("7 5");
  });

  it("keeps single architecture relations as routed edges with labels hidden until selected", () => {
    const [builtEdge] = buildArchitectureGraphEdges({
      edges: [
        {
          id: "edge:module->service",
          type: "USES_TYPE",
          source: "module:api",
          target: "service:application",
        },
      ],
    });

    expect(builtEdge).toMatchObject({
      id: "edge:module->service",
      label: "类型依赖",
      data: {
        labelVisibility: "selected",
      },
    });
    expect(builtEdge?.className).toBe("edge-architecture");
  });

  it("uses backend display relation semantics and source counts for project structure edge labels", () => {
    const [builtEdge] = buildArchitectureGraphEdges({
      edges: [
        {
          id: "edge:server->coordinator",
          type: "CALL",
          source: "arch:component:org.apache.kafka.server",
          target: "arch:component:org.apache.kafka.coordinator.group",
          label: "calls",
          metadata: {
            "architecture.displayRelation": "运行时调用",
            "architecture.displayRelationKind": "RUNTIME_CALL",
            "indexed.sourceCount": "42",
          },
        },
      ],
    });

    expect(builtEdge).toMatchObject({
      label: "运行时调用 42",
      ariaLabel: "运行时调用 42",
    });
  });
});

describe("ArchitectureNodeCard", () => {
  it("renders architecture nodes as compact graph marks instead of detail cards", () => {
    render(
      <ArchitectureNodeCard node={architectureNode()} selected={false} />,
    );

    expect(screen.getByText("外部")).toBeInTheDocument();
    expect(screen.getByText("library:vendor.jar")).toBeInTheDocument();
    expect(screen.getByText(/12 类型/)).toBeInTheDocument();
    expect(screen.getByText(/1 资源/)).toBeInTheDocument();
    expect(screen.queryByText("外部依赖分组：library:vendor.jar")).not.toBeInTheDocument();
    expect(screen.queryByText(/没有窗口收束节点/)).not.toBeInTheDocument();
    expect(screen.queryByText(/可打开类图继续看/)).not.toBeInTheDocument();
  });

  it("renders real window compaction counts separately from aggregate members", () => {
    render(
      <ArchitectureNodeCard
        node={{
          ...architectureNode({ title: "More nodes" }),
          metadata: {
            ...architectureNode().metadata,
            "indexed.memberClassCount": "0",
            "indexed.memberResourceCount": "0",
            "indexed.collapsedCount": "13",
            "indexed.expandable": "false",
            "linkGraph.overflow.kind": "GRAPH_WINDOW",
          },
        }}
        selected={false}
      />,
    );

    expect(screen.getByText("+13")).toBeInTheDocument();
    expect(screen.queryByText(/0 个类/)).not.toBeInTheDocument();
    expect(screen.queryByText(/13 个节点由此收束/)).not.toBeInTheDocument();
  });

  it("uses full qualified names as the primary project structure title", () => {
    render(
      <ArchitectureNodeCard
        node={{
          ...architectureNode({
            title: "org",
            nodeType: "COMPONENT",
            nodeRole: "SERVICE",
            nodeKind: "COMPONENT",
            layerKind: "PROJECT_SOURCE",
            boundaryKind: "PROJECT_COMPONENT",
            qualifiedName: "org.apache.kafka.server",
          }),
          metadata: {
            ...architectureNode({
              nodeType: "COMPONENT",
              nodeRole: "SERVICE",
              nodeKind: "COMPONENT",
              layerKind: "PROJECT_SOURCE",
              boundaryKind: "PROJECT_COMPONENT",
              qualifiedName: "org.apache.kafka.server",
            }).metadata,
            "architecture.displayName": "apache.kafka.server",
            "architecture.displayBaseName": "server",
            "architecture.displaySubtitle": "应用层 · 组件",
            "indexed.memberClassCount": "85",
            "indexed.memberResourceCount": "0",
          },
        }}
        selected={false}
      />,
    );

    expect(screen.getByText("org.apache.kafka.server")).toBeInTheDocument();
    expect(screen.getByText("应用层 · 组件")).toBeInTheDocument();
    expect(screen.getByText(/85 类型/)).toBeInTheDocument();
    expect(screen.queryByText("org")).not.toBeInTheDocument();
    expect(screen.queryByText("apache.kafka.server")).not.toBeInTheDocument();
  });

  it("renders JDK groups as JDK groups rather than services", () => {
    render(
      <ArchitectureNodeCard
        node={architectureNode({
          title: "JDK",
          nodeType: "LIBRARY",
          nodeRole: "EXTERNAL",
          nodeKind: "JDK",
          layerKind: "JDK",
          boundaryKind: "JDK_GROUP",
          qualifiedName: "JDK",
        })}
        selected={false}
      />,
    );

    expect(screen.getAllByText("JDK").length).toBeGreaterThan(0);
    expect(screen.queryByText("服务边界")).not.toBeInTheDocument();
  });

  it("marks the target architecture node", () => {
    render(
      <ArchitectureNodeCard
        node={architectureNode({
          title: "API",
          nodeType: "LAYER",
          nodeRole: "API",
          nodeKind: "LAYER",
          layerKind: "PROJECT_SOURCE",
          boundaryKind: "PROJECT_LAYER",
          qualifiedName: "API",
        })}
        selected={false}
        targetNode
      />,
    );

    expect(screen.getByText("目标")).toBeInTheDocument();
    expect(screen.getByText("API 层")).toBeInTheDocument();
    expect(screen.getByText("层")).toBeInTheDocument();
  });

  it("marks inferred service boundaries so users do not read them as declared facts", () => {
    render(
      <ArchitectureNodeCard
        node={architectureNode({
          title: "orders",
          nodeType: "SERVICE",
          nodeRole: "SERVICE",
          nodeKind: "SERVICE",
          layerKind: "PROJECT_SOURCE",
          boundaryKind: "PROJECT_SERVICE_BOUNDARY",
          qualifiedName: "com.example.orders",
        })}
        selected={false}
      />,
    );

    expect(screen.getByText("服务边界")).toBeInTheDocument();
    expect(screen.getByText("推断")).toBeInTheDocument();
    expect(screen.queryByText("服务")).not.toBeInTheDocument();
  });
});

function architectureNode({
  title = "VendorGroup",
  nodeType = "SERVICE",
  nodeRole = "EXTERNAL",
  nodeKind = "PACKAGE",
  layerKind = "EXTERNAL_LIBRARY",
  boundaryKind = "EXTERNAL_LIBRARY_GROUP",
  qualifiedName = "library:vendor.jar",
}: {
  title?: string;
  nodeType?: LinkGraphNode["type"];
  nodeRole?: string;
  nodeKind?: string;
  layerKind?: string;
  boundaryKind?: string;
  qualifiedName?: string;
} = {}): LinkGraphNode {
  return {
    id: "arch:library:example",
    type: nodeType,
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata: {
      "architecture.node.kind": nodeKind,
      "architecture.boundary.kind": boundaryKind,
      "architecture.qualifiedName": qualifiedName,
      "indexed.layerKind": layerKind,
      "indexed.nodeRole": nodeRole,
      "indexed.sourceKind": "SYNTHETIC_AGGREGATE",
      "indexed.memberClassCount": "12",
      "indexed.memberResourceCount": "1",
      "indexed.collapsedCount": "0",
      "indexed.expandable": "true",
    },
  };
}
