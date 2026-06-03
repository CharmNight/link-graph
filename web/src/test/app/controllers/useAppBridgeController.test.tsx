import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import {
  applySingleCodeDraft,
  importMermaid,
  openCodeDraftNativeDiff,
  requestArtifactContent,
  requestDiffReviewAsync,
  updateWorkbenchSectionPreference,
} from "../../../app/api";
import { useAppBridgeController } from "../../../app/controllers/useAppBridgeController";

vi.mock("../../../app/api", () => ({
  requestArtifactContent: vi.fn((artifactIds: string[]) => ({ ok: true, artifactIds })),
  updateWorkbenchSectionPreference: vi.fn((sectionId: string, expanded: boolean) => ({ ok: true, sectionId, expanded })),
  importMermaid: vi.fn((mermaid: string) => ({ ok: true, mermaid })),
  requestDiffReviewAsync: vi.fn((question: string, diffTargetItemIds: string[]) => ({ ok: true, question, diffTargetItemIds })),
  applySingleCodeDraft: vi.fn((draftId: string) => ({ ok: true, draftId })),
  openCodeDraftNativeDiff: vi.fn((draftId: string) => ({ ok: true, draftId })),
}));

function renderController() {
  const runBridgeCommand = vi.fn((_label: string, command: () => unknown, options?: { onAccepted?: () => void }) => {
    const result = command();
    options?.onAccepted?.();
    return result;
  });
  const submitAsyncBridgeCommand = vi.fn((_label: string, command: () => unknown) => command());
  const hook = renderHook(() => {
    const [artifactContents, setArtifactContents] = useState<Record<string, string>>({ loaded: "cached artifact" });
    const [importDialogOpen, setImportDialogOpen] = useState(true);
    const [workbenchSectionPreferences, setWorkbenchSectionPreferences] = useState<Record<string, boolean>>({});
    return {
      artifactContents,
      importDialogOpen,
      workbenchSectionPreferences,
      controller: useAppBridgeController({
        artifactContents,
        bridgeCommands: {
          runBridgeCommand,
          submitAsyncBridgeCommand,
        } as never,
        setImportDialogOpen,
        setWorkbenchSectionPreferences,
      }),
      setArtifactContents,
    };
  });
  return {
    ...hook,
    runBridgeCommand,
    submitAsyncBridgeCommand,
  };
}

describe("useAppBridgeController", () => {
  it("requests missing artifacts through the bridge but skips cached ones", () => {
    const { result } = renderController();

    act(() => {
      result.current.controller.handleRequestArtifact("loaded");
      result.current.controller.handleRequestArtifact("missing");
    });

    expect(requestArtifactContent).toHaveBeenCalledTimes(1);
    expect(requestArtifactContent).toHaveBeenCalledWith(["missing"]);
  });

  it("delegates workbench preference, import, diff review, and single-draft actions through bridge command wrappers", () => {
    const { result, runBridgeCommand, submitAsyncBridgeCommand } = renderController();

    act(() => {
      result.current.controller.handleWorkbenchSectionPreferenceChange("qa.request-status", true);
      result.current.controller.handleConfirmImportMermaid("graph TD\nA-->B");
      result.current.controller.handleRequestDiffReview("why changed?", ["node-a"]);
      result.current.controller.handleWriteSingleCodeDraft("draft-1");
      result.current.controller.handleOpenCodeDraftNativeDiff("draft-1");
    });

    expect(updateWorkbenchSectionPreference).toHaveBeenCalledWith("qa.request-status", true);
    expect(importMermaid).toHaveBeenCalledWith("graph TD\nA-->B");
    expect(requestDiffReviewAsync).toHaveBeenCalledWith("why changed?", ["node-a"]);
    expect(applySingleCodeDraft).toHaveBeenCalledWith("draft-1");
    expect(openCodeDraftNativeDiff).toHaveBeenCalledWith("draft-1");
    expect(result.current.workbenchSectionPreferences["qa.request-status"]).toBe(true);
    expect(runBridgeCommand).toHaveBeenCalledTimes(3);
    expect(submitAsyncBridgeCommand).toHaveBeenCalledTimes(1);
    expect(result.current.importDialogOpen).toBe(false);
  });
});
