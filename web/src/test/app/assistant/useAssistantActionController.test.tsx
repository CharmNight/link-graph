import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import type { BridgeInvocationResult } from "../../../app/api";
import { useAssistantActionController, type AssistantDisplayModeDocuments } from "../../../app/assistant/useAssistantActionController";
import type {
  AnalysisDisplayMode,
  AssistantSessionState,
  LinkGraphDocument,
  LinkGraphSceneId,
} from "../../../app/types";

const emptyDocument: LinkGraphDocument = {
  nodes: [],
  edges: [],
};

const selectedDocument: LinkGraphDocument = {
  nodes: [
    {
      id: "method:submit-order",
      type: "METHOD",
      title: "submitOrder",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
    },
  ],
  edges: [],
};

const viewDocuments: AssistantDisplayModeDocuments = {
  FACT_GRAPH: { visibleGraph: emptyDocument },
  FLOWCHART: { visibleGraph: selectedDocument },
  RESOURCE_RELATION_VIEW: { visibleGraph: emptyDocument },
  ARCHITECTURE_GRAPH: { visibleGraph: emptyDocument },
  CLASS_DIAGRAM: { visibleGraph: emptyDocument },
  REVIEW_GRAPH: { visibleGraph: emptyDocument },
};

function sceneIdForDisplayMode(mode: AnalysisDisplayMode): LinkGraphSceneId {
  switch (mode) {
    case "FACT_GRAPH":
      return "WORKSPACE_FACT";
    case "RESOURCE_RELATION_VIEW":
      return "WORKSPACE_RESOURCE_RELATION";
    case "ARCHITECTURE_GRAPH":
      return "WORKSPACE_ARCHITECTURE_GRAPH";
    case "CLASS_DIAGRAM":
      return "WORKSPACE_CLASS_DIAGRAM";
    case "REVIEW_GRAPH":
      return "WORKSPACE_REVIEW_GRAPH";
    case "FLOWCHART":
      return "WORKSPACE_FLOWCHART";
  }
}

function initialAssistantSession(): AssistantSessionState {
  return {
    sessionId: "assistant-session-test",
    activeIntent: "GENERATE_CODE",
    activeActionId: "GENERATE_IMPLEMENTATION",
    contextLocked: false,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: null,
      scopeLabel: "submitOrder",
    },
    composer: {
      draft: "",
      target: { kind: "NewTask" },
      actionId: "GENERATE_IMPLEMENTATION",
      sceneId: "WORKSPACE_FLOWCHART",
    },
    turns: [],
  };
}

function renderController() {
  const submittedPayloads: unknown[] = [];
  const submitAsyncBridgeCommand = vi.fn((
    _scene: string,
    invoke: () => BridgeInvocationResult,
    options?: { onAccepted?: () => void },
  ) => {
    const result = invoke();
    options?.onAccepted?.();
    return result;
  });

  window.linkGraphBridge = {
    sendCommand: vi.fn((command) => {
      submittedPayloads.push(command.payload);
    }),
  };

  const hook = renderHook(() => {
    const [assistantSessionState, setAssistantSessionState] = useState(initialAssistantSession());
    const [activeWorkflowStage, setActiveWorkflowStage] = useState("draft");

    const controller = useAssistantActionController({
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: sceneIdForDisplayMode("FLOWCHART"),
      selectedMethodSignature: null,
      selectedNodeId: "method:submit-order",
      anchorNodeId: null,
      selectionGroupNodeIds: [],
      diffTargetItemIds: [],
      diffItems: [],
      viewDocuments,
      assistantSessionState,
      setAssistantSessionState,
      selectedExplanationGranularity: "BUSINESS",
      bridgeCommands: {
        submitAsyncBridgeCommand,
      },
      setActiveWorkflowStage,
      onExplanationAccepted: vi.fn(),
    });

    return {
      activeWorkflowStage,
      assistantSessionState,
      controller,
    };
  });

  return {
    ...hook,
    submittedPayloads,
    submitAsyncBridgeCommand,
  };
}

describe("useAssistantActionController", () => {
  it("derives assistant submission action from the current intent when active action state is stale", () => {
    const hook = renderController();

    act(() => {
      hook.result.current.controller.handleAssistantSubmit("EXPLAIN_CODE", "讲解当前链路");
    });

    expect(hook.submittedPayloads).toEqual([
      expect.objectContaining({
        actionId: "EXPLAIN_FLOW",
        intent: "EXPLAIN_CODE",
        prompt: "讲解当前链路",
      }),
    ]);
    expect(hook.result.current.activeWorkflowStage).toBe("understand");
    expect(hook.result.current.assistantSessionState.activeActionId).toBe("EXPLAIN_FLOW");
  });
});
