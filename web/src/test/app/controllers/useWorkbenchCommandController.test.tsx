import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  requestAnalysisDisplayMode,
  requestArchitectureGraph,
  requestClassDiagram,
  requestPackageDependencyGraph,
  requestReviewGraph,
  requestSyncPreview,
  showDiffMode,
} from "../../../app/api";
import type { BridgeInvocationResult } from "../../../app/api";
import { useWorkbenchCommandController } from "../../../app/controllers/useWorkbenchCommandController";

vi.mock("../../../app/api", () => ({
  applyCodeDrafts: vi.fn(),
  exportMermaid: vi.fn(),
  requestAnalysisDisplayMode: vi.fn(),
  requestArchitectureGraph: vi.fn(),
  requestCodeDraftsAsync: vi.fn(),
  requestClassDiagram: vi.fn(),
  requestDraftNavigation: vi.fn(),
  requestExpandOverflowNode: vi.fn(),
  requestGenerationPlanAsync: vi.fn(),
  requestGenerationPlanDiscussionAsync: vi.fn(),
  requestOpenSettings: vi.fn(),
  requestPackageDependencyGraph: vi.fn(),
  requestReviewGraph: vi.fn(),
  requestSyncPreview: vi.fn(),
  showDiffMode: vi.fn(),
}));

function renderController(availability = {
  architectureGraphLoaded: false,
  classDiagramLoaded: false,
  reviewGraphLoaded: false,
}) {
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
      availability,
    }),
  );
  return {
    ...hook,
    runBridgeCommand,
  };
}

describe("useWorkbenchCommandController", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

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

  it("loads indexed graph views through dedicated bridge commands", () => {
    const { result, runBridgeCommand } = renderController();

    act(() => {
      result.current.handleRequestAnalysisDisplayMode("ARCHITECTURE_GRAPH");
      result.current.handleRequestAnalysisDisplayMode("CLASS_DIAGRAM");
      result.current.handleRequestAnalysisDisplayMode("REVIEW_GRAPH", ["diff:docs"]);
    });

    expect(requestArchitectureGraph).toHaveBeenCalled();
    expect(requestClassDiagram).toHaveBeenCalledWith();
    expect(requestReviewGraph).toHaveBeenCalledWith(["diff:docs"]);
    expect(runBridgeCommand).toHaveBeenCalledWith("加载架构图", expect.any(Function));
    expect(runBridgeCommand).toHaveBeenCalledWith("加载类图", expect.any(Function));
    expect(runBridgeCommand).toHaveBeenCalledWith("加载 Review Graph", expect.any(Function));
  });

  it("always routes project-level indexed graph entries through the indexed graph lifecycle", () => {
    const { result, runBridgeCommand } = renderController();

    act(() => {
      result.current.handleRequestAnalysisDisplayMode("ARCHITECTURE_GRAPH");
      result.current.handleRequestAnalysisDisplayMode("CLASS_DIAGRAM");
      result.current.handleRequestAnalysisDisplayMode("REVIEW_GRAPH", ["diff:docs"]);
    });

    expect(requestArchitectureGraph).toHaveBeenCalledTimes(1);
    expect(requestClassDiagram).toHaveBeenCalledTimes(1);
    expect(requestReviewGraph).toHaveBeenCalledWith(["diff:docs"]);
    expect(requestAnalysisDisplayMode).not.toHaveBeenCalledWith("ARCHITECTURE_GRAPH");
    expect(requestAnalysisDisplayMode).not.toHaveBeenCalledWith("CLASS_DIAGRAM");
    expect(requestAnalysisDisplayMode).not.toHaveBeenCalledWith("REVIEW_GRAPH");
    expect(runBridgeCommand).toHaveBeenCalledWith("加载架构图", expect.any(Function));
    expect(runBridgeCommand).toHaveBeenCalledWith("加载类图", expect.any(Function));
    expect(runBridgeCommand).toHaveBeenCalledWith("加载 Review Graph", expect.any(Function));
  });

  it("switches to already-loaded indexed graph views without requesting reloads", () => {
    const { result, runBridgeCommand } = renderController({
      architectureGraphLoaded: true,
      classDiagramLoaded: true,
      reviewGraphLoaded: true,
    });

    act(() => {
      result.current.handleRequestAnalysisDisplayMode("ARCHITECTURE_GRAPH");
      result.current.handleRequestAnalysisDisplayMode("CLASS_DIAGRAM");
      result.current.handleRequestAnalysisDisplayMode("REVIEW_GRAPH");
    });

    expect(requestArchitectureGraph).not.toHaveBeenCalled();
    expect(requestClassDiagram).not.toHaveBeenCalled();
    expect(requestReviewGraph).not.toHaveBeenCalled();
    expect(requestAnalysisDisplayMode).toHaveBeenCalledWith("ARCHITECTURE_GRAPH");
    expect(requestAnalysisDisplayMode).toHaveBeenCalledWith("CLASS_DIAGRAM");
    expect(requestAnalysisDisplayMode).toHaveBeenCalledWith("REVIEW_GRAPH");
    expect(runBridgeCommand).toHaveBeenCalledWith("切换展示模式", expect.any(Function));
  });

  it("reloads Review Graph when a non-empty selection changes the review scope", () => {
    const { result } = renderController({
      architectureGraphLoaded: true,
      classDiagramLoaded: true,
      reviewGraphLoaded: true,
    });

    act(() => {
      result.current.handleRequestAnalysisDisplayMode("REVIEW_GRAPH", ["diff:docs"]);
    });

    expect(requestReviewGraph).toHaveBeenCalledWith(["diff:docs"]);
    expect(requestAnalysisDisplayMode).not.toHaveBeenCalledWith("REVIEW_GRAPH");
  });

  it("shows immediate feedback and preserves dependency options when requesting package dependencies", () => {
    const { result, runBridgeCommand } = renderController();

    act(() => {
      result.current.handleRequestPackageDependencyGraph(null, {
        includeExternalLibraries: false,
        includeJdk: false,
      });
    });

    expect(requestPackageDependencyGraph).toHaveBeenCalledWith(null, {
      includeExternalLibraries: false,
      includeJdk: false,
    });
    expect(runBridgeCommand).toHaveBeenCalledWith("加载包依赖", expect.any(Function), {
      successFeedback: {
        level: "INFO",
        message: "正在加载包依赖视图。",
      },
    });
  });

  it("requests class diagrams scoped to architecture nodes", () => {
    const { result, runBridgeCommand } = renderController();

    act(() => {
      result.current.handleRequestClassDiagram("component:orders");
    });

    expect(requestClassDiagram).toHaveBeenCalledWith("component:orders");
    expect(runBridgeCommand).toHaveBeenCalledWith("加载类图", expect.any(Function));
  });
});
