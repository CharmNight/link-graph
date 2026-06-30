import { describe, expect, it } from "vitest";

import type { NodeMeasuredSize } from "../../app/graph/nodeSizeRegistry";
import type { LinkGraphEdge, LinkGraphNode } from "../../app/types";
import { TARGET_RIGHT_PORT, LEFT_PORT } from "../../app/views/class-diagram/classDiagramLayoutModel";
import { targetPortForRoute } from "../../app/views/class-diagram/classDiagramPortRouting";

/**
 * 针对 targetPortForRoute 在 DATA-lane 源、非 DATA 目标场景下的端口选择回归测试。
 *
 * 旧实现有 `if (source.y > target.y) { ... } else { ... }` 两个完全一样的分支，
 * 属于死代码；这里覆盖上下/同高 + 左右/同列各种组合，确认 Y 不再影响 X 决策。
 */
describe("targetPortForRoute DATA-source non-DATA-target", () => {
  function dataNode(id: string, x: number, y: number, column = 0, row = 0): LinkGraphNode {
    return {
      id,
      type: "class",
      title: id,
      position: { x, y },
      metadata: {
        "layout.direction": "DATA",
        "layout.column": String(column),
        "layout.row": String(row),
      },
    } as unknown as LinkGraphNode;
  }

  function outgoingNode(id: string, x: number, y: number): LinkGraphNode {
    return {
      id,
      type: "class",
      title: id,
      position: { x, y },
      metadata: { "layout.direction": "OUTGOING", "layout.column": "0" },
    } as unknown as LinkGraphNode;
  }

  function structuralEdge(source: string, target: string): LinkGraphEdge {
    return {
      id: `edge-${source}-${target}`,
      source,
      target,
      type: "class-diagram-structural",
      label: "depends",
      metadata: { "jvm.relation.kind": "DEPENDENCY" },
    } as unknown as LinkGraphEdge;
  }

  const emptySize = new Map<string, NodeMeasuredSize>();
  const emptyEdgeIndex = new Map<string, LinkGraphEdge[]>();

  it("returns right port when target is to the right of source, regardless of Y", () => {
    const sourceAbove = dataNode("d1", 0, 0);
    const target = outgoingNode("t1", 200, 100);
    const nodeIndex = new Map<string, LinkGraphNode>([
      ["d1", sourceAbove],
      ["t1", target],
    ]);
    const edge = structuralEdge("d1", "t1");

    const port = targetPortForRoute(
      edge,
      sourceAbove,
      target,
      emptySize,
      emptyEdgeIndex,
      nodeIndex,
    );
    expect(port).toBe(TARGET_RIGHT_PORT);
  });

  it("returns left port when target is to the left of source, regardless of Y", () => {
    const source = dataNode("d1", 200, 0);
    const targetBelow = outgoingNode("t1", 0, 300);
    const nodeIndex = new Map<string, LinkGraphNode>([
      ["d1", source],
      ["t1", targetBelow],
    ]);
    const edge = structuralEdge("d1", "t1");

    const port = targetPortForRoute(edge, source, targetBelow, emptySize, emptyEdgeIndex, nodeIndex);
    expect(port).toBe(LEFT_PORT);
  });

  it("returns right port when source and target share the same Y (tie-breaks to right)", () => {
    const source = dataNode("d1", 0, 100);
    const target = outgoingNode("t1", 200, 100);
    const nodeIndex = new Map<string, LinkGraphNode>([
      ["d1", source],
      ["t1", target],
    ]);
    const edge = structuralEdge("d1", "t1");

    const port = targetPortForRoute(edge, source, target, emptySize, emptyEdgeIndex, nodeIndex);
    expect(port).toBe(TARGET_RIGHT_PORT);
  });

  it("returns right port when source is below target but target still to the right", () => {
    // 旧死代码下，Y 高低理论上会切换分支，但两个分支返回值一致；新实现直接无视 Y。
    const sourceBelow = dataNode("d1", 0, 500);
    const target = outgoingNode("t1", 200, 100);
    const nodeIndex = new Map<string, LinkGraphNode>([
      ["d1", sourceBelow],
      ["t1", target],
    ]);
    const edge = structuralEdge("d1", "t1");

    const port = targetPortForRoute(edge, sourceBelow, target, emptySize, emptyEdgeIndex, nodeIndex);
    expect(port).toBe(TARGET_RIGHT_PORT);
  });
});
