import type { LayoutOptions } from "elkjs/lib/elk-api";
import { executeElkLayout, resolveMeasuredNodeSize } from "../../reactflow/elkGraph";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";

const RESOURCE_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.partitioning.activate": "true",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "180",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "88",
  "org.eclipse.elk.spacing.nodeNode": "92",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
  "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder": "true",
};

const RESOURCE_LANE_ORDER = ["CODE", "INTEGRATION", "DATA", "CONFIG", "DOC", "AUXILIARY"];
const RESOURCE_LANE_INDEX = new Map(RESOURCE_LANE_ORDER.map((lane, index) => [lane, index]));

function resourceLane(node: MeasuredLayoutRequest["nodes"][number]): string {
  return node.metadata?.["resource.lane"] ?? "CODE";
}

export async function layoutResourceRelationView({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  return executeElkLayout({
    mode: "RESOURCE_RELATION_VIEW",
    layoutOptions: RESOURCE_LAYOUT_OPTIONS,
    nodes: nodes.map((node) => {
      const lane = resourceLane(node);
      return {
        node,
        ...resolveMeasuredNodeSize(node, sizeSnapshot, "RESOURCE_RELATION_VIEW"),
        metadata: {
          "resource.lane": lane,
          "resource.laneIndex": String(RESOURCE_LANE_INDEX.get(lane) ?? RESOURCE_LANE_ORDER.length - 1),
        },
        layoutOptions: {
          "org.eclipse.elk.partitioning.partition": String(RESOURCE_LANE_INDEX.get(lane) ?? RESOURCE_LANE_ORDER.length - 1),
          ...(node.id === anchorNodeId
            ? { "org.eclipse.elk.layered.layering.layerConstraint": "FIRST" }
            : {}),
        },
      };
    }),
    edges: edges.map((edge) => ({ edge })),
  });
}
