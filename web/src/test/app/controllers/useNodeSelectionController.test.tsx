import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { publishNodeSelected } from "../../../app/api";
import { useNodeSelectionController } from "../../../app/controllers/useNodeSelectionController";
import type { GraphFocusRequest } from "../../../app/types";

vi.mock("../../../app/api", () => ({
  publishNodeSelected: vi.fn((nodeId: string) => ({ ok: true, nodeId })),
}));

function renderSelectionController() {
  const runBridgeCommand = vi.fn((_label: string, command: () => unknown) => command());
  const hook = renderHook(() => {
    const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
    const [detailNodeId, setDetailNodeId] = useState<string | null>(null);
    const [selectionGroupNodeIds, setSelectionGroupNodeIds] = useState<string[]>(["stale-node"]);
    const controller = useNodeSelectionController({
      bridgeCommands: {
        runBridgeCommand,
      } as never,
      setSelectedNodeId,
      setDetailNodeId,
      setSelectionGroupNodeIds,
    });
    return {
      controller,
      selectedNodeId,
      detailNodeId,
      selectionGroupNodeIds,
      focusNodeRequest: controller.focusNodeRequest as GraphFocusRequest | null,
    };
  });
  return {
    ...hook,
    runBridgeCommand,
  };
}

describe("useNodeSelectionController", () => {
  it("selects explanation targets, clears stale group selection, requests focus, and syncs the bridge", () => {
    const { result, runBridgeCommand } = renderSelectionController();

    act(() => {
      result.current.controller.selectExplanationTargetNode("node-a", { focusViewport: true });
    });

    expect(result.current.selectedNodeId).toBe("node-a");
    expect(result.current.selectionGroupNodeIds).toEqual([]);
    expect(result.current.focusNodeRequest).toEqual({
      nodeId: "node-a",
      nonce: 1,
    });
    expect(runBridgeCommand).toHaveBeenCalledTimes(1);
    expect(publishNodeSelected).toHaveBeenCalledWith("node-a");
  });

  it("preserves single-node selection separately from multi-node group selection", () => {
    const { result } = renderSelectionController();

    act(() => {
      result.current.controller.handleSelectionGroupChange(["node-a", "node-a", "node-b"]);
    });
    expect(result.current.selectionGroupNodeIds).toEqual(["node-a", "node-b"]);

    act(() => {
      result.current.controller.handleSelectionGroupChange(["node-c"]);
    });
    expect(result.current.selectedNodeId).toBe("node-c");
    expect(result.current.selectionGroupNodeIds).toEqual([]);
  });
});
