import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  edgeSortKey,
  LANE_RANK,
  nodeSortKey,
  type ClassDiagramLane,
  type ClassLaneEntry,
} from "./classDiagramLayoutModel";
import {
  isClassDiagramDependencyRelation,
  isClassDiagramHierarchyRelation,
} from "./classDiagramRelations";

export interface ClassDiagramTopology {
  anchorId: string;
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  incoming: Map<string, LinkGraphEdge[]>;
  outgoing: Map<string, LinkGraphEdge[]>;
  incomingDistances: Map<string, number>;
  outgoingDistances: Map<string, number>;
  nodesWithLane: ClassLaneEntry[];
  laneBuckets: Map<ClassDiagramLane, ClassLaneEntry[]>;
}

export class ClassDiagramTopologyBuilder {
  build(nodes: LinkGraphNode[], edges: LinkGraphEdge[], anchorNodeId?: string | null): ClassDiagramTopology | null {
    const anchorId = this.resolveAnchorNodeId(nodes, anchorNodeId);
    if (!anchorId) {
      return null;
    }
    const incoming = this.buildIncoming(edges);
    const outgoing = this.buildOutgoing(edges);
    const incomingDistances = this.shortestDistances(anchorId, incoming, "source");
    const outgoingDistances = this.shortestDistances(anchorId, outgoing, "target");
    const nodesWithLane = nodes
      .map((node) => ({
        node,
        ...this.resolveLane(node, anchorId, incomingDistances, outgoingDistances, incoming, outgoing),
      }))
      .sort((left, right) =>
        LANE_RANK[left.lane] - LANE_RANK[right.lane]
        || left.depth - right.depth
        || nodeSortKey(left.node).localeCompare(nodeSortKey(right.node)),
      );
    const laneBuckets = new Map<ClassDiagramLane, ClassLaneEntry[]>();
    nodesWithLane.forEach((entry) => {
      laneBuckets.set(entry.lane, [...(laneBuckets.get(entry.lane) ?? []), entry]);
    });
    return {
      anchorId,
      nodes,
      edges,
      incoming,
      outgoing,
      incomingDistances,
      outgoingDistances,
      nodesWithLane,
      laneBuckets,
    };
  }

  private resolveAnchorNodeId(
    nodes: LinkGraphNode[],
    anchorNodeId?: string | null,
  ): string | null {
    if (anchorNodeId && nodes.some((node) => node.id === anchorNodeId)) {
      return anchorNodeId;
    }
    return nodes.find((node) => node.type === "CLASS")?.id ?? nodes[0]?.id ?? null;
  }

  private buildIncoming(edges: LinkGraphEdge[]) {
    const incoming = new Map<string, LinkGraphEdge[]>();
    edges.forEach((edge) => {
      incoming.set(edge.target, [...(incoming.get(edge.target) ?? []), edge]);
    });
    return incoming;
  }

  private buildOutgoing(edges: LinkGraphEdge[]) {
    const outgoing = new Map<string, LinkGraphEdge[]>();
    edges.forEach((edge) => {
      outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge]);
    });
    return outgoing;
  }

  private shortestDistances(
    anchorId: string,
    adjacency: Map<string, LinkGraphEdge[]>,
    direction: "source" | "target",
  ) {
    const distances = new Map<string, number>([[anchorId, 0]]);
    const queue = [anchorId];
    while (queue.length > 0) {
      const current = queue.shift();
      if (!current) {
        continue;
      }
      const baseDistance = distances.get(current) ?? 0;
      (adjacency.get(current) ?? [])
        .slice()
        .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))
        .forEach((edge) => {
          const nextId = direction === "source" ? edge.source : edge.target;
          if (distances.has(nextId)) {
            return;
          }
          distances.set(nextId, baseDistance + 1);
          queue.push(nextId);
        });
    }
    return distances;
  }

  private resolveLane(
    node: LinkGraphNode,
    anchorId: string,
    incomingDistances: Map<string, number>,
    outgoingDistances: Map<string, number>,
    incoming: Map<string, LinkGraphEdge[]>,
    outgoing: Map<string, LinkGraphEdge[]>,
  ): { lane: ClassDiagramLane; depth: number } {
    if (node.id === anchorId) {
      return { lane: "ANCHOR", depth: 0 };
    }
    const anchorOutgoingEdges = outgoing.get(anchorId) ?? [];
    const anchorIncomingEdges = incoming.get(anchorId) ?? [];
    const hasAnchorHierarchyEdge = anchorOutgoingEdges.some((edge) => edge.target === node.id && isClassDiagramHierarchyRelation(edge))
      || anchorIncomingEdges.some((edge) => edge.source === node.id && isClassDiagramHierarchyRelation(edge));
    if (hasAnchorHierarchyEdge) {
      return { lane: "PARENT", depth: outgoingDistances.get(node.id) ?? incomingDistances.get(node.id) ?? 1 };
    }
    const directAnchorEdge = this.hasDirectAnchorEdge(node.id, anchorOutgoingEdges, anchorIncomingEdges);
    if (directAnchorEdge.incoming && !directAnchorEdge.outgoing) {
      return { lane: "INCOMING", depth: 1 };
    }
    if (directAnchorEdge.outgoing) {
      return { lane: "OUTGOING", depth: 1 };
    }
    if (
      this.hasSecondaryDataEdge(node.id, incomingDistances, outgoingDistances, incoming)
      || (outgoingDistances.has(node.id) && this.isDataNode(node))
    ) {
      return { lane: "DATA", depth: outgoingDistances.get(node.id) ?? incomingDistances.get(node.id) ?? 1 };
    }
    if (incomingDistances.has(node.id) && !outgoingDistances.has(node.id)) {
      return { lane: "INCOMING", depth: incomingDistances.get(node.id) ?? 1 };
    }
    if (outgoingDistances.has(node.id)) {
      return { lane: "OUTGOING", depth: outgoingDistances.get(node.id) ?? 1 };
    }
    if (incomingDistances.has(node.id)) {
      return { lane: "INCOMING", depth: incomingDistances.get(node.id) ?? 1 };
    }
    return { lane: "RELATED", depth: 1 };
  }

  private isDataNode(node: LinkGraphNode): boolean {
    return node.type === "ENUM"
      || node.type === "RECORD"
      || node.type === "OBJECT"
      || node.metadata?.["jvm.class.kind"] === "ENUM"
      || node.metadata?.["jvm.class.kind"] === "RECORD"
      || node.metadata?.["jvm.class.kind"] === "OBJECT";
  }

  private hasDirectAnchorEdge(
    nodeId: string,
    anchorOutgoingEdges: LinkGraphEdge[],
    anchorIncomingEdges: LinkGraphEdge[],
  ): { incoming: boolean; outgoing: boolean } {
    return {
      incoming: anchorIncomingEdges.some((edge) => edge.source === nodeId),
      outgoing: anchorOutgoingEdges.some((edge) => edge.target === nodeId),
    };
  }

  private hasSecondaryDataEdge(
    nodeId: string,
    incomingDistances: Map<string, number>,
    outgoingDistances: Map<string, number>,
    incoming: Map<string, LinkGraphEdge[]>,
  ): boolean {
    const distance = outgoingDistances.get(nodeId);
    if (!distance || distance <= 1) {
      return false;
    }
    return (incoming.get(nodeId) ?? []).some((edge) =>
      isClassDiagramDependencyRelation(edge)
      && outgoingDistances.has(edge.source)
      && !incomingDistances.has(nodeId),
    );
  }
}
