import type { LayoutOptions } from "elkjs/lib/elk-api";
import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import { executeElkLayout } from "../../reactflow/elkGraph";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";
import {
  BOTTOM_PORT,
  LEFT_PORT,
  RIGHT_PORT,
  SOURCE_LEFT_PORT,
  SOURCE_TOP_PORT,
  TARGET_BOTTOM_PORT,
  TARGET_RIGHT_PORT,
  TOP_PORT,
  measuredNodeSize,
} from "./classDiagramLayoutModel";
import { ClassDiagramTopologyBuilder } from "./classDiagramTopology";
import { ClassDiagramPlacementEngine } from "./classDiagramPlacement";
import { ClassDiagramRoutingEngine } from "./classDiagramRouting";
import { ClassDiagramReadabilityScorer } from "./classDiagramReadability";

const CLASS_DIAGRAM_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "NETWORK_SIMPLEX",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "220",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "120",
  "org.eclipse.elk.spacing.nodeNode": "104",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
  "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder": "true",
  "org.eclipse.elk.portConstraints": "FIXED_SIDE",
  "org.eclipse.elk.layered.mergeEdges": "false",
};

interface ClassDiagramPipeline {
  topologyBuilder: ClassDiagramTopologyBuilder;
  placementEngine: ClassDiagramPlacementEngine;
  readabilityScorer: ClassDiagramReadabilityScorer;
  routingEngine: ClassDiagramRoutingEngine;
}

function createClassDiagramPipeline(): ClassDiagramPipeline {
  const readabilityScorer = new ClassDiagramReadabilityScorer();
  return {
    topologyBuilder: new ClassDiagramTopologyBuilder(),
    placementEngine: new ClassDiagramPlacementEngine(),
    readabilityScorer,
    routingEngine: new ClassDiagramRoutingEngine(readabilityScorer),
  };
}

function runManualClassDiagramPipeline(
  request: MeasuredLayoutRequest,
  pipeline: ClassDiagramPipeline,
): { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] } {
  const topology = pipeline.topologyBuilder.build(request.nodes, request.edges, request.anchorNodeId);
  if (!topology) {
    return { nodes: request.nodes, edges: request.edges };
  }
  const placement = pipeline.placementEngine.place(topology, request.sizeSnapshot);
  const edges = pipeline.routingEngine.routeClassDiagramEdges(topology, placement, request.sizeSnapshot);
  const report = pipeline.readabilityScorer.scoreClassDiagramReadability(
    { nodes: placement.nodes, edges },
    request.sizeSnapshot,
  );
  return report.acceptedLayout;
}

async function runElkClassDiagramPipeline(
  request: MeasuredLayoutRequest,
  pipeline: ClassDiagramPipeline,
): Promise<{ nodes: LinkGraphNode[]; edges: LinkGraphEdge[] }> {
  const elkLayout = await executeElkLayout({
    mode: "CLASS_DIAGRAM",
    layoutOptions: CLASS_DIAGRAM_LAYOUT_OPTIONS,
    nodes: request.nodes.map((node) => ({
      node,
      ...measuredNodeSize(node, request.sizeSnapshot, node.id === request.anchorNodeId ? "ANCHOR" : undefined),
      ports: [
        { id: TOP_PORT, side: "NORTH" },
        { id: SOURCE_TOP_PORT, side: "NORTH" },
        { id: RIGHT_PORT, side: "EAST" },
        { id: TARGET_RIGHT_PORT, side: "EAST" },
        { id: BOTTOM_PORT, side: "SOUTH" },
        { id: TARGET_BOTTOM_PORT, side: "SOUTH" },
        { id: LEFT_PORT, side: "WEST" },
        { id: SOURCE_LEFT_PORT, side: "WEST" },
      ],
    })),
    edges: request.edges.map((edge) => ({
      edge: {
        ...edge,
        metadata: {
          ...(edge.metadata ?? {}),
          "layout.route": "class-diagram-elk",
          "layout.labelPlacement": edge.metadata?.["layout.labelPlacement"] ?? "target-stub",
        },
      },
    })),
  });
  const repairedLayout = pipeline.routingEngine.repairAndPreserveClassDiagramRoutes(elkLayout, request.sizeSnapshot);
  const report = pipeline.readabilityScorer.scoreClassDiagramReadability(repairedLayout, request.sizeSnapshot);
  return report.acceptedLayout;
}

export async function layoutClassDiagramView(request: MeasuredLayoutRequest) {
  const pipeline = createClassDiagramPipeline();
  if (request.nodes.length <= 32) {
    return runManualClassDiagramPipeline(request, pipeline);
  }
  return runElkClassDiagramPipeline(request, pipeline);
}
