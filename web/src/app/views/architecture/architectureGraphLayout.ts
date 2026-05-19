import type { LayoutOptions } from "elkjs/lib/elk-api";
import { executeElkLayout, resolveMeasuredNodeSize } from "../../reactflow/elkGraph";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";

const ARCHITECTURE_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "190",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "92",
  "org.eclipse.elk.spacing.nodeNode": "96",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
};

export async function layoutArchitectureGraphView({
  nodes,
  edges,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  return executeElkLayout({
    mode: "ARCHITECTURE_GRAPH",
    layoutOptions: ARCHITECTURE_LAYOUT_OPTIONS,
    nodes: nodes.map((node) => ({
      node,
      ...resolveMeasuredNodeSize(node, sizeSnapshot, "ARCHITECTURE_GRAPH"),
    })),
    edges: edges.map((edge) => ({ edge })),
  });
}
