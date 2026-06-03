import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import { traceLinkGraph } from "../../debug";
import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  DATA_ANCHOR_GAP,
  DATA_COLUMN_GAP,
  DATA_COLUMN_VERTICAL_STAGGER,
  DATA_RIGHT_OFFSET,
  FALLBACK_HEIGHT,
  LANE_RANK,
  MIN_NODE_WIDTH,
  ORIGIN,
  OUTGOING_COLUMN_GAP,
  PARENT_ANCHOR_GAP,
  SIDE_LANE_BASE_GAP,
  STACK_GAP,
  X_GAP,
  Y_GAP,
  ANCHOR_ROUTE_CHANNEL_GAP,
  ANCHOR_ROUTE_SOURCE_MARGIN_X,
  ANCHOR_ROUTE_TARGET_MARGIN_X,
  edgeSortKey,
  measuredNodeSize,
  nodeSortKey,
  type ClassDiagramLane,
  type ClassLaneEntry,
} from "./classDiagramLayoutModel";
import {
  isClassDiagramDependencyRelation,
  isClassDiagramHierarchyRelation,
} from "./classDiagramRelations";
import type { ClassDiagramTopology } from "./classDiagramTopology";

export interface ClassDiagramPlacement {
  anchorId: string;
  nodes: LinkGraphNode[];
  nodeIndex: Map<string, LinkGraphNode>;
  laneBuckets: Map<ClassDiagramLane, ClassLaneEntry[]>;
}

interface ClassDiagramPresentationMetadata {
  laneId: string;
  role: "ANCHOR" | "INTERFACE" | "CALLER" | "COLLABORATOR" | "OUTPUT" | "TYPE";
  priority: number;
  compact: boolean;
}

export class ClassDiagramPlacementEngine {
  place(
    topology: ClassDiagramTopology,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): ClassDiagramPlacement {
    const laneBuckets = this.cloneLaneBuckets(topology.laneBuckets);
    laneBuckets.forEach((bucket, lane) => {
      this.sortLaneBucket(
        bucket,
        lane,
        topology.anchorId,
        topology.incoming,
        topology.outgoing,
        topology.incomingDistances,
        topology.outgoingDistances,
      );
    });

    const incomingBucket = laneBuckets.get("INCOMING") ?? [];
    const outgoingBucket = laneBuckets.get("OUTGOING") ?? [];
    const incomingLaneGap = this.anchorFanoutLaneGap(this.anchorIncomingFanoutCount(topology.anchorId, incomingBucket, topology.incoming));
    const outgoingLaneGap = this.anchorFanoutLaneGap(this.anchorOutgoingFanoutCount(topology.anchorId, outgoingBucket, topology.outgoing));
    const anchorX = ORIGIN.x + incomingLaneGap;
    const laneX: Record<ClassDiagramLane, number> = {
      INCOMING: ORIGIN.x,
      ANCHOR: anchorX,
      PARENT: anchorX,
      OUTGOING: anchorX + outgoingLaneGap,
      DATA: anchorX + outgoingLaneGap + DATA_RIGHT_OFFSET,
      RELATED: anchorX + outgoingLaneGap + X_GAP,
    };
    const laneStartY = (lane: ClassDiagramLane, count: number) => {
      void lane;
      return ORIGIN.y + Math.max(0, 2 - Math.ceil(count / 2)) * (Y_GAP / 2);
    };
    const stackHeight = (bucket: ClassLaneEntry[]): number => {
      if (bucket.length === 0) {
        return 0;
      }
      return bucket.reduce((height, entry, index) => {
        const size = measuredNodeSize(entry.node, sizeSnapshot, entry.lane);
        return height + size.height + (index === bucket.length - 1 ? 0 : STACK_GAP);
      }, 0);
    };
    const centeredStartY = (centerY: number, bucket: ClassLaneEntry[]): number =>
      Math.max(ORIGIN.y, Math.round(centerY - stackHeight(bucket) / 2));
    const parentBucket = laneBuckets.get("PARENT") ?? [];
    const anchorBucket = laneBuckets.get("ANCHOR") ?? [];
    const parentStackHeight = stackHeight(parentBucket);
    const anchorStartY = parentStackHeight > 0
      ? ORIGIN.y + parentStackHeight + PARENT_ANCHOR_GAP
      : ORIGIN.y + Y_GAP;
    const anchorHeight = anchorBucket[0] ? measuredNodeSize(anchorBucket[0].node, sizeSnapshot, "ANCHOR").height : FALLBACK_HEIGHT;
    const anchorCenterY = anchorStartY + (anchorHeight / 2);
    const outgoingColumnByNodeId = this.resolveOutgoingColumns(outgoingBucket, topology.outgoing);
    const outgoingColumnBuckets = this.groupEntriesByColumn(outgoingBucket, outgoingColumnByNodeId);
    const outgoingColumnStartY = new Map<number, number>();
    const outgoingBaseStartY = centeredStartY(anchorCenterY, outgoingBucket);
    outgoingColumnBuckets.forEach((_bucket, column) => {
      outgoingColumnStartY.set(column, outgoingBaseStartY + column * DATA_COLUMN_VERTICAL_STAGGER);
    });
    const outgoingLaneStartY = outgoingColumnStartY.size > 0
      ? Math.min(...outgoingColumnStartY.values())
      : outgoingBaseStartY;
    const outgoingLaneBottom = outgoingColumnBuckets.size > 0
      ? Math.max(...Array.from(outgoingColumnBuckets, ([column, bucket]) =>
          (outgoingColumnStartY.get(column) ?? outgoingLaneStartY) + stackHeight(bucket),
        ))
      : outgoingLaneStartY + stackHeight(outgoingBucket);
    const resolvedLaneStartY: Record<ClassDiagramLane, number> = {
      PARENT: ORIGIN.y,
      ANCHOR: anchorStartY,
      INCOMING: centeredStartY(anchorCenterY, laneBuckets.get("INCOMING") ?? []),
      OUTGOING: outgoingLaneStartY,
      DATA: anchorStartY + anchorHeight + DATA_ANCHOR_GAP,
      RELATED: laneStartY("RELATED", laneBuckets.get("RELATED")?.length ?? 0),
    };
    const nonDataBottom = Math.max(
      resolvedLaneStartY.ANCHOR + stackHeight(laneBuckets.get("ANCHOR") ?? []),
      resolvedLaneStartY.INCOMING + stackHeight(laneBuckets.get("INCOMING") ?? []),
      outgoingLaneBottom,
    );
    resolvedLaneStartY.DATA = Math.max(resolvedLaneStartY.DATA, nonDataBottom + DATA_ANCHOR_GAP);
    const edgesByNode = this.edgeIndexForNode(topology.edges);
    const dataBucket = laneBuckets.get("DATA") ?? [];
    const dataColumnByNodeId = new Map<string, number>();
    dataBucket.forEach((entry, index) => {
      dataColumnByNodeId.set(entry.node.id, this.resolveDataColumn(entry.node, edgesByNode, topology.anchorId, index));
    });
    const dataColumnStartY: Record<number, number> = {
      0: resolvedLaneStartY.DATA,
      1: resolvedLaneStartY.DATA + DATA_COLUMN_VERTICAL_STAGGER,
    };
    resolvedLaneStartY.DATA = Math.min(dataColumnStartY[0], dataColumnStartY[1]);
    const laidOutNodes: LinkGraphNode[] = [];
    for (const lane of ["PARENT", "INCOMING", "ANCHOR", "OUTGOING", "DATA", "RELATED"] as ClassDiagramLane[]) {
      const bucket = laneBuckets.get(lane) ?? [];
      let cursorY = resolvedLaneStartY[lane];
      const outgoingCursorsByColumn = new Map(outgoingColumnStartY);
      const outgoingRowsByColumn = new Map<number, number>(
        Array.from(outgoingColumnStartY.keys()).map((column) => [column, 0]),
      );
      const dataColumnCursors: Record<number, number> = {
        0: dataColumnStartY[0],
        1: dataColumnStartY[1],
      };
      const dataColumnRows: Record<number, number> = {
        0: 0,
        1: 0,
      };
      bucket.forEach((entry, index) => {
        const size = measuredNodeSize(entry.node, sizeSnapshot, entry.lane);
        const presentation = this.resolvePresentationMetadata(entry.node, entry.lane);
        const outgoingColumn = lane === "OUTGOING" ? outgoingColumnByNodeId.get(entry.node.id) ?? 0 : 0;
        const dataColumn = lane === "DATA" ? dataColumnByNodeId.get(entry.node.id) ?? this.resolveDataColumn(entry.node, edgesByNode, topology.anchorId, index) : outgoingColumn;
        const dataRow = lane === "DATA" ? dataColumnRows[dataColumn] ?? 0 : lane === "OUTGOING" ? outgoingRowsByColumn.get(dataColumn) ?? 0 : 0;
        const x = laneX[lane] + (lane === "DATA" ? dataColumn * (MIN_NODE_WIDTH + DATA_COLUMN_GAP) : lane === "OUTGOING" ? dataColumn * (MIN_NODE_WIDTH + OUTGOING_COLUMN_GAP) : 0);
        const y = Math.round(lane === "DATA"
          ? dataColumnCursors[dataColumn] ?? cursorY
          : lane === "OUTGOING" ? outgoingCursorsByColumn.get(dataColumn) ?? cursorY : cursorY);
        laidOutNodes.push({
          ...entry.node,
          position: { x, y },
          metadata: {
            ...(entry.node.metadata ?? {}),
            "layout.mode": "CLASS_DIAGRAM",
            "layout.direction": entry.lane,
            "layout.levelLabel": this.classLayoutLabel(entry.lane, entry.depth),
            "layout.estimatedHeight": String(size.height),
            "layout.column": String(dataColumn),
            "layout.row": String(dataRow),
            "presentation.role": presentation.role,
            "presentation.laneId": presentation.laneId,
            "presentation.priority": String(presentation.priority),
            "presentation.compact": String(presentation.compact),
            "ui.x": String(x),
            "ui.y": String(y),
          },
        });
        if (lane === "DATA") {
          dataColumnCursors[dataColumn] = (dataColumnCursors[dataColumn] ?? cursorY) + size.height + STACK_GAP;
          dataColumnRows[dataColumn] = dataRow + 1;
        } else if (lane === "OUTGOING") {
          outgoingCursorsByColumn.set(dataColumn, (outgoingCursorsByColumn.get(dataColumn) ?? cursorY) + size.height + STACK_GAP);
          outgoingRowsByColumn.set(dataColumn, dataRow + 1);
        } else {
          cursorY += size.height + STACK_GAP;
        }
      });
    }
    const nodeIndex = new Map(laidOutNodes.map((node) => [node.id, node]));
    const overlapSummary = this.laneOverlapSummary(laidOutNodes, sizeSnapshot);
    traceLinkGraph("classDiagramLayout.placement.complete", {
      nodeCount: laidOutNodes.length,
      edgeCount: topology.edges.length,
      anchorNodeId: topology.anchorId,
      laneCounts: Object.fromEntries(
        (["PARENT", "INCOMING", "ANCHOR", "OUTGOING", "DATA", "RELATED"] as ClassDiagramLane[])
          .map((lane) => [lane, laneBuckets.get(lane)?.length ?? 0]),
      ),
      laneStartY: resolvedLaneStartY,
      maxNodeHeight: Math.max(...laidOutNodes.map((node) => measuredNodeSize(node, sizeSnapshot).height)),
      ...overlapSummary,
    });
    return {
      anchorId: topology.anchorId,
      nodes: laidOutNodes,
      nodeIndex,
      laneBuckets,
    };
  }

  private resolvePresentationMetadata(
    node: LinkGraphNode,
    lane: ClassDiagramLane,
  ): ClassDiagramPresentationMetadata {
    switch (lane) {
      case "ANCHOR":
        return { laneId: "anchor", role: "ANCHOR", priority: 30, compact: false };
      case "PARENT":
        return this.isAbstractionNode(node)
          ? { laneId: "abstraction", role: "INTERFACE", priority: 10, compact: true }
          : { laneId: "abstraction", role: "TYPE", priority: 45, compact: true };
      case "INCOMING":
        return { laneId: "caller", role: "CALLER", priority: 20, compact: true };
      case "OUTGOING":
        return { laneId: "collaborator", role: "COLLABORATOR", priority: 40, compact: true };
      case "DATA":
        return { laneId: "output", role: "OUTPUT", priority: 50, compact: true };
      case "RELATED":
        return { laneId: "collaborator", role: "TYPE", priority: 45, compact: true };
    }
  }

  private isAbstractionNode(node: LinkGraphNode): boolean {
    return node.type === "INTERFACE"
      || node.metadata?.["jvm.class.kind"] === "INTERFACE"
      || node.metadata?.["jvm.class.abstract"] === "true";
  }

  private cloneLaneBuckets(source: Map<ClassDiagramLane, ClassLaneEntry[]>): Map<ClassDiagramLane, ClassLaneEntry[]> {
    return new Map(Array.from(source, ([lane, bucket]) => [lane, [...bucket]]));
  }

  private classLayoutLabel(lane: ClassDiagramLane, depth: number): string {
    switch (lane) {
      case "PARENT":
        return "继承/实现";
      case "INCOMING":
        return `入向关系 ${Math.max(depth, 1)} 层`;
      case "ANCHOR":
        return "当前类型";
      case "OUTGOING":
        return `出向关系 ${Math.max(depth, 1)} 层`;
      case "DATA":
        return "数据结构";
      case "RELATED":
        return "相关类型";
    }
  }

  private laneOverlapSummary(
    nodes: LinkGraphNode[],
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ) {
    let overlapCount = 0;
    let minVerticalGap = Number.POSITIVE_INFINITY;
    const lanes = new Map<string, LinkGraphNode[]>();
    nodes.forEach((node) => {
      const lane = node.metadata?.["layout.direction"] ?? "UNKNOWN";
      const column = node.metadata?.["layout.column"] ?? "0";
      lanes.set(`${lane}:${column}`, [...(lanes.get(`${lane}:${column}`) ?? []), node]);
    });
    lanes.forEach((laneNodes) => {
      const ordered = laneNodes
        .filter((node) => node.position)
        .sort((left, right) => (left.position?.y ?? 0) - (right.position?.y ?? 0));
      for (let index = 1; index < ordered.length; index += 1) {
        const previous = ordered[index - 1];
        const current = ordered[index];
        if (!previous?.position || !current?.position) {
          continue;
        }
        const previousBottom = previous.position.y + measuredNodeSize(previous, sizeSnapshot).height;
        const gap = current.position.y - previousBottom;
        minVerticalGap = Math.min(minVerticalGap, gap);
        if (gap < 0) {
          overlapCount += 1;
        }
      }
    });
    return {
      overlapCount,
      minVerticalGap: Number.isFinite(minVerticalGap) ? Math.round(minVerticalGap) : null,
    };
  }

  private laneNodeOrderKey(
    nodeId: string,
    lane: ClassDiagramLane,
    anchorId: string,
    incoming: Map<string, LinkGraphEdge[]>,
    outgoing: Map<string, LinkGraphEdge[]>,
    incomingDistances: Map<string, number>,
    outgoingDistances: Map<string, number>,
  ): string {
    if (nodeId === anchorId) {
      return "0|anchor";
    }
    const anchorIncomingEdge = (incoming.get(anchorId) ?? [])
      .filter((edge) => edge.source === nodeId)
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))[0];
    const anchorOutgoingEdge = (outgoing.get(anchorId) ?? [])
      .filter((edge) => edge.target === nodeId)
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))[0];
    if (lane === "INCOMING" && anchorIncomingEdge) {
      return `0|${edgeSortKey(anchorIncomingEdge)}`;
    }
    if ((lane === "OUTGOING" || lane === "PARENT") && anchorOutgoingEdge) {
      return `0|${edgeSortKey(anchorOutgoingEdge)}`;
    }
    const incomingEdge = (incoming.get(nodeId) ?? [])
      .filter((edge) => outgoingDistances.has(edge.source))
      .sort((left, right) =>
        (outgoingDistances.get(left.source) ?? 99) - (outgoingDistances.get(right.source) ?? 99)
        || edgeSortKey(left).localeCompare(edgeSortKey(right)),
      )[0];
    const outgoingEdge = (outgoing.get(nodeId) ?? [])
      .filter((edge) => incomingDistances.has(edge.target))
      .sort((left, right) =>
        (incomingDistances.get(left.target) ?? 99) - (incomingDistances.get(right.target) ?? 99)
        || edgeSortKey(left).localeCompare(edgeSortKey(right)),
      )[0];
    return `1|${incomingEdge ? edgeSortKey(incomingEdge) : ""}|${outgoingEdge ? edgeSortKey(outgoingEdge) : ""}|${nodeId}`;
  }

  private sortLaneBucket(
    bucket: ClassLaneEntry[],
    lane: ClassDiagramLane,
    anchorId: string,
    incoming: Map<string, LinkGraphEdge[]>,
    outgoing: Map<string, LinkGraphEdge[]>,
    incomingDistances: Map<string, number>,
    outgoingDistances: Map<string, number>,
  ) {
    bucket.sort((left, right) =>
      this.laneNodeOrderKey(left.node.id, lane, anchorId, incoming, outgoing, incomingDistances, outgoingDistances)
        .localeCompare(this.laneNodeOrderKey(right.node.id, lane, anchorId, incoming, outgoing, incomingDistances, outgoingDistances))
      || left.depth - right.depth
      || nodeSortKey(left.node).localeCompare(nodeSortKey(right.node)),
    );
    if (bucket.length <= 1 || lane === "ANCHOR" || lane === "PARENT") {
      return;
    }
    const nodeIds = new Set(bucket.map((entry) => entry.node.id));
    const baseOrder = new Map(bucket.map((entry, index) => [entry.node.id, index]));
    const adjacency = new Map<string, string[]>();
    const indegree = new Map(bucket.map((entry) => [entry.node.id, 0]));
    const seenPairs = new Set<string>();
    bucket
      .flatMap((entry) => outgoing.get(entry.node.id) ?? [])
      .filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target) && edge.source !== edge.target && !isClassDiagramHierarchyRelation(edge))
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))
      .forEach((edge) => {
        const pairKey = `${edge.source}->${edge.target}`;
        if (seenPairs.has(pairKey)) {
          return;
        }
        seenPairs.add(pairKey);
        adjacency.set(edge.source, [...(adjacency.get(edge.source) ?? []), edge.target]);
        indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1);
      });
    if (seenPairs.size === 0) {
      return;
    }
    const entryById = new Map(bucket.map((entry) => [entry.node.id, entry]));
    const compareBaseOrder = (left: string, right: string) => (baseOrder.get(left) ?? 0) - (baseOrder.get(right) ?? 0);
    const ready = bucket
      .map((entry) => entry.node.id)
      .filter((nodeId) => (indegree.get(nodeId) ?? 0) === 0)
      .sort(compareBaseOrder);
    const ordered: ClassLaneEntry[] = [];
    const consumed = new Set<string>();
    while (ready.length > 0) {
      const nodeId = ready.shift()!;
      if (consumed.has(nodeId)) {
        continue;
      }
      consumed.add(nodeId);
      const entry = entryById.get(nodeId);
      if (entry) {
        ordered.push(entry);
      }
      (adjacency.get(nodeId) ?? []).sort(compareBaseOrder).forEach((targetId) => {
        const nextIndegree = (indegree.get(targetId) ?? 0) - 1;
        indegree.set(targetId, nextIndegree);
        if (nextIndegree === 0) {
          ready.push(targetId);
          ready.sort(compareBaseOrder);
        }
      });
    }
    if (ordered.length === bucket.length) {
      bucket.splice(0, bucket.length, ...ordered);
      return;
    }
    const orderedIds = new Set(ordered.map((entry) => entry.node.id));
    bucket.splice(0, bucket.length, ...ordered, ...bucket.filter((entry) => !orderedIds.has(entry.node.id)));
  }

  private resolveOutgoingColumns(
    bucket: ClassLaneEntry[],
    outgoing: Map<string, LinkGraphEdge[]>,
  ): Map<string, number> {
    const nodeIds = new Set(bucket.map((entry) => entry.node.id));
    const columns = new Map(bucket.map((entry) => [entry.node.id, 0]));
    const dependencies = bucket
      .flatMap((entry) => outgoing.get(entry.node.id) ?? [])
      .filter((edge) =>
        isClassDiagramDependencyRelation(edge)
        && edge.source !== edge.target
        && nodeIds.has(edge.source)
        && nodeIds.has(edge.target),
      )
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)));

    for (let pass = 0; pass < Math.max(1, bucket.length); pass += 1) {
      let changed = false;
      dependencies.forEach((edge) => {
        const sourceColumn = columns.get(edge.source) ?? 0;
        const nextColumn = Math.min(sourceColumn + 1, 2);
        if ((columns.get(edge.target) ?? 0) < nextColumn) {
          columns.set(edge.target, nextColumn);
          changed = true;
        }
      });
      if (!changed) {
        break;
      }
    }
    return columns;
  }

  private groupEntriesByColumn(
    bucket: ClassLaneEntry[],
    columns: Map<string, number>,
  ): Map<number, ClassLaneEntry[]> {
    const grouped = new Map<number, ClassLaneEntry[]>();
    bucket.forEach((entry) => {
      const column = columns.get(entry.node.id) ?? 0;
      grouped.set(column, [...(grouped.get(column) ?? []), entry]);
    });
    return grouped;
  }

  private edgeIndexForNode(edges: LinkGraphEdge[]) {
    const byNode = new Map<string, LinkGraphEdge[]>();
    edges.forEach((edge) => {
      byNode.set(edge.source, [...(byNode.get(edge.source) ?? []), edge]);
      byNode.set(edge.target, [...(byNode.get(edge.target) ?? []), edge]);
    });
    return byNode;
  }

  private resolveDataColumn(
    node: LinkGraphNode,
    edgesByNode: Map<string, LinkGraphEdge[]>,
    anchorId: string,
    laneIndex: number,
  ): number {
    const directEdges = edgesByNode.get(node.id) ?? [];
    const hasAnchorData = directEdges.some((edge) =>
      isClassDiagramDependencyRelation(edge)
      && (edge.source === anchorId || edge.target === anchorId),
    );
    return hasAnchorData ? laneIndex % 2 : 1;
  }

  private anchorFanoutLaneGap(edgeCount: number): number {
    const fanoutCount = Math.max(1, edgeCount);
    const channelGap = fanoutCount <= 1
      ? 56
      : fanoutCount <= 2 ? 72
      : fanoutCount <= 4 ? 84 : ANCHOR_ROUTE_CHANNEL_GAP;
    const readableCorridorWidth = ANCHOR_ROUTE_SOURCE_MARGIN_X
      + ANCHOR_ROUTE_TARGET_MARGIN_X
      + (fanoutCount + 1) * channelGap;
    return Math.max(SIDE_LANE_BASE_GAP, MIN_NODE_WIDTH + readableCorridorWidth);
  }

  private anchorOutgoingFanoutCount(
    anchorId: string,
    outgoingBucket: ClassLaneEntry[],
    outgoing: Map<string, LinkGraphEdge[]>,
  ): number {
    const outgoingNodeIds = new Set(outgoingBucket.map((entry) => entry.node.id));
    return (outgoing.get(anchorId) ?? []).filter((edge) =>
      outgoingNodeIds.has(edge.target)
      && !isClassDiagramHierarchyRelation(edge),
    ).length;
  }

  private anchorIncomingFanoutCount(
    anchorId: string,
    incomingBucket: ClassLaneEntry[],
    incoming: Map<string, LinkGraphEdge[]>,
  ): number {
    const incomingNodeIds = new Set(incomingBucket.map((entry) => entry.node.id));
    return (incoming.get(anchorId) ?? []).filter((edge) =>
      incomingNodeIds.has(edge.source)
      && !isClassDiagramHierarchyRelation(edge),
    ).length;
  }
}
