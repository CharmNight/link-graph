import { describe, expect, it } from "vitest";
import type { LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import { layoutArchitectureGraphView } from "../../../../app/views/architecture/architectureGraphLayout";

function nodeSize(node: LinkGraphNode): { width: number; height: number } {
  return {
    width: Number(node.metadata?.["architecture.layoutNodeWidth"] ?? 408),
    height: Number(node.metadata?.["architecture.layoutNodeHeight"] ?? 116),
  };
}

function nodeRect(node: LinkGraphNode) {
  const size = nodeSize(node);
  return {
    id: node.id,
    left: node.position?.x ?? 0,
    top: node.position?.y ?? 0,
    right: (node.position?.x ?? 0) + size.width,
    bottom: (node.position?.y ?? 0) + size.height,
  };
}

function rectsOverlap(
  left: ReturnType<typeof nodeRect>,
  right: ReturnType<typeof nodeRect>,
): boolean {
  return left.left < right.right
    && left.right > right.left
    && left.top < right.bottom
    && left.bottom > right.top;
}

function bandBounds(node: LinkGraphNode) {
  return {
    x: Number(node.metadata?.["architecture.layoutBandX"]),
    y: Number(node.metadata?.["architecture.layoutBandY"]),
    width: Number(node.metadata?.["architecture.layoutBandWidth"]),
    height: Number(node.metadata?.["architecture.layoutBandHeight"]),
  };
}

function architectureNode(
  id: string,
  {
    type = "COMPONENT",
    title = id,
    nodeKind = type,
    nodeRole = "SERVICE",
    layerKind = "PROJECT_SOURCE",
    presentationLaneId,
  }: {
    type?: LinkGraphNode["type"];
    title?: string;
    nodeKind?: string;
    nodeRole?: string;
    layerKind?: string;
    presentationLaneId?: string;
  } = {},
): LinkGraphNode {
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
      "indexed.nodeRole": nodeRole,
      "indexed.layerKind": layerKind,
      ...(presentationLaneId ? { "presentation.laneId": presentationLaneId } : {}),
    },
  };
}

describe("layoutArchitectureGraphView", () => {
  it("lays out project structure graphs by readable presentation bands instead of tree depth rows", async () => {
    const overviewMetadata = {
      "architecture.aggregate.level": "OVERVIEW",
      "jvm.relation.kind": "CALLS",
      "indexed.relationKind": "CALLS",
    };
    const nodes = [
      architectureNode("component:entry", {
        type: "COMPONENT",
        title: "entry",
        nodeKind: "COMPONENT",
        nodeRole: "API",
        presentationLaneId: "entry",
      }),
      ...Array.from({ length: 10 }, (_, index) => architectureNode(`component:${index}`, {
        type: "COMPONENT",
        title: `component-${index}`,
        nodeKind: "COMPONENT",
        nodeRole: "SERVICE",
        presentationLaneId: index % 2 === 0 ? "application" : "domain",
      })),
      ...Array.from({ length: 8 }, (_, index) => architectureNode(`resource:${index}`, {
        type: "RESOURCE",
        title: `resource-${index}`,
        nodeKind: "RESOURCE",
        nodeRole: "RESOURCE",
        layerKind: "RESOURCE",
        presentationLaneId: "resource",
      })),
    ];
    const edges: LinkGraphEdge[] = nodes.slice(1).map((node) => ({
      id: `overview:${nodes[0]!.id}->${node.id}`,
      type: "CALL",
      source: nodes[0]!.id,
      target: node.id,
      label: "calls",
      metadata: overviewMetadata,
    }));

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: nodes[0]!.id,
      sizeSnapshot: new Map(),
      reason: "graph",
      projectStructureView: true,
    });
    const xs = laidOut.nodes.map((node) => node.position?.x ?? 0);
    const root = laidOut.nodes.find((node) => node.id === nodes[0]!.id)!;
    const entryNode = laidOut.nodes.find((node) => node.metadata?.["presentation.laneId"] === "entry");
    const applicationNode = laidOut.nodes.find((node) => node.metadata?.["presentation.laneId"] === "application");
    const domainNode = laidOut.nodes.find((node) => node.metadata?.["presentation.laneId"] === "domain");
    const resourceNode = laidOut.nodes.find((node) => node.metadata?.["presentation.laneId"] === "resource");
    const bandYs = (band: string) => laidOut.nodes
      .filter((node) => node.metadata?.["architecture.layoutBand"] === band)
      .map((node) => node.position?.y ?? 0);

    expect(root.metadata?.["architecture.layoutMode"]).toBe("PROJECT_STRUCTURE");
    expect(Math.max(...xs) - Math.min(...xs)).toBeGreaterThan(3 * 260);
    expect(entryNode?.metadata?.["architecture.layoutBand"]).toBe("entry");
    expect(applicationNode?.metadata?.["architecture.layoutBand"]).toBe("application");
    expect(domainNode?.metadata?.["architecture.layoutBand"]).toBe("domain");
    expect(resourceNode?.metadata?.["architecture.layoutBand"]).toBe("data");
    expect(entryNode?.position?.y ?? 0).toBeLessThan(applicationNode?.position?.y ?? Number.MAX_SAFE_INTEGER);
    expect(applicationNode?.position?.y ?? 0).toBeLessThan(domainNode?.position?.y ?? Number.MAX_SAFE_INTEGER);
    expect(domainNode?.position?.y ?? 0).toBeLessThan(resourceNode?.position?.y ?? Number.MAX_SAFE_INTEGER);
    expect(Math.max(...bandYs("application"))).toBeLessThan(Math.min(...bandYs("domain")));
    expect(Math.max(...bandYs("domain"))).toBeLessThan(Math.min(...bandYs("data")));
    expect(laidOut.edges.every((edge) => edge.route?.sections.length)).toBe(true);
  });

  it("does not switch to project structure layout only because every edge is synthetic", async () => {
    const nodes = [
      architectureNode("component:source", { presentationLaneId: "application" }),
      architectureNode("component:target", { presentationLaneId: "application" }),
    ];
    const edges: LinkGraphEdge[] = [
      {
        id: "legacy-structure",
        type: "CONTAINS_FLOW",
        source: "component:source",
        target: "component:target",
        metadata: {
          "architecture.relation.kind": "PROJECT_STRUCTURE_PARENT",
          "indexed.relationKind": "PROJECT_STRUCTURE_PARENT",
        },
      },
    ];

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "component:source",
      sizeSnapshot: new Map(),
      reason: "graph",
    });

    expect(laidOut.nodes.some((node) => node.metadata?.["architecture.layoutMode"] === "PROJECT_STRUCTURE")).toBe(false);
  });

  it("uses one layout route for parallel architecture relations without dropping the original edges", async () => {
    const nodes = [
      architectureNode("component:api"),
      architectureNode("component:application"),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "edge:api->app:call", type: "CALL", source: "component:api", target: "component:application" },
      { id: "edge:api->app:type", type: "USES_TYPE", source: "component:api", target: "component:application" },
      { id: "edge:api->app:spi", type: "SPI_RESOLVES_TO", source: "component:api", target: "component:application" },
    ];

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "layer:api",
      sizeSnapshot: new Map(),
      reason: "graph",
    });

    expect(laidOut.nodes).toHaveLength(2);
    expect(laidOut.edges.map((edge) => edge.id)).toEqual(edges.map((edge) => edge.id));
    expect(laidOut.edges.every((edge) => edge.route?.sections.length)).toBe(true);
    expect(new Set(laidOut.edges.map((edge) => JSON.stringify(edge.route))).size).toBe(1);
  });

  it("places overview nodes by presentation lane metadata instead of legacy architecture kind heuristics", async () => {
    const nodes = [
      architectureNode("layer:api", { type: "LAYER", title: "API", nodeKind: "LAYER", nodeRole: "API", presentationLaneId: "entry" }),
      architectureNode("component:app", { type: "COMPONENT", nodeKind: "COMPONENT", nodeRole: "SERVICE", presentationLaneId: "application" }),
      architectureNode("component:domain", { type: "COMPONENT", nodeKind: "COMPONENT", nodeRole: "SERVICE", presentationLaneId: "domain" }),
      architectureNode("layer:data", { type: "LAYER", title: "data", nodeKind: "LAYER", nodeRole: "DATA", presentationLaneId: "data" }),
      architectureNode("resource:config", { type: "RESOURCE", nodeKind: "RESOURCE", nodeRole: "RESOURCE", layerKind: "RESOURCE", presentationLaneId: "resource" }),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "api-app", type: "CALL", source: "layer:api", target: "component:app" },
      { id: "app-domain", type: "CALL", source: "component:app", target: "component:domain" },
      { id: "domain-data", type: "USES_TYPE", source: "component:domain", target: "layer:data" },
      { id: "data-resource", type: "BINDS_CONFIG", source: "layer:data", target: "resource:config" },
    ];

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "layer:api",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const nodeIndex = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const x = (nodeId: string) => nodeIndex.get(nodeId)?.position?.x ?? 0;

    expect(nodeIndex.get("layer:api")?.metadata?.["architecture.layoutLane"]).toBe("entry");
    expect(nodeIndex.get("component:app")?.metadata?.["architecture.layoutLane"]).toBe("application");
    expect(nodeIndex.get("component:domain")?.metadata?.["architecture.layoutLane"]).toBe("domain");
    expect(nodeIndex.get("layer:data")?.metadata?.["architecture.layoutLane"]).toBe("data");
    expect(nodeIndex.get("resource:config")?.metadata?.["architecture.layoutLane"]).toBe("resource");
    expect(x("layer:api")).toBeLessThan(x("component:app"));
    expect(x("component:app")).toBeLessThan(x("component:domain"));
    expect(x("component:domain")).toBeLessThan(x("layer:data"));
    expect(x("layer:data")).toBeLessThan(x("resource:config"));
  });

  it("defaults nodes without presentation lane metadata to the application lane", async () => {
    const nodes = [
      architectureNode("component:app", { type: "COMPONENT", nodeKind: "COMPONENT", nodeRole: "SERVICE" }),
    ];

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges: [] },
      nodes,
      edges: [],
      sizeSnapshot: new Map(),
      reason: "graph",
    });

    expect(laidOut.nodes[0]?.metadata?.["architecture.layoutLane"]).toBe("application");
  });

  it("keeps architecture lanes compact instead of spreading visible nodes through hidden full-graph gaps", async () => {
    const nodes = [
      architectureNode("layer:api", { type: "LAYER", title: "API", nodeKind: "LAYER", nodeRole: "API", presentationLaneId: "entry" }),
      architectureNode("component:orders", { type: "COMPONENT", nodeKind: "COMPONENT", nodeRole: "SERVICE", presentationLaneId: "application" }),
      architectureNode("component:billing", { type: "COMPONENT", nodeKind: "COMPONENT", nodeRole: "SERVICE", presentationLaneId: "application" }),
      architectureNode("layer:data", { type: "LAYER", title: "data", nodeKind: "LAYER", nodeRole: "DATA", presentationLaneId: "data" }),
      architectureNode("resource:config", { type: "RESOURCE", nodeKind: "RESOURCE", nodeRole: "RESOURCE", layerKind: "RESOURCE", presentationLaneId: "resource" }),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "api-orders", type: "CALL", source: "layer:api", target: "component:orders" },
      { id: "api-billing", type: "CALL", source: "layer:api", target: "component:billing" },
      { id: "orders-data", type: "USES_TYPE", source: "component:orders", target: "layer:data" },
      { id: "billing-data", type: "USES_TYPE", source: "component:billing", target: "layer:data" },
      { id: "data-resource", type: "BINDS_CONFIG", source: "layer:data", target: "resource:config" },
    ];

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const xs = laidOut.nodes.map((node) => node.position?.x ?? 0);
    const ys = laidOut.nodes.map((node) => node.position?.y ?? 0);

    expect(Math.max(...xs) - Math.min(...xs)).toBeLessThanOrEqual(4 * (408 + 136));
    expect(Math.max(...ys) - Math.min(...ys)).toBeLessThanOrEqual(2 * (156 + 64));
    expect(laidOut.edges.every((edge) => edge.route?.sections.length)).toBe(true);
  });

  it("splits same-kind component neighborhoods into upstream anchor and downstream dependency columns", async () => {
    const nodes = [
      architectureNode("component:root", { type: "COMPONENT", nodeKind: "COMPONENT", nodeRole: "UNKNOWN" }),
      architectureNode("component:anchor", { type: "COMPONENT", nodeKind: "COMPONENT", nodeRole: "SERVICE" }),
      architectureNode("component:downstream", { type: "COMPONENT", nodeKind: "COMPONENT", nodeRole: "SERVICE" }),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "root-anchor", type: "CALL", source: "component:root", target: "component:anchor" },
      { id: "anchor-downstream", type: "CALL", source: "component:anchor", target: "component:downstream" },
    ];

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "component:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const nodeIndex = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const x = (nodeId: string) => nodeIndex.get(nodeId)?.position?.x ?? 0;

    expect(nodeIndex.get("component:root")?.metadata?.["architecture.layoutTopologyRank"]).toBe("-1");
    expect(nodeIndex.get("component:anchor")?.metadata?.["architecture.layoutTopologyRank"]).toBe("0");
    expect(nodeIndex.get("component:downstream")?.metadata?.["architecture.layoutTopologyRank"]).toBe("1");
    expect(x("component:root")).toBeLessThan(x("component:anchor"));
    expect(x("component:anchor")).toBeLessThan(x("component:downstream"));
  });

  it("treats project structure bands as layout containers that contain their nodes without overlap", async () => {
    const nodes = [
      architectureNode("entry:rest", { title: "rest", nodeRole: "API", presentationLaneId: "entry" }),
      ...Array.from({ length: 6 }, (_, index) => architectureNode(`app:${index}`, {
        title: `app-${index}`,
        nodeRole: "SERVICE",
        presentationLaneId: "application",
      })),
      ...Array.from({ length: 5 }, (_, index) => architectureNode(`domain:${index}`, {
        title: `domain-${index}`,
        nodeRole: "SERVICE",
        presentationLaneId: "domain",
      })),
      ...Array.from({ length: 4 }, (_, index) => architectureNode(`data:${index}`, {
        title: `data-${index}`,
        nodeRole: "DATA",
        presentationLaneId: "data",
      })),
    ];
    const edges: LinkGraphEdge[] = [
      ...nodes.filter((node) => node.id.startsWith("app:")).map((node, index) => ({
        id: `entry-app-${index}`,
        type: "CALL" as const,
        source: "entry:rest",
        target: node.id,
      })),
      ...nodes.filter((node) => node.id.startsWith("domain:")).map((node, index) => ({
        id: `app-domain-${index}`,
        type: "CALL" as const,
        source: `app:${index % 6}`,
        target: node.id,
      })),
      ...nodes.filter((node) => node.id.startsWith("data:")).map((node, index) => ({
        id: `domain-data-${index}`,
        type: "USES_TYPE" as const,
        source: `domain:${index % 5}`,
        target: node.id,
      })),
    ];

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "entry:rest",
      sizeSnapshot: new Map(),
      reason: "graph",
      projectStructureView: true,
    });

    const rects = laidOut.nodes.map(nodeRect);
    rects.forEach((rect, index) => {
      rects.slice(index + 1).forEach((other) => {
        expect(rectsOverlap(rect, other), `${rect.id} overlaps ${other.id}`).toBe(false);
      });
    });
    laidOut.nodes.forEach((node) => {
      const bounds = bandBounds(node);
      const rect = nodeRect(node);
      expect(Number.isFinite(bounds.x), `${node.id} missing band x`).toBe(true);
      expect(Number.isFinite(bounds.y), `${node.id} missing band y`).toBe(true);
      expect(rect.left).toBeGreaterThanOrEqual(bounds.x);
      expect(rect.top).toBeGreaterThanOrEqual(bounds.y);
      expect(rect.right).toBeLessThanOrEqual(bounds.x + bounds.width);
      expect(rect.bottom).toBeLessThanOrEqual(bounds.y + bounds.height);
    });
  });

  it("keeps real 12-node project structure views narrow enough for readable default zoom", async () => {
    const nodes = [
      ...Array.from({ length: 3 }, (_, index) => architectureNode(`entry:${index}`, {
        title: `entry-${index}`,
        nodeRole: "API",
        presentationLaneId: "entry",
      })),
      ...Array.from({ length: 9 }, (_, index) => architectureNode(`app:${index}`, {
        title: `app-${index}`,
        nodeRole: "SERVICE",
        presentationLaneId: "application",
      })),
    ];
    const edges: LinkGraphEdge[] = [
      ...Array.from({ length: 3 }, (_, index) => ({
        id: `entry-app-${index}`,
        type: "CALL" as const,
        source: `entry:${index}`,
        target: `app:${index}`,
      })),
      ...Array.from({ length: 8 }, (_, index) => ({
        id: `app-chain-${index}`,
        type: "CALL" as const,
        source: `app:${index}`,
        target: `app:${index + 1}`,
      })),
    ];

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "entry:0",
      sizeSnapshot: new Map(),
      reason: "graph",
      projectStructureView: true,
    });
    const xs = laidOut.nodes.map((node) => node.position?.x ?? 0);
    const boundsWidth = Math.max(...xs.map((x) => x + 340)) - Math.min(...xs);
    const appColumns = new Set(
      laidOut.nodes
        .filter((node) => node.metadata?.["architecture.layoutBand"] === "application")
        .map((node) => node.metadata?.["architecture.layoutColumn"]),
    );

    expect(appColumns.size).toBeLessThanOrEqual(3);
    expect(boundsWidth).toBeLessThanOrEqual(3 * 340 + 2 * 72);
  });

  it("separates project structure edge channels instead of routing every cross-band edge on the same line", async () => {
    const nodes = [
      architectureNode("entry:rest", { nodeRole: "API", presentationLaneId: "entry" }),
      architectureNode("app:orders", { nodeRole: "SERVICE", presentationLaneId: "application" }),
      architectureNode("app:billing", { nodeRole: "SERVICE", presentationLaneId: "application" }),
      architectureNode("domain:orders", { nodeRole: "SERVICE", presentationLaneId: "domain" }),
      architectureNode("domain:billing", { nodeRole: "SERVICE", presentationLaneId: "domain" }),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "entry-orders", type: "CALL", source: "entry:rest", target: "app:orders" },
      { id: "entry-billing", type: "CALL", source: "entry:rest", target: "app:billing" },
      { id: "orders-domain", type: "CALL", source: "app:orders", target: "domain:orders" },
      { id: "billing-domain", type: "CALL", source: "app:billing", target: "domain:billing" },
    ];

    const laidOut = await layoutArchitectureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "entry:rest",
      sizeSnapshot: new Map(),
      reason: "graph",
      projectStructureView: true,
    });
    const routeStarts = laidOut.edges.map((edge) => edge.route?.sections[0]?.startPoint);
    const routeSignatures = laidOut.edges.map((edge) => JSON.stringify(edge.route));

    expect(laidOut.edges.every((edge) => edge.route?.sections.length)).toBe(true);
    expect(new Set(routeStarts.map((point) => `${point?.x}:${point?.y}`)).size).toBeGreaterThan(2);
    expect(new Set(routeSignatures).size).toBe(laidOut.edges.length);
  });
});
