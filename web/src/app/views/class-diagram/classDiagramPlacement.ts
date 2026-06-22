import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import { traceLinkGraph } from "../../debug";
import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  ANCHOR_ROUTE_SOURCE_MARGIN_X,
  ANCHOR_ROUTE_TARGET_MARGIN_X,
  DATA_ANCHOR_GAP,
  DATA_COLUMN_VERTICAL_STAGGER,
  DATA_RIGHT_OFFSET,
  FALLBACK_HEIGHT,
  LANE_COLUMN_LIMIT,
  MIN_NODE_WIDTH,
  ORIGIN,
  PARENT_ANCHOR_GAP,
  RELATED_LANE_GAP,
  STACK_GAP,
  Y_GAP,
  edgeSortKey,
  laneColumnGap,
  laneColumnWidth,
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

interface LaneColumnPlan {
  columnByNodeId: Map<string, number>;
  columnCount: number;
  nodesByColumn: Map<number, ClassLaneEntry[]>;
}

const COLUMN_STAGGER = DATA_COLUMN_VERTICAL_STAGGER;

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
    const dataBucket = laneBuckets.get("DATA") ?? [];
    const relatedBucket = laneBuckets.get("RELATED") ?? [];

    const incomingPlan = this.buildLaneColumnPlan(incomingBucket, topology, "INCOMING");
    const outgoingPlan = this.buildLaneColumnPlan(outgoingBucket, topology, "OUTGOING");
    const dataPlan = this.buildLaneColumnPlan(dataBucket, topology, "DATA");
    const relatedPlan = this.buildLaneColumnPlan(relatedBucket, topology, "RELATED");

    const incomingLaneWidth = laneColumnWidth("INCOMING", incomingPlan.columnCount);
    const outgoingLaneWidth = laneColumnWidth("OUTGOING", outgoingPlan.columnCount);
    const dataLaneWidth = laneColumnWidth("DATA", dataPlan.columnCount);
    const relatedLaneWidth = laneColumnWidth("RELATED", relatedPlan.columnCount);

    const incomingLaneGap = this.anchorFanoutLaneGap(this.anchorIncomingFanoutCount(topology.anchorId, incomingBucket, topology.incoming));
    const outgoingLaneGap = this.anchorFanoutLaneGap(this.anchorOutgoingFanoutCount(topology.anchorId, outgoingBucket, topology.outgoing));

    const anchorX = ORIGIN.x + incomingLaneWidth + incomingLaneGap;
    const anchorWidth = MIN_NODE_WIDTH;
    const outgoingX = anchorX + anchorWidth + outgoingLaneGap;
    const dataX = outgoingX + outgoingLaneWidth + DATA_RIGHT_OFFSET;
    const relatedX = dataX + dataLaneWidth + RELATED_LANE_GAP;

    const laneX: Record<ClassDiagramLane, number> = {
      PARENT: anchorX,
      INCOMING: ORIGIN.x,
      ANCHOR: anchorX,
      OUTGOING: outgoingX,
      DATA: dataX,
      RELATED: relatedX,
    };

    const parentBucket = laneBuckets.get("PARENT") ?? [];
    const anchorBucket = laneBuckets.get("ANCHOR") ?? [];

    const measuredColumnHeight = (
      bucket: ClassLaneEntry[],
    ): number => {
      if (bucket.length === 0) {
        return 0;
      }
      return bucket.reduce((height, entry, index) => {
        const size = measuredNodeSize(entry.node, sizeSnapshot, entry.lane);
        return height + size.height + (index === bucket.length - 1 ? 0 : STACK_GAP);
      }, 0);
    };

    const incomingHeight = measuredColumnHeight(incomingBucket);
    const outgoingHeight = measuredColumnHeight(outgoingBucket);

    const parentStackHeight = parentBucket.reduce((height, entry, index) => {
      const size = measuredNodeSize(entry.node, sizeSnapshot, entry.lane);
      return height + size.height + (index === parentBucket.length - 1 ? 0 : STACK_GAP);
    }, 0);

    const anchorStartY = parentStackHeight > 0
      ? ORIGIN.y + parentStackHeight + PARENT_ANCHOR_GAP
      : ORIGIN.y + Y_GAP;
    const anchorHeight = anchorBucket[0]
      ? measuredNodeSize(anchorBucket[0].node, sizeSnapshot, "ANCHOR").height
      : FALLBACK_HEIGHT;
    const anchorCenterY = anchorStartY + anchorHeight / 2;

    const centeredBaseStartY = (totalHeight: number): number =>
      Math.max(ORIGIN.y, Math.round(anchorCenterY - totalHeight / 2));

    const incomingBaseStartY = centeredBaseStartY(incomingHeight);
    const outgoingBaseStartY = centeredBaseStartY(outgoingHeight);

    const columnStartYFor = (baseStartY: number, column: number): number =>
      baseStartY + column * COLUMN_STAGGER;

    const nonDataBottom = Math.max(
      anchorStartY + anchorHeight,
      incomingBaseStartY + incomingHeight,
      outgoingBaseStartY + outgoingHeight,
    );
    const dataBaseStartY = nonDataBottom + DATA_ANCHOR_GAP;
    const relatedBaseStartY = ORIGIN.y + Y_GAP;

    const placedNodes: LinkGraphNode[] = [];
    const placeColumnStack = (
      lane: ClassDiagramLane,
      plan: LaneColumnPlan,
      baseStartY: number,
    ) => {
      plan.nodesByColumn.forEach((bucket, column) => {
        const columnX = laneX[lane] + column * (MIN_NODE_WIDTH + laneColumnGap(lane));
        const columnStartY = columnStartYFor(baseStartY, column);
        let cursorY = columnStartY;
        bucket.forEach((entry, index) => {
          const size = measuredNodeSize(entry.node, sizeSnapshot, lane);
          const x = Math.round(columnX);
          const y = Math.round(cursorY);
          const presentation = this.resolvePresentationMetadata(entry.node, lane);
          placedNodes.push({
            ...entry.node,
            position: { x, y },
            metadata: {
              ...(entry.node.metadata ?? {}),
              "layout.mode": "CLASS_DIAGRAM",
              "layout.direction": lane,
              "layout.levelLabel": this.classLayoutLabel(lane, entry.depth),
              "layout.estimatedHeight": String(size.height),
              "layout.column": String(column),
              "layout.row": String(index),
              "presentation.role": presentation.role,
              "presentation.laneId": presentation.laneId,
              "presentation.priority": String(presentation.priority),
              "presentation.compact": String(presentation.compact),
              "ui.x": String(x),
              "ui.y": String(y),
            },
          });
          cursorY += size.height + STACK_GAP;
        });
      });
    };

    parentBucket.forEach((entry, index) => {
      const size = measuredNodeSize(entry.node, sizeSnapshot, "PARENT");
      const x = Math.round(laneX.PARENT);
      const y = Math.round(ORIGIN.y + index * (size.height + STACK_GAP));
      const presentation = this.resolvePresentationMetadata(entry.node, "PARENT");
      placedNodes.push({
        ...entry.node,
        position: { x, y },
        metadata: {
          ...(entry.node.metadata ?? {}),
          "layout.mode": "CLASS_DIAGRAM",
          "layout.direction": "PARENT",
          "layout.levelLabel": this.classLayoutLabel("PARENT", entry.depth),
          "layout.estimatedHeight": String(size.height),
          "layout.column": "0",
          "layout.row": String(index),
          "presentation.role": presentation.role,
          "presentation.laneId": presentation.laneId,
          "presentation.priority": String(presentation.priority),
          "presentation.compact": String(presentation.compact),
          "ui.x": String(x),
          "ui.y": String(y),
        },
      });
    });

    anchorBucket.forEach((entry) => {
      const size = measuredNodeSize(entry.node, sizeSnapshot, "ANCHOR");
      const x = Math.round(laneX.ANCHOR);
      const y = Math.round(anchorStartY);
      const presentation = this.resolvePresentationMetadata(entry.node, "ANCHOR");
      placedNodes.push({
        ...entry.node,
        position: { x, y },
        metadata: {
          ...(entry.node.metadata ?? {}),
          "layout.mode": "CLASS_DIAGRAM",
          "layout.direction": "ANCHOR",
          "layout.levelLabel": this.classLayoutLabel("ANCHOR", entry.depth),
          "layout.estimatedHeight": String(size.height),
          "layout.column": "0",
          "layout.row": "0",
          "presentation.role": presentation.role,
          "presentation.laneId": presentation.laneId,
          "presentation.priority": String(presentation.priority),
          "presentation.compact": String(presentation.compact),
          "ui.x": String(x),
          "ui.y": String(y),
        },
      });
    });

    placeColumnStack("INCOMING", incomingPlan, incomingBaseStartY);
    placeColumnStack("OUTGOING", outgoingPlan, outgoingBaseStartY);
    placeColumnStack("DATA", dataPlan, dataBaseStartY);
    placeColumnStack("RELATED", relatedPlan, relatedBaseStartY);

    const nodeIndex = new Map(placedNodes.map((node) => [node.id, node]));
    const overlapSummary = this.laneOverlapSummary(placedNodes, sizeSnapshot);
    traceLinkGraph("classDiagramLayout.placement.complete", {
      nodeCount: placedNodes.length,
      edgeCount: topology.edges.length,
      anchorNodeId: topology.anchorId,
      laneCounts: Object.fromEntries(
        (["PARENT", "INCOMING", "ANCHOR", "OUTGOING", "DATA", "RELATED"] as ClassDiagramLane[])
          .map((lane) => [lane, laneBuckets.get(lane)?.length ?? 0]),
      ),
      laneColumns: Object.fromEntries([
        ["INCOMING", incomingPlan.columnCount],
        ["OUTGOING", outgoingPlan.columnCount],
        ["DATA", dataPlan.columnCount],
        ["RELATED", relatedPlan.columnCount],
      ]),
      laneX,
      anchorStartY,
      maxNodeHeight: Math.max(...placedNodes.map((node) => measuredNodeSize(node, sizeSnapshot).height)),
      ...overlapSummary,
    });
    return {
      anchorId: topology.anchorId,
      nodes: placedNodes,
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

  private buildLaneColumnPlan(
    bucket: ClassLaneEntry[],
    topology: ClassDiagramTopology,
    lane: ClassDiagramLane,
  ): LaneColumnPlan {
    if (bucket.length === 0) {
      return {
        columnByNodeId: new Map(),
        columnCount: 0,
        nodesByColumn: new Map(),
      };
    }
    const limit = LANE_COLUMN_LIMIT[lane];
    const columnByNodeId = this.resolveLaneColumns(bucket, topology, limit);
    let columnCount = 0;
    const nodesByColumn = new Map<number, ClassLaneEntry[]>();
    bucket.forEach((entry) => {
      const column = Math.min(columnByNodeId.get(entry.node.id) ?? 0, limit - 1);
      columnByNodeId.set(entry.node.id, column);
      columnCount = Math.max(columnCount, column + 1);
      nodesByColumn.set(column, [...(nodesByColumn.get(column) ?? []), entry]);
    });
    nodesByColumn.forEach((columnBucket) => {
      columnBucket.sort((left, right) =>
        this.laneNodeOrderKey(left.node.id, lane, topology.anchorId, topology.incoming, topology.outgoing, topology.incomingDistances, topology.outgoingDistances)
          .localeCompare(this.laneNodeOrderKey(right.node.id, lane, topology.anchorId, topology.incoming, topology.outgoing, topology.incomingDistances, topology.outgoingDistances))
        || left.depth - right.depth
        || nodeSortKey(left.node).localeCompare(nodeSortKey(right.node)),
      );
    });
    return { columnByNodeId, columnCount, nodesByColumn };
  }

  private resolveLaneColumns(
    bucket: ClassLaneEntry[],
    topology: ClassDiagramTopology,
    limit: number,
  ): Map<string, number> {
    if (limit <= 1 || bucket.length <= 1) {
      return new Map(bucket.map((entry) => [entry.node.id, 0]));
    }
    const nodeIds = new Set(bucket.map((entry) => entry.node.id));
    const columns = new Map(bucket.map((entry) => [entry.node.id, 0]));
    const dependencies = bucket
      .flatMap((entry) => topology.outgoing.get(entry.node.id) ?? [])
      .filter((edge) =>
        isClassDiagramDependencyRelation(edge)
        && edge.source !== edge.target
        && nodeIds.has(edge.source)
        && nodeIds.has(edge.target),
      )
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)));

    if (dependencies.length === 0) {
      return columns;
    }

    for (let pass = 0; pass < Math.max(1, bucket.length); pass += 1) {
      let changed = false;
      dependencies.forEach((edge) => {
        const sourceColumn = columns.get(edge.source) ?? 0;
        const nextColumn = Math.min(sourceColumn + 1, limit - 1);
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

  private anchorFanoutLaneGap(edgeCount: number): number {
    const fanoutCount = Math.max(1, edgeCount);
    const channelGap = fanoutCount <= 1
      ? 56
      : fanoutCount <= 2 ? 72
      : 84;
    const readableCorridorWidth = ANCHOR_ROUTE_SOURCE_MARGIN_X
      + ANCHOR_ROUTE_TARGET_MARGIN_X
      + (fanoutCount + 1) * channelGap;
    return Math.max(128, readableCorridorWidth);
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
