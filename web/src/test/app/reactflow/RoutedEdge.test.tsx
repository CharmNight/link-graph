import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RoutedEdge } from "../../../app/reactflow/RoutedEdge";

const { useStoreMock } = vi.hoisted(() => ({
  useStoreMock: vi.fn(),
}));

vi.mock("@xyflow/react", () => ({
  BaseEdge: ({
    id,
    path,
    labelX,
    labelY,
  }: {
    id: string;
    path: string;
    labelX: number;
    labelY: number;
  }) => (
    <div
      data-testid="base-edge"
      data-edge-id={id}
      data-path={path}
      data-label-x={String(labelX)}
      data-label-y={String(labelY)}
    />
  ),
  useStore: useStoreMock,
}));

function createInternalNode(
  id: string,
  x: number,
  y: number,
  width: number,
  height: number,
) {
  return {
    id,
    measured: { width, height },
    internals: {
      positionAbsolute: { x, y },
      z: 0,
      userNode: {
        id,
      },
    },
  };
}

function installNodeLookup(...nodes: Array<ReturnType<typeof createInternalNode>>) {
  const nodeLookup = new Map(nodes.map((node) => [node.id, node]));
  const valuesSpy = vi.spyOn(nodeLookup, "values");
  useStoreMock.mockImplementation((selector: (state: { nodeLookup: typeof nodeLookup }) => unknown) =>
    selector({ nodeLookup }),
  );
  return { nodeLookup, valuesSpy };
}

function parsePath(path: string) {
  return path
    .split(/[ML]/)
    .map((segment) => segment.trim())
    .filter(Boolean)
    .map((segment) => {
      const [x, y] = segment.split(/\s+/).map(Number);
      return { x, y };
    });
}

function segments(path: string) {
  const points = parsePath(path);
  return points.slice(1).map((point, index) => ({
    startPoint: points[index]!,
    endPoint: point,
  }));
}

function expectOrthogonalPath(path: string) {
  const pathSegments = segments(path);
  expect(pathSegments.length).toBeGreaterThan(0);
  pathSegments.forEach((segment) => {
    expect(
      Math.abs(segment.startPoint.x - segment.endPoint.x) <= 0.5
      || Math.abs(segment.startPoint.y - segment.endPoint.y) <= 0.5,
    ).toBe(true);
  });
}

function segmentIntersectsRect(
  segment: { startPoint: { x: number; y: number }; endPoint: { x: number; y: number } },
  rect: { left: number; right: number; top: number; bottom: number },
) {
  if (Math.abs(segment.startPoint.x - segment.endPoint.x) <= 0.5) {
    const x = segment.startPoint.x;
    if (x <= rect.left || x >= rect.right) {
      return false;
    }
    const top = Math.min(segment.startPoint.y, segment.endPoint.y);
    const bottom = Math.max(segment.startPoint.y, segment.endPoint.y);
    return Math.max(top, rect.top) < Math.min(bottom, rect.bottom);
  }
  const y = segment.startPoint.y;
  if (y <= rect.top || y >= rect.bottom) {
    return false;
  }
  const left = Math.min(segment.startPoint.x, segment.endPoint.x);
  const right = Math.max(segment.startPoint.x, segment.endPoint.x);
  return Math.max(left, rect.left) < Math.min(right, rect.right);
}

describe("RoutedEdge", () => {
  it("moves the leading horizontal corridor with a left or right source endpoint when the node shifts horizontally", () => {
    installNodeLookup(
      createInternalNode("source", 0, 0, 200, 120),
      createInternalNode("target", 220, 180, 200, 120),
    );
    render(
      <RoutedEdge
        id="edge:flow"
        source="source"
        target="target"
        sourceX={160}
        sourceY={80}
        targetX={220}
        targetY={260}
        sourcePosition={"left" as never}
        targetPosition={"top" as never}
        data={{
          route: {
            sections: [
              {
                startPoint: { x: 100, y: 80 },
                bendPoints: [
                  { x: 40, y: 80 },
                  { x: 40, y: 180 },
                  { x: 220, y: 180 },
                ],
                endPoint: { x: 220, y: 260 },
              },
            ],
          },
        }}
      />,
    );

    expect(screen.getByTestId("base-edge")).toHaveAttribute(
      "data-path",
      "M 160 80 L 100 80 L 100 180 L 220 180 L 220 260",
    );
  });

  it("keeps the stored orthogonal route when the live endpoints only drift by a few pixels", () => {
    installNodeLookup(
      createInternalNode("source", 0, 0, 200, 120),
      createInternalNode("target", 160, 180, 200, 120),
    );
    render(
      <RoutedEdge
        id="edge:flow"
        source="source"
        target="target"
        sourceX={104}
        sourceY={82}
        targetX={163}
        targetY={182}
        sourcePosition={"bottom" as never}
        targetPosition={"top" as never}
        data={{
          route: {
            sections: [
              {
                startPoint: { x: 100, y: 80 },
                bendPoints: [
                  { x: 100, y: 120 },
                  { x: 160, y: 120 },
                ],
                endPoint: { x: 160, y: 180 },
              },
            ],
          },
        }}
      />,
    );

    expect(screen.getByTestId("base-edge")).toHaveAttribute(
      "data-path",
      "M 104 82 L 104 120 L 163 120 L 163 182",
    );
  });

  it("keeps the routed topology when live endpoints materially diverge instead of replacing it with a generic preview path", () => {
    installNodeLookup(
      createInternalNode("source", 0, 0, 200, 120),
      createInternalNode("target", 160, 180, 200, 120),
    );
    render(
      <RoutedEdge
        id="edge:flow"
        source="source"
        target="target"
        sourceX={124}
        sourceY={112}
        targetX={208}
        targetY={232}
        sourcePosition={"bottom" as never}
        targetPosition={"top" as never}
        data={{
          route: {
            sections: [
              {
                startPoint: { x: 100, y: 80 },
                bendPoints: [
                  { x: 100, y: 120 },
                  { x: 160, y: 120 },
                ],
                endPoint: { x: 160, y: 180 },
              },
            ],
          },
        }}
      />,
    );

    expect(screen.getByTestId("base-edge")).toHaveAttribute(
      "data-path",
      "M 124 112 L 124 120 L 208 120 L 208 232",
    );
  });

  it("keeps a large-drag route orthogonal instead of falling back to a coarse preview path", () => {
    installNodeLookup(
      createInternalNode("source", 100, 60, 240, 120),
      createInternalNode("target", 360, 340, 240, 120),
    );
    render(
      <RoutedEdge
        id="edge:flow"
        source="source"
        target="target"
        sourceX={220}
        sourceY={180}
        targetX={480}
        targetY={340}
        sourcePosition={"bottom" as never}
        targetPosition={"top" as never}
        data={{
          route: {
            sections: [
              {
                startPoint: { x: 100, y: 80 },
                bendPoints: [
                  { x: 100, y: 120 },
                  { x: 160, y: 120 },
                ],
                endPoint: { x: 160, y: 180 },
              },
            ],
          },
        }}
      />,
    );

    const path = screen.getByTestId("base-edge").getAttribute("data-path") ?? "";
    const points = parsePath(path);

    expect(points[0]).toEqual({ x: 220, y: 180 });
    expect(points[points.length - 1]).toEqual({ x: 480, y: 340 });
    expectOrthogonalPath(path);
  });

  it("builds an orthogonal path from the live node handles when the edge has no stored route", () => {
    installNodeLookup(
      createInternalNode("source", 100, 60, 240, 120),
      createInternalNode("target", 360, 280, 240, 120),
    );

    render(
      <RoutedEdge
        id="edge:orthogonal"
        source="source"
        target="target"
        sourceX={220}
        sourceY={180}
        targetX={480}
        targetY={280}
        sourcePosition={"bottom" as never}
        targetPosition={"top" as never}
      />,
    );

    const path = screen.getByTestId("base-edge").getAttribute("data-path") ?? "";
    const points = parsePath(path);

    expect(points[0]).toEqual({ x: 220, y: 180 });
    expect(points[points.length - 1]).toEqual({ x: 480, y: 280 });
    expectOrthogonalPath(path);
    expect(points[1]?.x).toBeCloseTo(220, 5);
    expect(points[points.length - 2]?.x).toBeCloseTo(480, 5);
  });

  it("reroutes stale straight edges around intermediate node obstacles instead of drawing through them", () => {
    installNodeLookup(
      createInternalNode("source", 100, 60, 240, 120),
      createInternalNode("target", 520, 320, 240, 120),
      createInternalNode("obstacle", 360, 180, 180, 140),
    );

    render(
      <RoutedEdge
        id="edge:obstacle"
        source="source"
        target="target"
        sourceX={220}
        sourceY={180}
        targetX={640}
        targetY={320}
        sourcePosition={"bottom" as never}
        targetPosition={"top" as never}
        data={{
          route: {
            sections: [
              {
                startPoint: { x: 220, y: 180 },
                endPoint: { x: 640, y: 320 },
              },
            ],
          },
        }}
      />,
    );

    const path = screen.getByTestId("base-edge").getAttribute("data-path") ?? "";
    expectOrthogonalPath(path);
    const obstacleRect = {
      left: 360,
      right: 540,
      top: 180,
      bottom: 320,
    };
    expect(
      segments(path).some((segment) => segmentIntersectsRect(segment, obstacleRect)),
    ).toBe(false);
  });

  it("keeps stored routes on dense graphs instead of rebuilding an obstacle grid per edge", () => {
    const obstacles = Array.from({ length: 56 }, (_, index) =>
      createInternalNode(
        `obstacle-${index}`,
        900 + (index % 7) * 72,
        760 + Math.floor(index / 7) * 34,
        48,
        24,
      ),
    );
    const { valuesSpy } = installNodeLookup(
      createInternalNode("source", 100, 60, 240, 120),
      createInternalNode("target", 520, 320, 240, 120),
      ...obstacles,
    );

    render(
      <RoutedEdge
        id="edge:dense"
        source="source"
        target="target"
        sourceX={220}
        sourceY={180}
        targetX={640}
        targetY={320}
        sourcePosition={"bottom" as never}
        targetPosition={"top" as never}
        data={{
          route: {
            sections: [
              {
                startPoint: { x: 220, y: 180 },
                bendPoints: [
                  { x: 220, y: 280 },
                  { x: 640, y: 280 },
                ],
                endPoint: { x: 640, y: 320 },
              },
            ],
          },
        }}
      />,
    );

    expect(screen.getByTestId("base-edge")).toHaveAttribute(
      "data-path",
      "M 220 180 L 220 280 L 640 280 L 640 320",
    );
    expect(valuesSpy).not.toHaveBeenCalled();
  });

  it("repairs stale diagonal routes on dense graphs without drawing through nearby nodes", () => {
    const farObstacles = Array.from({ length: 56 }, (_, index) =>
      createInternalNode(
        `far-obstacle-${index}`,
        900 + (index % 7) * 72,
        760 + Math.floor(index / 7) * 34,
        48,
        24,
      ),
    );
    installNodeLookup(
      createInternalNode("source", 100, 60, 240, 120),
      createInternalNode("target", 520, 320, 240, 120),
      createInternalNode("near-obstacle", 360, 180, 180, 140),
      ...farObstacles,
    );

    render(
      <RoutedEdge
        id="edge:dense-stale"
        source="source"
        target="target"
        sourceX={220}
        sourceY={180}
        targetX={640}
        targetY={320}
        sourcePosition={"bottom" as never}
        targetPosition={"top" as never}
        data={{
          route: {
            sections: [
              {
                startPoint: { x: 220, y: 180 },
                endPoint: { x: 640, y: 320 },
              },
            ],
          },
        }}
      />,
    );

    const path = screen.getByTestId("base-edge").getAttribute("data-path") ?? "";
    const nearObstacleRect = {
      left: 360,
      right: 540,
      top: 180,
      bottom: 320,
    };

    expectOrthogonalPath(path);
    expect(
      segments(path).some((segment) => segmentIntersectsRect(segment, nearObstacleRect)),
    ).toBe(false);
  });
});
