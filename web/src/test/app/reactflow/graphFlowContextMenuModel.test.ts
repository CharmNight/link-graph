import { describe, expect, it } from "vitest";
import {
  resolveContextMenuPoint,
  resolvePanePositionFromRect,
} from "../../../app/reactflow/graphFlowContextMenuModel";

describe("graphFlowContextMenuModel", () => {
  it("keeps context menus inside a viewport with a safe margin", () => {
    expect(resolveContextMenuPoint(2, 8, { width: 320, height: 240 })).toEqual({ x: 16, y: 16 });
    expect(resolveContextMenuPoint(900, 700, { width: 320, height: 240 })).toEqual({ x: 64, y: 16 });
    expect(resolveContextMenuPoint(120, 80, { width: 800, height: 600 })).toEqual({ x: 120, y: 80 });
  });

  it("returns raw coordinates when no viewport is available", () => {
    expect(resolveContextMenuPoint(900, 700, null)).toEqual({ x: 900, y: 700 });
  });

  it("resolves pane positions from shell bounds without going negative", () => {
    expect(resolvePanePositionFromRect(150, 260, { left: 100, top: 200 })).toEqual({ x: 50, y: 60 });
    expect(resolvePanePositionFromRect(80, 160, { left: 100, top: 200 })).toEqual({ x: 0, y: 0 });
    expect(resolvePanePositionFromRect(150, 260, null)).toBeUndefined();
  });
});
