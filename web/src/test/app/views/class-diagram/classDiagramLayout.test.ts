import { describe, expect, it } from "vitest";
import type { NodeMeasuredSize } from "../../../../app/graph/nodeSizeRegistry";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import { layoutClassDiagramView } from "../../../../app/views/class-diagram/classDiagramLayout";
import type { ClassDiagramPlacement } from "../../../../app/views/class-diagram/classDiagramPlacement";
import type { ClassDiagramTopology } from "../../../../app/views/class-diagram/classDiagramTopology";
import type { ClassDiagramLane, ClassLaneEntry } from "../../../../app/views/class-diagram/classDiagramLayoutModel";
import { ClassDiagramReadabilityScorer } from "../../../../app/views/class-diagram/classDiagramReadability";
import { ClassDiagramRoutingEngine } from "../../../../app/views/class-diagram/classDiagramRouting";
import { classDiagramNodeCardWidth } from "../../../../app/graphNodeSizing";

function classNode(id: string, title = id): LinkGraphNode {
  return {
    id,
    type: "CLASS",
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  };
}

function tallClassNode(id: string, title = id): LinkGraphNode {
  return {
    ...classNode(id, title),
    doc: "Coordinates a long-running application workflow and exposes the public API used by neighboring classes.",
    metadata: {
      "uml.field.items": [
        "provider: TaskProvider",
        "scheduler: TaskScheduler",
        "repository: TaskRepository",
        "clock: Clock",
        "events: EventBus",
      ].join("\n"),
      "uml.method.items": [
        "start(command: StartCommand): Result",
        "stop(id: TaskId): void",
        "status(id: TaskId): TaskStatus",
        "reschedule(id: TaskId, instant: Instant): void",
        "publish(event: TaskEvent): void",
      ].join("\n"),
      "uml.field.hiddenCount": "3",
      "uml.method.hiddenCount": "4",
    },
  };
}

function placedClassNode(
  id: string,
  lane: ClassDiagramLane,
  position: GraphPosition,
  column = 0,
  title = id,
): LinkGraphNode {
  return {
    ...classNode(id, title),
    position,
    metadata: {
      "layout.direction": lane,
      "layout.column": String(column),
      "layout.estimatedHeight": "120",
    },
  };
}

function relation(
  id: string,
  source: string,
  target: string,
  kind: string,
  umlKind = kind,
): LinkGraphEdge {
  return {
    id,
    type: kind === "INJECTS" ? "INJECT" : kind === "EXTENDS" ? "EXTENDS" : "USES_TYPE",
    source,
    target,
    label: kind.toLowerCase(),
    metadata: {
      "jvm.relation.kind": kind,
      "uml.relation.kind": umlKind,
    },
  };
}

function routePlacedClassEdges(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
  anchorId = nodes[0]?.id ?? "anchor",
): LinkGraphEdge[] {
  const sizeSnapshot = new Map<string, NodeMeasuredSize>(
    nodes.map((node) => [node.id, { width: 220, height: 120 }]),
  );
  const laneBuckets = new Map<ClassDiagramLane, ClassLaneEntry[]>();
  nodes.forEach((node) => {
    const lane = (node.metadata?.["layout.direction"] as ClassDiagramLane | undefined) ?? "RELATED";
    laneBuckets.set(lane, [...(laneBuckets.get(lane) ?? []), { node, lane, depth: node.id === anchorId ? 0 : 1 }]);
  });
  const topology: ClassDiagramTopology = {
    anchorId,
    nodes,
    edges,
    incoming: new Map(),
    outgoing: new Map(),
    incomingDistances: new Map([[anchorId, 0]]),
    outgoingDistances: new Map([[anchorId, 0]]),
    nodesWithLane: Array.from(laneBuckets.values()).flat(),
    laneBuckets,
  };
  const placement: ClassDiagramPlacement = {
    anchorId,
    nodes,
    nodeIndex: new Map(nodes.map((node) => [node.id, node])),
    laneBuckets,
  };
  return new ClassDiagramRoutingEngine().routeClassDiagramEdges(topology, placement, sizeSnapshot);
}

function routePoints(edge: LinkGraphEdge): GraphPosition[] {
  const section = edge.route?.sections[0];
  return section ? [section.startPoint, ...(section.bendPoints ?? []), section.endPoint] : [];
}

function nodeRect(node: LinkGraphNode) {
  return {
    left: node.position?.x ?? 0,
    right: (node.position?.x ?? 0) + classDiagramNodeCardWidth(node),
    top: node.position?.y ?? 0,
    bottom: (node.position?.y ?? 0) + Number(node.metadata?.["layout.estimatedHeight"] ?? 260),
  };
}

function segmentCrossesRect(startPoint: GraphPosition, endPoint: GraphPosition, rect: ReturnType<typeof nodeRect>): boolean {
  const inside = (point: GraphPosition): boolean =>
    point.x > rect.left + 0.5
    && point.x < rect.right - 0.5
    && point.y > rect.top + 0.5
    && point.y < rect.bottom - 0.5;

  if (inside(startPoint) || inside(endPoint)) {
    return true;
  }
  if (Math.abs(startPoint.x - endPoint.x) <= 0.5) {
    const x = startPoint.x;
    if (x <= rect.left + 0.5 || x >= rect.right - 0.5) {
      return false;
    }
    const top = Math.min(startPoint.y, endPoint.y);
    const bottom = Math.max(startPoint.y, endPoint.y);
    return Math.max(top, rect.top) < Math.min(bottom, rect.bottom);
  }
  if (Math.abs(startPoint.y - endPoint.y) <= 0.5) {
    const y = startPoint.y;
    if (y <= rect.top + 0.5 || y >= rect.bottom - 0.5) {
      return false;
    }
    const left = Math.min(startPoint.x, endPoint.x);
    const right = Math.max(startPoint.x, endPoint.x);
    return Math.max(left, rect.left) < Math.min(right, rect.right);
  }

  const crossesVerticalSide = (x: number): boolean => {
    const minX = Math.min(startPoint.x, endPoint.x);
    const maxX = Math.max(startPoint.x, endPoint.x);
    if (x <= minX + 0.5 || x >= maxX - 0.5) {
      return false;
    }
    const ratio = (x - startPoint.x) / (endPoint.x - startPoint.x);
    const y = startPoint.y + (endPoint.y - startPoint.y) * ratio;
    return y > rect.top + 0.5 && y < rect.bottom - 0.5;
  };
  const crossesHorizontalSide = (y: number): boolean => {
    const minY = Math.min(startPoint.y, endPoint.y);
    const maxY = Math.max(startPoint.y, endPoint.y);
    if (y <= minY + 0.5 || y >= maxY - 0.5) {
      return false;
    }
    const ratio = (y - startPoint.y) / (endPoint.y - startPoint.y);
    const x = startPoint.x + (endPoint.x - startPoint.x) * ratio;
    return x > rect.left + 0.5 && x < rect.right - 0.5;
  };

  return crossesVerticalSide(rect.left)
    || crossesVerticalSide(rect.right)
    || crossesHorizontalSide(rect.top)
    || crossesHorizontalSide(rect.bottom);
}

function routeCrossesNode(edge: LinkGraphEdge, node: LinkGraphNode): boolean {
  const points = routePoints(edge);
  if (points.length < 2) {
    return false;
  }
  const rect = nodeRect(node);
  return points.slice(1).some((point, index) => segmentCrossesRect(points[index]!, point, rect));
}

function expandedNodeRect(node: LinkGraphNode, padding: number) {
  const rect = nodeRect(node);
  return {
    left: rect.left - padding,
    right: rect.right + padding,
    top: rect.top - padding,
    bottom: rect.bottom + padding,
  };
}

function routeCrossesExpandedNode(edge: LinkGraphEdge, node: LinkGraphNode, padding: number): boolean {
  const points = routePoints(edge);
  if (points.length < 2) {
    return false;
  }
  const rect = expandedNodeRect(node, padding);
  return points.slice(1).some((point, index) => segmentCrossesRect(points[index]!, point, rect));
}

function routeIsOrthogonal(edge: LinkGraphEdge): boolean {
  const points = routePoints(edge);
  return points.length >= 2 && points.slice(1).every((point, index) => {
    const previous = points[index]!;
    return Math.abs(previous.x - point.x) <= 0.5 || Math.abs(previous.y - point.y) <= 0.5;
  });
}

function horizontalSegmentYs(edge: LinkGraphEdge): number[] {
  const points = routePoints(edge);
  return points.slice(1)
    .filter((point, index) => {
      const previous = points[index]!;
      return Math.abs(point.y - previous.y) <= 0.5 && Math.abs(point.x - previous.x) > 0.5;
    })
    .map((point) => point.y);
}

function primaryVerticalChannelX(edge: LinkGraphEdge): number {
  const points = routePoints(edge);
  for (let index = 1; index < points.length; index += 1) {
    const previous = points[index - 1]!;
    const point = points[index]!;
    if (Math.abs(previous.x - point.x) <= 0.5 && Math.abs(previous.y - point.y) > 0.5) {
      return Math.round(point.x);
    }
  }
  return Number.NaN;
}

function minimumChannelGap(channels: number[]): number {
  const sorted = [...channels].sort((left, right) => left - right);
  if (sorted.length < 2) {
    return Number.POSITIVE_INFINITY;
  }
  return sorted.slice(1).reduce((minimum, channel, index) =>
    Math.min(minimum, Math.abs(channel - sorted[index]!)),
  Number.POSITIVE_INFINITY);
}

function isEndpoint(point: GraphPosition, edge: LinkGraphEdge): boolean {
  const points = routePoints(edge);
  const startPoint = points[0];
  const endPoint = points[points.length - 1];
  return (!!startPoint && Math.abs(point.x - startPoint.x) <= 0.5 && Math.abs(point.y - startPoint.y) <= 0.5)
    || (!!endPoint && Math.abs(point.x - endPoint.x) <= 0.5 && Math.abs(point.y - endPoint.y) <= 0.5);
}

function routeCrossingCount(edges: LinkGraphEdge[]): number {
  let count = 0;
  for (let leftIndex = 0; leftIndex < edges.length; leftIndex += 1) {
    const leftEdge = edges[leftIndex]!;
    const leftPoints = routePoints(leftEdge);
    for (let rightIndex = leftIndex + 1; rightIndex < edges.length; rightIndex += 1) {
      const rightEdge = edges[rightIndex]!;
      const rightPoints = routePoints(rightEdge);
      for (let leftPointIndex = 1; leftPointIndex < leftPoints.length; leftPointIndex += 1) {
        for (let rightPointIndex = 1; rightPointIndex < rightPoints.length; rightPointIndex += 1) {
          const intersection = segmentIntersection(
            leftPoints[leftPointIndex - 1]!,
            leftPoints[leftPointIndex]!,
            rightPoints[rightPointIndex - 1]!,
            rightPoints[rightPointIndex]!,
          );
          if (
            intersection
            && !isEndpoint(intersection, leftEdge)
            && !isEndpoint(intersection, rightEdge)
          ) {
            count += 1;
          }
        }
      }
    }
  }
  return count;
}

function readabilityReport(nodes: LinkGraphNode[], edges: LinkGraphEdge[]) {
  return new ClassDiagramReadabilityScorer().scoreClassDiagramReadability({ nodes, edges }, new Map());
}

function segmentIntersection(
  leftStart: GraphPosition,
  leftEnd: GraphPosition,
  rightStart: GraphPosition,
  rightEnd: GraphPosition,
): GraphPosition | null {
  const leftVertical = Math.abs(leftStart.x - leftEnd.x) <= 0.5;
  const leftHorizontal = Math.abs(leftStart.y - leftEnd.y) <= 0.5;
  const rightVertical = Math.abs(rightStart.x - rightEnd.x) <= 0.5;
  const rightHorizontal = Math.abs(rightStart.y - rightEnd.y) <= 0.5;
  if (leftVertical && rightHorizontal) {
    return pointOnSegments({ x: leftStart.x, y: rightStart.y }, leftStart, leftEnd, rightStart, rightEnd);
  }
  if (leftHorizontal && rightVertical) {
    return pointOnSegments({ x: rightStart.x, y: leftStart.y }, leftStart, leftEnd, rightStart, rightEnd);
  }
  return null;
}

function pointOnSegments(
  point: GraphPosition,
  leftStart: GraphPosition,
  leftEnd: GraphPosition,
  rightStart: GraphPosition,
  rightEnd: GraphPosition,
): GraphPosition | null {
  return pointOnSegment(point, leftStart, leftEnd) && pointOnSegment(point, rightStart, rightEnd)
    ? point
    : null;
}

function pointOnSegment(point: GraphPosition, start: GraphPosition, end: GraphPosition): boolean {
  const minX = Math.min(start.x, end.x) - 0.5;
  const maxX = Math.max(start.x, end.x) + 0.5;
  const minY = Math.min(start.y, end.y) - 0.5;
  const maxY = Math.max(start.y, end.y) + 0.5;
  return point.x >= minX && point.x <= maxX && point.y >= minY && point.y <= maxY;
}

describe("layoutClassDiagramView", () => {
  it("checks class diagram port ordering independently for each node side", () => {
    const hub = {
      ...classNode("hub", "TargetHub"),
      position: { x: 620, y: 420 },
      metadata: { "layout.direction": "ANCHOR" },
    };
    const lowerLeftPeer = {
      ...classNode("lower-left", "LowerLeftPeer"),
      position: { x: 128, y: 780 },
      metadata: { "layout.direction": "INCOMING" },
    };
    const upperRightPeer = {
      ...classNode("upper-right", "UpperRightPeer"),
      position: { x: 1080, y: 128 },
      metadata: { "layout.direction": "OUTGOING" },
    };
    const edges: LinkGraphEdge[] = [
      {
        ...relation("a-left-peer", "lower-left", "hub", "USES_TYPE"),
        sourceHandle: "source-right-1",
        targetHandle: "target-left-1",
        route: {
          sections: [{
            startPoint: { x: 376, y: 910 },
            endPoint: { x: 620, y: 520 },
            bendPoints: [{ x: 500, y: 910 }, { x: 500, y: 520 }],
          }],
        },
      },
      {
        ...relation("z-right-peer", "upper-right", "hub", "USES_TYPE"),
        sourceHandle: "source-left-1",
        targetHandle: "target-right-1",
        route: {
          sections: [{
            startPoint: { x: 1080, y: 258 },
            endPoint: { x: 868, y: 520 },
            bendPoints: [{ x: 980, y: 258 }, { x: 980, y: 520 }],
          }],
        },
      },
    ];

    expect(readabilityReport([hub, lowerLeftPeer, upperRightPeer], edges).metrics.portOrderMatchesTargetOrder).toBe(true);
  });

  it("places the anchor between incoming and outgoing type neighborhoods", async () => {
    const nodes = [
      classNode("consumer", "Consumer"),
      classNode("anchor", "ApplicationFeedbackLevel"),
      classNode("dependency", "GeneratedCodeDraftsPresentation"),
      classNode("base", "BasePresentation"),
    ];
    const edges = [
      relation("consumer-anchor", "consumer", "anchor", "INJECTS"),
      relation("anchor-dependency", "anchor", "dependency", "USES_TYPE"),
      relation("anchor-base", "anchor", "base", "EXTENDS"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));

    expect(byId.get("anchor")?.metadata?.["layout.direction"]).toBe("ANCHOR");
    expect(byId.get("consumer")?.metadata?.["layout.direction"]).toBe("INCOMING");
    expect(byId.get("dependency")?.metadata?.["layout.direction"]).toBe("OUTGOING");
    expect(byId.get("base")?.metadata?.["layout.direction"]).toBe("PARENT");
    expect(byId.get("consumer")?.position?.x).toBeLessThan(byId.get("anchor")?.position?.x ?? 0);
    expect(byId.get("dependency")?.position?.x).toBeGreaterThan(byId.get("anchor")?.position?.x ?? 0);
    expect(byId.get("base")?.position?.y).toBeLessThan(byId.get("anchor")?.position?.y ?? 0);
  });

  it("stamps class diagram presentation roles and zone lanes on laid out nodes", async () => {
    const nodes = [
      classNode("caller", "OrderController"),
      {
        ...classNode("anchor", "OrderService"),
        metadata: {
          "presentation.role": "ANCHOR",
          "presentation.laneId": "anchor",
          "presentation.compact": "false",
        },
      },
      {
        ...classNode("contract", "PaymentPort"),
        type: "INTERFACE" as const,
        metadata: {
          "jvm.class.abstract": "true",
        },
      },
      {
        ...classNode("collaborator", "OrderRepository"),
        metadata: {
          "presentation.role": "OUTPUT",
          "presentation.laneId": "output",
          "presentation.compact": "false",
        },
      },
      {
        ...classNode("result", "OrderResult"),
        type: "RECORD" as const,
      },
      classNode("neutral", "AuditEnvelope"),
    ];
    const edges = [
      relation("caller-anchor", "caller", "anchor", "INJECTS"),
      relation("anchor-contract", "anchor", "contract", "IMPLEMENTS", "REALIZATION"),
      relation("anchor-collaborator", "anchor", "collaborator", "USES_TYPE", "ASSOCIATION"),
      relation("collaborator-result", "collaborator", "result", "USES_TYPE", "DEPENDENCY"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));

    expect(byId.get("anchor")?.metadata).toMatchObject({
      "presentation.role": "ANCHOR",
      "presentation.laneId": "anchor",
      "presentation.compact": "false",
    });
    expect(byId.get("contract")?.metadata).toMatchObject({
      "presentation.role": "INTERFACE",
      "presentation.laneId": "abstraction",
      "presentation.compact": "true",
    });
    expect(byId.get("caller")?.metadata).toMatchObject({
      "presentation.role": "CALLER",
      "presentation.laneId": "caller",
    });
    expect(byId.get("collaborator")?.metadata).toMatchObject({
      "presentation.role": "COLLABORATOR",
      "presentation.laneId": "collaborator",
      "presentation.compact": "true",
    });
    expect(byId.get("result")?.metadata).toMatchObject({
      "presentation.role": "OUTPUT",
      "presentation.laneId": "output",
    });
    expect(byId.get("neutral")?.metadata).toMatchObject({
      "presentation.role": "TYPE",
      "presentation.laneId": "collaborator",
    });
  });

  it("routes class diagram edges through semantic side ports instead of the node center", async () => {
    const nodes = [classNode("anchor"), classNode("dependency"), classNode("base")];
    const edges = [
      relation("anchor-dependency", "anchor", "dependency", "USES_TYPE"),
      relation("anchor-base", "anchor", "base", "EXTENDS"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const usesEdge = laidOut.edges.find((edge) => edge.id === "anchor-dependency");
    const extendsEdge = laidOut.edges.find((edge) => edge.id === "anchor-base");

    expect(usesEdge).toMatchObject({
      sourceHandle: "source-right-3",
      targetHandle: "target-left-3",
      metadata: expect.objectContaining({
        "layout.route": "class-diagram-lane",
      }),
    });
    expect(extendsEdge).toMatchObject({
      sourceHandle: "source-top",
      targetHandle: "target-bottom",
    });
    expect(routeIsOrthogonal(usesEdge!)).toBe(true);
    expect(routePoints(usesEdge!).length).toBeGreaterThanOrEqual(2);
    expect(routePoints(usesEdge!).every((point) => point.x >= ((laidOut.nodes.find((node) => node.id === "anchor")?.position?.x ?? 0) + classDiagramNodeCardWidth()))).toBe(true);
    expect(extendsEdge?.route?.sections[0]?.bendPoints?.length).toBe(3);
  });

  it("uses UML relation metadata instead of raw JVM type usage when placing hierarchy nodes", async () => {
    const nodes = [classNode("anchor"), classNode("contract", "WorkerContract")];
    const edges = [
      relation("anchor-contract", "anchor", "contract", "USES_TYPE", "REALIZATION"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const contract = laidOut.nodes.find((node) => node.id === "contract");
    const edge = laidOut.edges.find((candidate) => candidate.id === "anchor-contract");

    expect(contract?.metadata?.["layout.direction"]).toBe("PARENT");
    expect(edge).toMatchObject({
      sourceHandle: "source-top",
      targetHandle: "target-bottom",
    });
  });

  it("routes horizontally separated hierarchy relations through side ports", () => {
    const nodes = [
      placedClassNode("anchor", "ANCHOR", { x: 500, y: 360 }),
      placedClassNode("contract", "PARENT", { x: 860, y: 160 }, 0, "WorkerContract"),
    ];
    const edges = [
      relation("anchor-contract", "anchor", "contract", "USES_TYPE", "REALIZATION"),
    ];

    const [edge] = routePlacedClassEdges(nodes, edges, "anchor");

    expect(edge).toMatchObject({
      sourceHandle: expect.stringMatching(/^source-right(?:-\d+)?$/),
      targetHandle: expect.stringMatching(/^target-left(?:-\d+)?$/),
      metadata: expect.objectContaining({
        "layout.sourcePort": expect.stringMatching(/^source-right(?:-\d+)?$/),
        "layout.targetPort": expect.stringMatching(/^target-left(?:-\d+)?$/),
      }),
    });
    expect(routeIsOrthogonal(edge!)).toBe(true);
  });

  it("keeps near-column hierarchy relations on vertical ports", () => {
    const nodes = [
      placedClassNode("contract", "PARENT", { x: 500, y: 120 }, 0, "WorkerContract"),
      placedClassNode("anchor", "ANCHOR", { x: 500, y: 360 }),
    ];
    const edges = [
      relation("anchor-contract", "anchor", "contract", "USES_TYPE", "REALIZATION"),
    ];

    const [edge] = routePlacedClassEdges(nodes, edges, "anchor");

    expect(edge).toMatchObject({
      sourceHandle: "source-top",
      targetHandle: "target-bottom",
    });
    expect(routeIsOrthogonal(edge!)).toBe(true);
  });

  it("routes horizontally separated structural relations through side ports before vertical fallback", () => {
    const nodes = [
      placedClassNode("anchor", "ANCHOR", { x: 500, y: 360 }),
      placedClassNode("repository", "RELATED", { x: 880, y: 120 }, 0, "TaskRepository"),
    ];
    const edges = [
      relation("anchor-repository", "anchor", "repository", "USES_TYPE", "ASSOCIATION"),
    ];

    const [edge] = routePlacedClassEdges(nodes, edges, "anchor");

    expect(edge).toMatchObject({
      sourceHandle: expect.stringMatching(/^source-right(?:-\d+)?$/),
      targetHandle: expect.stringMatching(/^target-left(?:-\d+)?$/),
    });
    expect(routeIsOrthogonal(edge!)).toBe(true);
  });

  it("separates mixed relation routes instead of stacking them on the same lane", async () => {
    const nodes = [
      classNode("consumer", "GraphEditorApplicationService"),
      classNode("anchor", "ConfirmedDraftArtifactWriter"),
      classNode("intent", "ConfirmedIntentArtifact"),
      classNode("store", "ArtifactStore"),
      classNode("pruner", "ArtifactStorePruner"),
    ];
    const edges = [
      relation("consumer-anchor", "consumer", "anchor", "INJECTS"),
      relation("anchor-intent", "anchor", "intent", "USES_TYPE", "DEPENDENCY"),
      relation("anchor-store", "anchor", "store", "USES_TYPE", "DEPENDENCY"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE"),
      relation("store-pruner", "store", "pruner", "USES_TYPE"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const routeSignatures = laidOut.edges.map((edge) => JSON.stringify(edge.route));
    const dataNodes = laidOut.nodes
      .filter((node) => node.metadata?.["layout.direction"] === "DATA")
      .sort((left, right) => (left.position?.x ?? 0) - (right.position?.x ?? 0));

    expect(new Set(routeSignatures).size).toBe(routeSignatures.length);
    expect(laidOut.nodes.find((node) => node.id === "pruner")?.metadata?.["layout.direction"]).toBe("OUTGOING");
    for (const column of ["0", "1"]) {
      const columnNodes = dataNodes
        .filter((node) => node.metadata?.["layout.column"] === column)
        .sort((left, right) => (left.position?.y ?? 0) - (right.position?.y ?? 0));
      for (let index = 1; index < columnNodes.length; index += 1) {
        const previous = columnNodes[index - 1];
        const current = columnNodes[index];
        const previousBottom = (previous?.position?.y ?? 0) + Number(previous?.metadata?.["layout.estimatedHeight"] ?? 0);
        expect(current?.position?.y ?? 0).toBeGreaterThanOrEqual(previousBottom + 92);
      }
    }
  });

  it("stacks tall UML cards without vertical overlap before DOM measurements arrive", async () => {
    const nodes = [
      classNode("anchor", "TaskRunner"),
      tallClassNode("consumer-a", "TaskConsumerA"),
      tallClassNode("consumer-b", "TaskConsumerB"),
      tallClassNode("consumer-c", "TaskConsumerC"),
    ];
    const edges = [
      relation("consumer-a-anchor", "consumer-a", "anchor", "INJECTS"),
      relation("consumer-b-anchor", "consumer-b", "anchor", "INJECTS"),
      relation("consumer-c-anchor", "consumer-c", "anchor", "INJECTS"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const incomingNodes = laidOut.nodes
      .filter((node) => node.metadata?.["layout.direction"] === "INCOMING")
      .sort((left, right) => (left.position?.y ?? 0) - (right.position?.y ?? 0));

    expect(incomingNodes).toHaveLength(3);
    for (let index = 1; index < incomingNodes.length; index += 1) {
      const previous = incomingNodes[index - 1];
      const current = incomingNodes[index];
      const previousBottom = (previous?.position?.y ?? 0) + Number(previous?.metadata?.["layout.estimatedHeight"] ?? 0);
      expect(current?.position?.y ?? 0).toBeGreaterThanOrEqual(previousBottom + 92);
    }
  });

  it("routes class diagram relation lines outside non-endpoint UML cards", async () => {
    const nodes = [
      tallClassNode("incoming-a", "CommandPalette"),
      tallClassNode("incoming-b", "ShortcutAction"),
      tallClassNode("anchor", "ApplicationFeedbackLevel"),
      tallClassNode("outgoing", "FeedbackPresenter"),
      tallClassNode("enum-a", "FeedbackSeverity"),
      tallClassNode("enum-b", "FeedbackIcon"),
      tallClassNode("enum-c", "FeedbackPalette"),
    ];
    const edges = [
      relation("incoming-a-anchor", "incoming-a", "anchor", "INJECTS"),
      relation("incoming-b-anchor", "incoming-b", "anchor", "INJECTS"),
      relation("anchor-outgoing", "anchor", "outgoing", "USES_TYPE", "DEPENDENCY"),
      relation("anchor-enum-a", "anchor", "enum-a", "USES_TYPE"),
      relation("enum-a-enum-b", "enum-a", "enum-b", "USES_TYPE"),
      relation("enum-b-enum-c", "enum-b", "enum-c", "USES_TYPE"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });

    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesNode(edge, node),
      );
      expect(crossedNode, `${edge.id} crosses ${crossedNode?.id}`).toBeUndefined();
    });
    expect(routeCrossingCount(laidOut.edges)).toBe(0);
  });

  it("preserves explicit class diagram node positions while rerouting relation lines", async () => {
    const nodes = [
      {
        ...tallClassNode("caller", "OrderController"),
        position: { x: 120, y: 180 },
        metadata: {
          "ui.x": "120",
          "ui.y": "180",
        },
      },
      {
        ...tallClassNode("anchor", "OrderService"),
        position: { x: 520, y: 260 },
        metadata: {
          "ui.x": "520",
          "ui.y": "260",
        },
      },
      {
        ...tallClassNode("repository", "OrderRepository"),
        position: { x: 940, y: 160 },
        metadata: {
          "ui.x": "940",
          "ui.y": "160",
        },
      },
    ];
    const edges = [
      relation("caller-anchor", "caller", "anchor", "INJECTS"),
      relation("anchor-repository", "anchor", "repository", "USES_TYPE", "ASSOCIATION"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "position",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));

    expect(byId.get("caller")?.position).toEqual({ x: 120, y: 180 });
    expect(byId.get("anchor")?.position).toEqual({ x: 520, y: 260 });
    expect(byId.get("repository")?.position).toEqual({ x: 940, y: 160 });
    laidOut.edges.forEach((edge) => {
      expect(edge.metadata?.["layout.routeMode"]).toBe("stored");
      expect(routeIsOrthogonal(edge)).toBe(true);
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesExpandedNode(edge, node, 16),
      );
      expect(crossedNode, `${edge.id} crosses or hugs ${crossedNode?.id}`).toBeUndefined();
    });
  });

  it("repairs edge-to-edge crossings across independent lane relations", async () => {
    const nodes = [
      tallClassNode("left-a", "BuildIndexWorkflow"),
      tallClassNode("left-b", "GraphEditorCommandRouter"),
      tallClassNode("anchor", "GraphEditorApplicationService"),
      tallClassNode("right-a", "ClassDiagramWorkflow"),
      tallClassNode("right-b", "ArchitectureGraphWorkflow"),
    ];
    const edges = [
      relation("left-a-anchor", "left-a", "anchor", "INJECTS"),
      relation("left-b-anchor", "left-b", "anchor", "INJECTS"),
      relation("anchor-right-a", "anchor", "right-a", "USES_TYPE"),
      relation("anchor-right-b", "anchor", "right-b", "USES_TYPE"),
      relation("left-a-right-b", "left-a", "right-b", "USES_TYPE"),
      relation("left-b-right-a", "left-b", "right-a", "USES_TYPE"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });

    expect(routeCrossingCount(laidOut.edges)).toBeLessThanOrEqual(1);
    expect(laidOut.edges.every((edge) => edge.metadata?.["layout.routeMode"] === "stored")).toBe(true);
    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesNode(edge, node),
      );
      expect(crossedNode, `${edge.id} crosses ${crossedNode?.id}`).toBeUndefined();
    });
  });

  it("keeps real draft artifact writer skip routes clear of intermediate UML cards", async () => {
    const nodes = [
      tallClassNode("coordinator", "ConfirmedDraftChangeCoordinator"),
      tallClassNode("anchor", "ConfirmedDraftArtifactWriter"),
      tallClassNode("pruner", "ArtifactStorePruner"),
      tallClassNode("store", "ArtifactStore"),
      tallClassNode("entry", "DraftWorkbenchEntry"),
    ];
    const edges = [
      relation("coordinator-anchor", "coordinator", "anchor", "INJECTS"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE", "ASSOCIATION"),
      relation("anchor-store", "anchor", "store", "USES_TYPE", "ASSOCIATION"),
      relation("pruner-store", "pruner", "store", "USES_TYPE", "ASSOCIATION"),
      relation("coordinator-pruner", "coordinator", "pruner", "USES_TYPE", "DEPENDENCY"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });

    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesExpandedNode(edge, node, 16),
      );
      expect(crossedNode, `${edge.id} crosses or hugs ${crossedNode?.id}`).toBeUndefined();
    });
    expect(routeCrossingCount(laidOut.edges)).toBe(0);
  });

  it("keeps small class neighborhoods compact while preserving side-lane route channels", async () => {
    const nodes = [
      tallClassNode("coordinator", "ConfirmedDraftChangeCoordinator"),
      tallClassNode("anchor", "ConfirmedDraftArtifactWriter"),
      tallClassNode("pruner", "ArtifactStorePruner"),
      tallClassNode("entry", "DraftWorkbenchEntry"),
    ];
    const edges = [
      relation("coordinator-anchor", "coordinator", "anchor", "INJECTS"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE", "ASSOCIATION"),
      relation("anchor-entry", "anchor", "entry", "USES_TYPE", "DEPENDENCY"),
      relation("coordinator-entry", "coordinator", "entry", "USES_TYPE", "DEPENDENCY"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const incomingGap = (byId.get("anchor")?.position?.x ?? 0) - (byId.get("coordinator")?.position?.x ?? 0);
    const outgoingGap = (byId.get("pruner")?.position?.x ?? 0) - (byId.get("anchor")?.position?.x ?? 0);

    expect(incomingGap).toBeLessThanOrEqual(classDiagramNodeCardWidth() + 260);
    expect(outgoingGap).toBeLessThanOrEqual(classDiagramNodeCardWidth() + 360);
    laidOut.edges.forEach((edge) => {
      expect(edge.metadata?.["layout.routeMode"]).toBe("stored");
      expect(routeIsOrthogonal(edge)).toBe(true);
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesExpandedNode(edge, node, 16),
      );
      expect(crossedNode, `${edge.id} crosses or hugs ${crossedNode?.id}`).toBeUndefined();
    });
    expect(routeCrossingCount(laidOut.edges)).toBe(0);
  });

  it("routes incoming-to-outgoing secondary dependencies on a lower readability rail", async () => {
    const nodes = [
      tallClassNode("coordinator", "ConfirmedDraftChangeCoordinator"),
      tallClassNode("anchor", "ConfirmedDraftArtifactWriter"),
      tallClassNode("pruner", "ArtifactStorePruner"),
      tallClassNode("entry", "DraftWorkbenchEntry"),
    ];
    const edges = [
      relation("coordinator-anchor", "coordinator", "anchor", "INJECTS"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE", "ASSOCIATION"),
      relation("anchor-entry", "anchor", "entry", "USES_TYPE", "DEPENDENCY"),
      relation("coordinator-entry", "coordinator", "entry", "USES_TYPE", "DEPENDENCY"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const mainEdge = laidOut.edges.find((edge) => edge.id === "coordinator-anchor")!;
    const crossLaneEdge = laidOut.edges.find((edge) => edge.id === "coordinator-entry")!;
    const anchorEdges = laidOut.edges.filter((edge) => edge.source === "anchor" || edge.target === "anchor");
    const primaryLaneBottom = Math.max(
      ...["coordinator", "anchor", "pruner", "entry"].map((nodeId) => nodeRect(byId.get(nodeId)!).bottom),
    );
    const railY = Math.max(...horizontalSegmentYs(crossLaneEdge));

    expect(mainEdge.sourceHandle).toBe("source-right-3");
    expect(mainEdge.targetHandle).toBe("target-left-3");
    expect(routePoints(mainEdge).length).toBe(2);
    expect(crossLaneEdge.sourceHandle).toBe("source-bottom");
    expect(crossLaneEdge.targetHandle).toBe("target-bottom");
    expect(routeIsOrthogonal(crossLaneEdge)).toBe(true);
    expect(routePoints(crossLaneEdge).length).toBe(4);
    expect(railY).toBeGreaterThanOrEqual(primaryLaneBottom + 56);
    anchorEdges.forEach((edge) => {
      expect(Math.max(...horizontalSegmentYs(edge))).toBeLessThan(primaryLaneBottom + 56);
    });
    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesExpandedNode(edge, node, 16),
      );
      expect(crossedNode, `${edge.id} crosses or hugs ${crossedNode?.id}`).toBeUndefined();
    });
    expect(routeCrossingCount(laidOut.edges)).toBe(0);
  });

  it("fans out anchor outgoing relations from separate side ports in the full class diagram", async () => {
    const nodes = [
      tallClassNode("coordinator", "ConfirmedDraftChangeCoordinator"),
      tallClassNode("service", "GraphEditorApplicationService"),
      tallClassNode("anchor", "ConfirmedDraftArtifactWriter"),
      tallClassNode("store", "ArtifactStore"),
      tallClassNode("pruner", "ArtifactStorePruner"),
      tallClassNode("entry", "DraftWorkbenchEntry"),
      tallClassNode("intent", "ConfirmedIntentArtifact"),
    ];
    const edges = [
      relation("coordinator-anchor", "coordinator", "anchor", "INJECTS"),
      relation("service-anchor", "service", "anchor", "INJECTS"),
      relation("anchor-store", "anchor", "store", "USES_TYPE", "DEPENDENCY"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE"),
      relation("anchor-entry", "anchor", "entry", "USES_TYPE"),
      relation("anchor-intent", "anchor", "intent", "USES_TYPE", "DEPENDENCY"),
      relation("store-pruner", "store", "pruner", "USES_TYPE"),
      relation("store-intent", "store", "intent", "USES_TYPE"),
      relation("pruner-entry", "pruner", "entry", "USES_TYPE"),
      relation("intent-entry", "intent", "entry", "USES_TYPE"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const anchorOutgoingEdges = laidOut.edges
      .filter((edge) => edge.source === "anchor")
      .sort((left, right) => (left.targetHandle ?? "").localeCompare(right.targetHandle ?? ""));
    const sourceHandles = anchorOutgoingEdges.map((edge) => edge.sourceHandle);
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const anchorRight = nodeRect(byId.get("anchor")!).right;

    expect(anchorOutgoingEdges).toHaveLength(4);
    expect(new Set(sourceHandles).size).toBe(anchorOutgoingEdges.length);
    anchorOutgoingEdges.forEach((edge) => {
      const targetLeft = nodeRect(byId.get(edge.target)!).left;
      expect(routeIsOrthogonal(edge)).toBe(true);
      expect(routePoints(edge).length).toBeLessThanOrEqual(4);
      routePoints(edge).forEach((point) => {
        expect(point.x).toBeGreaterThanOrEqual(anchorRight);
        expect(point.x).toBeLessThanOrEqual(targetLeft);
      });
    });
    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesNode(edge, node),
      );
      expect(crossedNode, `${edge.id} crosses ${crossedNode?.id}`).toBeUndefined();
    });
  });

  it("allocates separate whole-route channels for dense anchor fanout", async () => {
    const nodes = [
      tallClassNode("left-a", "SelectedMaterialConfigValidatorTest"),
      tallClassNode("left-b", "SelectedMaterialConfigValidator"),
      tallClassNode("parent", "MaterialConfigProvider"),
      tallClassNode("anchor", "MaterialVersionConfigValidator"),
      tallClassNode("provider", "PlatformProvider"),
      tallClassNode("layout-ref", "LayoutRef"),
      tallClassNode("metadata-data", "MetadataData"),
      tallClassNode("metadata-image", "MetadataImage"),
      tallClassNode("metadata-manager", "MetadataManager"),
    ];
    const edges = [
      relation("left-a-anchor", "left-a", "anchor", "INJECTS"),
      relation("left-b-anchor", "left-b", "anchor", "INJECTS"),
      relation("anchor-parent", "anchor", "parent", "IMPLEMENTS", "REALIZATION"),
      relation("anchor-provider", "anchor", "provider", "USES_TYPE", "ASSOCIATION"),
      relation("anchor-layout-ref", "anchor", "layout-ref", "USES_TYPE", "ASSOCIATION"),
      relation("anchor-metadata-data", "anchor", "metadata-data", "USES_TYPE", "DEPENDENCY"),
      relation("anchor-metadata-image", "anchor", "metadata-image", "USES_TYPE", "DEPENDENCY"),
      relation("anchor-metadata-manager", "anchor", "metadata-manager", "USES_TYPE", "DEPENDENCY"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const anchorRight = nodeRect(byId.get("anchor")!).right;
    const anchorLeft = nodeRect(byId.get("anchor")!).left;
    const anchorOutgoingEdges = laidOut.edges
      .filter((edge) => edge.source === "anchor" && byId.get(edge.target)?.metadata?.["layout.direction"] === "OUTGOING")
      .sort((left, right) => (left.sourceHandle ?? "").localeCompare(right.sourceHandle ?? ""));
    const anchorIncomingEdges = laidOut.edges
      .filter((edge) => edge.target === "anchor" && byId.get(edge.source)?.metadata?.["layout.direction"] === "INCOMING")
      .sort((left, right) => (left.targetHandle ?? "").localeCompare(right.targetHandle ?? ""));

    expect(anchorOutgoingEdges).toHaveLength(5);
    expect(anchorIncomingEdges).toHaveLength(2);
    const outgoingChannels = anchorOutgoingEdges.map(primaryVerticalChannelX);
    const incomingChannels = anchorIncomingEdges.map(primaryVerticalChannelX);
    const firstOutgoingGap = Math.min(...anchorOutgoingEdges.map((edge) =>
      (byId.get(edge.target)?.position?.x ?? 0) - (byId.get("anchor")?.position?.x ?? 0),
    ));
    expect(outgoingChannels.every(Number.isFinite)).toBe(true);
    expect(incomingChannels.every(Number.isFinite)).toBe(true);
    expect(new Set(outgoingChannels).size).toBe(anchorOutgoingEdges.length);
    expect(new Set(incomingChannels).size).toBe(anchorIncomingEdges.length);
    expect(firstOutgoingGap).toBeLessThanOrEqual(classDiagramNodeCardWidth() + 560);
    expect(minimumChannelGap(outgoingChannels)).toBeGreaterThanOrEqual(60);
    expect(minimumChannelGap(incomingChannels)).toBeGreaterThanOrEqual(72);
    outgoingChannels.forEach((channelX) => expect(channelX).toBeGreaterThan(anchorRight + 40));
    incomingChannels.forEach((channelX) => expect(channelX).toBeLessThan(anchorLeft - 40));
    anchorOutgoingEdges.forEach((edge) => {
      expect(edge.sourceHandle).toMatch(/^source-right(?:-\d+)?$/);
      expect(edge.targetHandle).toMatch(/^target-left(?:-\d+)?$/);
      expect(routeIsOrthogonal(edge)).toBe(true);
    });
    anchorIncomingEdges.forEach((edge) => {
      expect(edge.sourceHandle).toMatch(/^source-right(?:-\d+)?$/);
      expect(edge.targetHandle).toMatch(/^target-left(?:-\d+)?$/);
      expect(routeIsOrthogonal(edge)).toBe(true);
    });
    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesNode(edge, node),
      );
      expect(crossedNode, `${edge.id} crosses ${crossedNode?.id}`).toBeUndefined();
    });
  });

  it("keeps anchor direct routes and outgoing internal routes on separate channels", async () => {
    const nodes = [
      tallClassNode("coordinator", "ConfirmedDraftChangeCoordinator"),
      tallClassNode("anchor", "ConfirmedDraftArtifactWriter"),
      tallClassNode("pruner", "ArtifactStorePruner"),
      tallClassNode("store", "ArtifactStore"),
      tallClassNode("entry", "DraftWorkbenchEntry"),
    ];
    const edges = [
      relation("coordinator-anchor", "coordinator", "anchor", "INJECTS"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE", "ASSOCIATION"),
      relation("anchor-store", "anchor", "store", "USES_TYPE", "ASSOCIATION"),
      relation("anchor-entry", "anchor", "entry", "USES_TYPE", "DEPENDENCY"),
      relation("pruner-store", "pruner", "store", "USES_TYPE", "ASSOCIATION"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const anchorRight = nodeRect(byId.get("anchor")!).right;
    const rightLaneNodes = laidOut.nodes.filter((node) => node.metadata?.["layout.direction"] === "OUTGOING");
    const rightLaneRight = Math.max(...rightLaneNodes.map((node) => nodeRect(node).right));
    const anchorOutgoingEdges = laidOut.edges.filter((edge) => edge.source === "anchor");
    const prunerStore = laidOut.edges.find((edge) => edge.id === "pruner-store")!;

    expect(anchorOutgoingEdges).toHaveLength(3);
    anchorOutgoingEdges.forEach((edge) => {
      const targetLeft = nodeRect(byId.get(edge.target)!).left;
      const xs = routePoints(edge).map((point) => point.x);
      expect(routeIsOrthogonal(edge)).toBe(true);
      expect(edge.sourceHandle).toMatch(/^source-right(?:-\d+)?$/);
      expect(edge.targetHandle).toMatch(/^target-left(?:-\d+)?$/);
      expect(Math.min(...xs)).toBeGreaterThanOrEqual(anchorRight);
      expect(Math.max(...xs)).toBeLessThanOrEqual(targetLeft);
    });
    expect(routeCrossingCount(anchorOutgoingEdges)).toBe(0);

    const internalXs = routePoints(prunerStore).map((point) => point.x);
    expect(routeIsOrthogonal(prunerStore)).toBe(true);
    expect(prunerStore.sourceHandle).toMatch(/^source-right(?:-\d+)?$/);
    expect(prunerStore.targetHandle).toMatch(/^target-right(?:-\d+)?$/);
    expect(prunerStore.sourceHandle).not.toBe("source-bottom");
    expect(prunerStore.targetHandle).not.toBe("target-top");
    expect(Math.max(...internalXs)).toBeGreaterThan(rightLaneRight);
    expect(Math.max(...internalXs)).toBeLessThanOrEqual(rightLaneRight + 72);

    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesNode(edge, node),
      );
      expect(crossedNode, `${edge.id} crosses ${crossedNode?.id}`).toBeUndefined();
    });
  });

  it("places chained outgoing UML dependencies into readable columns", async () => {
    const nodes = [
      tallClassNode("anchor", "ConfirmedDraftArtifactWriter"),
      tallClassNode("store", "ArtifactStore"),
      tallClassNode("pruner", "ArtifactStorePruner"),
      tallClassNode("entry", "DraftWorkbenchEntry"),
    ];
    const edges = [
      relation("anchor-store", "anchor", "store", "USES_TYPE", "DEPENDENCY"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE"),
      relation("anchor-entry", "anchor", "entry", "USES_TYPE"),
      relation("store-pruner", "store", "pruner", "USES_TYPE"),
      relation("pruner-entry", "pruner", "entry", "USES_TYPE"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const store = byId.get("store")!;
    const pruner = byId.get("pruner")!;
    const entry = byId.get("entry")!;
    expect(store.metadata?.["layout.column"]).toBe("0");
    expect(Number(pruner.metadata?.["layout.column"])).toBeGreaterThan(Number(store.metadata?.["layout.column"]));
    expect(Number(entry.metadata?.["layout.column"])).toBeGreaterThan(Number(pruner.metadata?.["layout.column"]));
    expect(store.position?.x ?? 0).toBeLessThan(pruner.position?.x ?? 0);
    expect(pruner.position?.x ?? 0).toBeLessThan(entry.position?.x ?? 0);
    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesNode(edge, node),
      );
      expect(crossedNode, `${edge.id} crosses ${crossedNode?.id}`).toBeUndefined();
    });
  });

  it("routes cross-column outgoing UML relations through side ports", async () => {
    const nodes = [
      tallClassNode("anchor", "ConfirmedDraftArtifactWriter"),
      tallClassNode("store", "ArtifactStore"),
      tallClassNode("pruner", "ArtifactStorePruner"),
      tallClassNode("entry", "DraftWorkbenchEntry"),
    ];
    const edges = [
      relation("anchor-store", "anchor", "store", "USES_TYPE"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE"),
      relation("anchor-entry", "anchor", "entry", "USES_TYPE"),
      relation("store-pruner", "store", "pruner", "USES_TYPE"),
      relation("pruner-entry", "pruner", "entry", "USES_TYPE"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const storePruner = laidOut.edges.find((edge) => edge.id === "store-pruner")!;
    const prunerEntry = laidOut.edges.find((edge) => edge.id === "pruner-entry")!;

    [storePruner, prunerEntry].forEach((edge) => {
      const points = routePoints(edge);
      expect(points.length).toBeGreaterThanOrEqual(4);
      expect(routeIsOrthogonal(edge)).toBe(true);
      expect(edge.sourceHandle).toMatch(/^source-right(?:-\d+)?$/);
      expect(edge.targetHandle).toMatch(/^target-left(?:-\d+)?$/);
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesNode(edge, node),
      );
      expect(crossedNode, `${edge.id} crosses ${crossedNode?.id}`).toBeUndefined();
    });
  });

  it("routes skip-level outgoing UML relations without crossing intermediate cards", async () => {
    const nodes = [
      tallClassNode("anchor", "ConfirmedDraftArtifactWriter"),
      tallClassNode("store", "ArtifactStore"),
      tallClassNode("pruner", "ArtifactStorePruner"),
      tallClassNode("entry", "DraftWorkbenchEntry"),
    ];
    const edges = [
      relation("anchor-store", "anchor", "store", "USES_TYPE"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE"),
      relation("anchor-entry", "anchor", "entry", "USES_TYPE"),
      relation("store-pruner", "store", "pruner", "USES_TYPE"),
      relation("pruner-entry", "pruner", "entry", "USES_TYPE"),
      relation("store-entry", "store", "entry", "USES_TYPE"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const storeEntry = laidOut.edges.find((edge) => edge.id === "store-entry")!;
    const points = routePoints(storeEntry);

    expect(points.length).toBeGreaterThan(2);
    expect(routeIsOrthogonal(storeEntry)).toBe(true);
    expect(storeEntry.sourceHandle).toMatch(/^source-right(?:-\d+)?$/);
    expect(storeEntry.targetHandle).toMatch(/^target-left(?:-\d+)?$/);
    expect(Number(byId.get("entry")?.metadata?.["layout.column"])).toBeGreaterThan(Number(byId.get("store")?.metadata?.["layout.column"]));
    expect(routeCrossesNode(storeEntry, byId.get("pruner")!)).toBe(false);
    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesNode(edge, node),
      );
      expect(crossedNode, `${edge.id} crosses ${crossedNode?.id}`).toBeUndefined();
    });
  });

  it("keeps the full class diagram routes local instead of drawing large enclosing boxes", async () => {
    const nodes = [
      tallClassNode("service", "GraphEditorApplicationService"),
      tallClassNode("coordinator", "ConfirmedDraftChangeCoordinator"),
      tallClassNode("anchor", "ConfirmedDraftArtifactWriter"),
      tallClassNode("pruner", "ArtifactStorePruner"),
      tallClassNode("store", "ArtifactStore"),
      tallClassNode("intent", "ConfirmedIntentArtifact"),
      tallClassNode("entry", "DraftWorkbenchEntry"),
    ];
    const edges = [
      relation("service-anchor", "service", "anchor", "INJECTS"),
      relation("coordinator-anchor", "coordinator", "anchor", "INJECTS"),
      relation("anchor-pruner", "anchor", "pruner", "USES_TYPE"),
      relation("anchor-store", "anchor", "store", "USES_TYPE", "DEPENDENCY"),
      relation("anchor-intent", "anchor", "intent", "USES_TYPE", "DEPENDENCY"),
      relation("anchor-entry", "anchor", "entry", "USES_TYPE"),
      relation("store-pruner", "store", "pruner", "USES_TYPE"),
      relation("pruner-store", "pruner", "store", "USES_TYPE", "DEPENDENCY"),
      relation("store-intent", "store", "intent", "USES_TYPE"),
      relation("intent-entry", "intent", "entry", "USES_TYPE"),
      relation("pruner-entry", "pruner", "entry", "USES_TYPE"),
      relation("store-entry", "store", "entry", "USES_TYPE"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const rightLaneNodes = laidOut.nodes.filter((node) => node.metadata?.["layout.direction"] === "OUTGOING");
    const rightLaneRight = Math.max(...rightLaneNodes.map((node) => nodeRect(node).right));
    const anchorRight = nodeRect(byId.get("anchor")!).right;
    const anchorOutgoingEdges = laidOut.edges.filter((edge) => edge.source === "anchor");
    const anchorStore = laidOut.edges.find((edge) => edge.id === "anchor-store")!;
    const sameLaneEdges = laidOut.edges.filter((edge) =>
      byId.get(edge.source)?.metadata?.["layout.direction"] === "OUTGOING"
      && byId.get(edge.target)?.metadata?.["layout.direction"] === "OUTGOING",
    );

    anchorOutgoingEdges.forEach((edge) => {
      expect(routeIsOrthogonal(edge)).toBe(true);
      expect(routePoints(edge).length).toBeLessThanOrEqual(4);
      expect(Math.min(...routePoints(edge).map((point) => point.x))).toBeGreaterThanOrEqual(anchorRight);
    });
    expect(anchorStore.metadata?.["uml.relation.kind"]).toBe("DEPENDENCY");
    expect(anchorStore.sourceHandle).toMatch(/^source-right(?:-\d+)?$/);
    expect(anchorStore.targetHandle).toMatch(/^target-left(?:-\d+)?$/);
    expect(Math.max(...routePoints(anchorStore).map((point) => point.x))).toBeLessThanOrEqual(nodeRect(byId.get("store")!).left);
    sameLaneEdges.forEach((edge) => {
      const points = routePoints(edge);
      expect(Math.max(...points.map((point) => point.x))).toBeLessThanOrEqual(rightLaneRight + 112);
    });
    laidOut.edges.forEach((edge) => {
      const crossedNode = laidOut.nodes.find((node) =>
        node.id !== edge.source && node.id !== edge.target && routeCrossesNode(edge, node),
      );
      expect(crossedNode, `${edge.id} crosses ${crossedNode?.id}`).toBeUndefined();
    });
  });

  it("keeps dense current-class diagrams on readable rail routes after crossing repair", async () => {
    const nodes = [
      tallClassNode("validator", "MetadataVersionConfigValidator"),
      tallClassNode("test", "MetadataVersionConfigValidatorTest"),
      {
        ...tallClassNode("publisher", "MetadataPublisher"),
        type: "INTERFACE" as const,
      },
      tallClassNode("config", "KafkaConfig"),
      tallClassNode("fault", "FaultHandler"),
      tallClassNode("delta", "MetadataDelta"),
      tallClassNode("image", "MetadataImage"),
      tallClassNode("version", "MetadataVersion"),
    ];
    const edges = [
      relation("validator-config", "validator", "config", "USES_TYPE", "ASSOCIATION"),
      relation("validator-fault", "validator", "fault", "USES_TYPE", "ASSOCIATION"),
      relation("validator-delta", "validator", "delta", "USES_TYPE", "METHOD_PARAMETER"),
      relation("validator-publisher", "validator", "publisher", "IMPLEMENTS", "REALIZATION"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "validator",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const report = readabilityReport(laidOut.nodes, laidOut.edges);

    expect(report.metrics.nodeCrossings).toBe(0);
    expect(report.metrics.edgeCrossings).toBe(0);
    expect(report.metrics.edgeOverlaps).toBe(0);
    expect(report.metrics.crossLaneSecondaryRouteUsesRail).toBe(true);
    expect(report.metrics.portOrderMatchesTargetOrder).toBe(true);
    expect(report.violations).toEqual([]);
  });

  it("rejects class diagrams whose relation routes still contain unreadable crossing pressure", () => {
    const left = { ...classNode("left"), position: { x: 120, y: 120 } };
    const right = { ...classNode("right"), position: { x: 520, y: 120 } };
    const top = { ...classNode("top"), position: { x: 320, y: -120 } };
    const bottom = { ...classNode("bottom"), position: { x: 320, y: 360 } };
    const horizontal: LinkGraphEdge = {
      ...relation("horizontal", "left", "right", "USES_TYPE", "DEPENDENCY"),
      route: {
        sections: [{
          startPoint: { x: 360, y: 180 },
          endPoint: { x: 520, y: 180 },
        }],
      },
    };
    const vertical: LinkGraphEdge = {
      ...relation("vertical", "top", "bottom", "USES_TYPE", "DEPENDENCY"),
      route: {
        sections: [{
          startPoint: { x: 440, y: 20 },
          endPoint: { x: 440, y: 360 },
        }],
      },
    };

    const report = readabilityReport([left, right, top, bottom], [horizontal, vertical]);

    expect(report.metrics.edgeCrossings).toBeGreaterThan(0);
    expect(report.accepted).toBe(false);
    expect(report.violations).toContain("edgeCrossings");
  });
});
