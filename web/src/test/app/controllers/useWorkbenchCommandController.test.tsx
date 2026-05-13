import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { requestSyncPreview, showDiffMode } from "../../../app/api";
import type { BridgeInvocationResult } from "../../../app/api";
import { useWorkbenchCommandController } from "../../../app/controllers/useWorkbenchCommandController";

vi.mock("../../../app/api", () => ({
  applyCodeDrafts: vi.fn(),
  exportMermaid: vi.fn(),
  requestAnalysisDisplayMode: vi.fn(),
  requestCodeDraftsAsync: vi.fn(),
  requestDraftNavigation: vi.fn(),
  requestExpandOverflowNode: vi.fn(),
  requestGenerationPlanAsync: vi.fn(),
  requestGenerationPlanDiscussionAsync: vi.fn(),
  requestOpenSettings: vi.fn(),
  requestSyncPreview: vi.fn(),
  showDiffMode: vi.fn(),
}));

function renderController() {
  const runBridgeCommand = vi.fn((_label: string, command: () => BridgeInvocationResult, options?: unknown) => {
    command();
    return { ok: true } as const;
  });
  const submitAsyncBridgeCommand = vi.fn((_label: string, command: () => BridgeInvocationResult, options?: unknown) => {
    command();
    return { ok: true } as const;
  });
  const hook = renderHook(() =>
    useWorkbenchCommandController({
      bridgeCommands: {
        runBridgeCommand,
        submitAsyncBridgeCommand,
      },
    }),
  );
  return {
    ...hook,
    runBridgeCommand,
  };
}

describe("useWorkbenchCommandController", () => {
  it("does not show optimistic success for backend-driven diff and sync commands", () => {
    const { result, runBridgeCommand } = renderController();

    act(() => {
      result.current.handleShowDiffMode();
      result.current.handleRequestSyncPreview();
    });

    expect(showDiffMode).toHaveBeenCalled();
    expect(requestSyncPreview).toHaveBeenCalled();
    expect(runBridgeCommand).toHaveBeenCalledWith("代码对比", expect.any(Function));
    expect(runBridgeCommand).toHaveBeenCalledWith("同步预览", expect.any(Function));
  });
});
