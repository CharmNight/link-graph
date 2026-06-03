import type { CSSProperties } from "react";
import type { GraphPresentationLane, LinkGraphNode } from "../types";
import { sortedPresentationLanes } from "./graphPresentation";

interface GraphCanvasLanesProps {
  lanes: GraphPresentationLane[];
  nodes: LinkGraphNode[];
}

interface LaneRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

const NODE_WIDTH = 280;
const NODE_HEIGHT = 160;
const LANE_PADDING_X = 84;
const LANE_PADDING_Y = 56;
const MIN_LANE_WIDTH = 280;
const MIN_LANE_HEIGHT = 140;

function nodeMatchesLane(node: LinkGraphNode, lane: GraphPresentationLane): boolean {
  const metadata = node.metadata ?? {};
  return metadata["presentation.laneId"] === lane.id;
}

function laneRect(nodes: LinkGraphNode[], lane: GraphPresentationLane): LaneRect {
  const positioned = nodes.map((node) => node.position ?? { x: 0, y: 0 });
  const minX = Math.min(...positioned.map((position) => position.x));
  const minY = Math.min(...positioned.map((position) => position.y));
  const maxX = Math.max(...positioned.map((position) => position.x + NODE_WIDTH));
  const maxY = Math.max(...positioned.map((position) => position.y + NODE_HEIGHT));
  const horizontal = lane.axis === "ROW";
  return {
    left: Math.round(minX - LANE_PADDING_X),
    top: Math.round(minY - LANE_PADDING_Y),
    width: Math.round(Math.max(MIN_LANE_WIDTH, maxX - minX + LANE_PADDING_X * 2)),
    height: Math.round(Math.max(horizontal ? MIN_LANE_HEIGHT : NODE_HEIGHT, maxY - minY + LANE_PADDING_Y * 2)),
  };
}

function rectStyle(rect: LaneRect): CSSProperties {
  return {
    left: rect.left,
    top: rect.top,
    width: rect.width,
    height: rect.height,
  };
}

export function GraphCanvasLanes({
  lanes,
  nodes,
}: GraphCanvasLanesProps) {
  const laneCandidates = sortedPresentationLanes(lanes)
    .map((lane) => {
      const laneNodes = nodes.filter((node) => nodeMatchesLane(node, lane));
      return { lane, nodes: laneNodes };
    });
  const renderedLanes = laneCandidates.filter((entry) => entry.nodes.length > 0 && entry.nodes.length < nodes.length);

  return (
    <div
      className="graph-canvas-lanes graph-canvas-lanes--fact-graph"
      data-testid="graph-canvas-lanes"
      data-lane-count={renderedLanes.length}
      aria-hidden="true"
    >
      {renderedLanes.map(({ lane, nodes: laneNodes }) => (
        <div
          key={lane.id}
          className={`graph-canvas-lane graph-canvas-lane--${lane.axis.toLowerCase()}`}
          data-testid="graph-canvas-lane"
          data-lane-id={lane.id}
          data-lane-role={lane.role}
          data-lane-axis={lane.axis}
          style={rectStyle(laneRect(laneNodes, lane))}
        >
          <span className="graph-canvas-lane-label">{lane.label}</span>
        </div>
      ))}
    </div>
  );
}
