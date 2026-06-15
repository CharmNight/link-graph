import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import {
  applySingleCodeDraft,
  importMermaid,
  openCodeDraftNativeDiff,
  requestArtifactContent,
} from "../../../app/api";
import { useAppBridgeController } from "../../../app/controllers/useAppBridgeController";

vi.mock("../../../app/api", () => ({
  importMermaid: vi.fn((mermaid: string) => ({ ok: true, mermaid })),
  applySingleCodeDraft: vi.fn((draftId: string) => ({ ok: true, draftId })),
  openCodeDraftNativeDiff: vi.fn((draftId: string) => ({ ok: true, draftId })),
  requestArtifactContent: vi.fn((artifactIds: string[]) => ({ ok: true, artifactIds })),
}));

function renderController() {
  const runBridgeCommand = vi.fn((
    _label: string,
    command: () => unknown,
    options?: { onAccepted?: () => void; announceFailure?: boolean },
  ) => {
    const result = command();
    options?.onAccepted?.();
    return result;
  });
  const submitAsyncBridgeCommand = vi.fn((_label: string, command: () => unknown) => command());
  const hook = renderHook(() => {
    const [importDialogOpen, setImportDialogOpen] = useState(true);
    return {
      importDialogOpen,
      controller: useAppBridgeController({
        bridgeCommands: {
          runBridgeCommand,
          submitAsyncBridgeCommand,
        } as never,
        setImportDialogOpen,
      }),
    };
  });
  return {
    ...hook,
    runBridgeCommand,
    submitAsyncBridgeCommand,
  };
}

describe("useAppBridgeController", () => {
  it("delegates import and single-draft actions through bridge command wrappers", () => {
    const { result, runBridgeCommand, submitAsyncBridgeCommand } = renderController();

    act(() => {
      result.current.controller.handleConfirmImportMermaid("graph TD\nA-->B");
      result.current.controller.handleWriteSingleCodeDraft("draft-1");
      result.current.controller.handleOpenCodeDraftNativeDiff("draft-1");
    });

    expect(importMermaid).toHaveBeenCalledWith("graph TD\nA-->B");
    expect(applySingleCodeDraft).toHaveBeenCalledWith("draft-1");
    expect(openCodeDraftNativeDiff).toHaveBeenCalledWith("draft-1");
    expect(runBridgeCommand).toHaveBeenCalledTimes(3);
    expect(submitAsyncBridgeCommand).not.toHaveBeenCalled();
    expect(result.current.importDialogOpen).toBe(false);
  });

  it("delegates artifact content requests through the bridge command wrapper", () => {
    const { result, runBridgeCommand } = renderController();

    act(() => {
      result.current.controller.handleRequestArtifact("assistant-qa-prompt:turn-1");
    });

    expect(requestArtifactContent).toHaveBeenCalledWith(["assistant-qa-prompt:turn-1"]);
    expect(runBridgeCommand).toHaveBeenCalledWith("加载按需内容", expect.any(Function), {
      announceFailure: false,
    });
  });
});
