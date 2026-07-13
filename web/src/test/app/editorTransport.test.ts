import { afterEach, describe, expect, it, vi } from "vitest";
import type { LinkGraphBootstrapState, LinkGraphIncrementalTransportEnvelope, LinkGraphSnapshotEnvelope } from "../../app/types";
import { EMPTY_STATE } from "../../app/sampleState";
import {
  acknowledgeSnapshot,
  announceFrontendReady,
  dispatchBootstrapForTest,
  resetEditorTransportForTest,
  subscribeBootstrap,
} from "../../app/editorTransport";

const sampleState = {
  currentSceneId: "WORKSPACE_FACT",
  sceneStates: {
    WORKSPACE_FACT: {
      selectedNodeId: "method:submit-order",
    },
  },
  workspaceGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
      },
    ],
    edges: [],
  },
  workspaceBaseGraph: {
    nodes: [],
    edges: [],
  },
  semanticFactGraph: {
    nodes: [],
    edges: [],
  },
  mermaidIssues: [],
  diffItems: [],
  syncPreviewItems: [],
} as unknown as LinkGraphBootstrapState;

function envelope(revision: number): LinkGraphSnapshotEnvelope {
  return {
    sessionId: "session-1",
    revision,
    state: {
      ...structuredClone(sampleState),
      snapshotRevision: revision,
    },
  };
}

function artifactSlice(revision: number): LinkGraphIncrementalTransportEnvelope {
  return {
    type: "ARTIFACT_SLICE",
    sessionId: "session-1",
    revision,
    state: {
      artifactContents: {
        "artifact:draft-1": "public class OrderDraftDto {}",
      },
      snapshotRevision: revision,
    },
  };
}

function feedbackSlice(revision: number): LinkGraphIncrementalTransportEnvelope {
  return {
    type: "FEEDBACK_SLICE",
    sessionId: "session-1",
    revision,
    state: {
      operationFeedback: {
        level: "INFO",
        message: "Saved layout",
      },
      snapshotRevision: revision,
      lastMessageType: "operationFeedback",
    },
  };
}

describe("editorTransport", () => {
  afterEach(() => {
    resetEditorTransportForTest();
    window.__linkGraphDebugEnabled = false;
    window.linkGraphDebugTrace = undefined;
    window.__linkGraphTraceHistory = undefined;
  });

  it("replays the latest bootstrap after frontend announces ready", () => {
    dispatchBootstrapForTest(envelope(1));

    const received: number[] = [];
    const unsubscribe = subscribeBootstrap((nextEnvelope) => {
      received.push(nextEnvelope.revision);
    });

    announceFrontendReady();

    expect(received).toEqual([1]);
    unsubscribe();
  });

  it("acknowledges only the latest applied revision", () => {
    const onAck = vi.fn();
    dispatchBootstrapForTest(envelope(1));
    dispatchBootstrapForTest(envelope(2));

    const unsubscribe = subscribeBootstrap((nextEnvelope) => {
      acknowledgeSnapshot(nextEnvelope.revision, onAck);
    });

    announceFrontendReady();

    expect(onAck).toHaveBeenCalledTimes(1);
    expect(onAck).toHaveBeenCalledWith(2);
    unsubscribe();
  });

  it("merges artifact slices into the latest snapshot state without overwriting graph state", () => {
    dispatchBootstrapForTest(envelope(1));
    const receivedArtifacts: Array<Record<string, string> | undefined> = [];

    const unsubscribe = subscribeBootstrap((nextEnvelope) => {
      receivedArtifacts.push((nextEnvelope.state as LinkGraphBootstrapState & {
        artifactContents?: Record<string, string>;
      }).artifactContents);
    });

    announceFrontendReady();
    dispatchBootstrapForTest(artifactSlice(1));

    expect(receivedArtifacts.at(-1)).toEqual({
      "artifact:draft-1": "public class OrderDraftDto {}",
    });
    unsubscribe();
  });

  it("preserves graph object identity while merging artifact slices", () => {
    dispatchBootstrapForTest(envelope(1));
    let initialWorkingGraph: LinkGraphBootstrapState["workspaceGraph"] | undefined;
    let slicedWorkingGraph: LinkGraphBootstrapState["workspaceGraph"] | undefined;

    const unsubscribe = subscribeBootstrap((nextEnvelope) => {
      if (!initialWorkingGraph) {
        initialWorkingGraph = nextEnvelope.state.workspaceGraph;
      } else {
        slicedWorkingGraph = nextEnvelope.state.workspaceGraph;
      }
    });

    announceFrontendReady();
    dispatchBootstrapForTest(artifactSlice(1));

    expect(slicedWorkingGraph).toBe(initialWorkingGraph);
    unsubscribe();
  });

  it("merges feedback slices into the latest snapshot state and dispatches same-revision updates", () => {
    dispatchBootstrapForTest(envelope(1));
    const received = [] as Array<{
      revision: number;
      selectedNodeId?: string | null;
      feedbackMessage?: string | null;
    }>;

    const unsubscribe = subscribeBootstrap((nextEnvelope) => {
      received.push({
        revision: nextEnvelope.revision,
        feedbackMessage: nextEnvelope.state.operationFeedback?.message ?? null,
      });
    });

    announceFrontendReady();
    dispatchBootstrapForTest(feedbackSlice(1));

    expect(received).toEqual([
      {
        revision: 1,
        feedbackMessage: null,
      },
      {
        revision: 1,
        feedbackMessage: "Saved layout",
      },
    ]);
    unsubscribe();
  });

  it("preserves graph object identity while merging feedback slices", () => {
    dispatchBootstrapForTest(envelope(1));
    let initialWorkingGraph: LinkGraphBootstrapState["workspaceGraph"] | undefined;
    let slicedWorkingGraph: LinkGraphBootstrapState["workspaceGraph"] | undefined;

    const unsubscribe = subscribeBootstrap((nextEnvelope) => {
      if (!initialWorkingGraph) {
        initialWorkingGraph = nextEnvelope.state.workspaceGraph;
      } else {
        slicedWorkingGraph = nextEnvelope.state.workspaceGraph;
      }
    });

    announceFrontendReady();
    dispatchBootstrapForTest(feedbackSlice(1));

    expect(slicedWorkingGraph).toBe(initialWorkingGraph);
    unsubscribe();
  });

  it("traces incremental slice clone merge and dispatch timing when debug bridge is enabled", () => {
    const traceSink = vi.fn();
    window.__linkGraphDebugEnabled = true;
    window.linkGraphDebugTrace = traceSink;
    dispatchBootstrapForTest(envelope(1));
    const received: number[] = [];

    const unsubscribe = subscribeBootstrap((nextEnvelope) => {
      received.push(nextEnvelope.revision);
    });

    announceFrontendReady();
    dispatchBootstrapForTest(artifactSlice(1));

    const traces = traceSink.mock.calls.map(([payload]) => JSON.parse(payload as string));
    const events = traces.map((trace) => trace.event);
    expect(events).toContain("editorTransport.incrementalSlice.merge");
    expect(events).toContain("editorTransport.flushLatestEnvelope");
    expect(traces).toContainEqual(
      expect.objectContaining({
        event: "editorTransport.incrementalSlice.merge",
        payload: expect.objectContaining({
          type: "ARTIFACT_SLICE",
        }),
      }),
    );
    expect(received).toEqual([1, 1]);
    unsubscribe();
  });

  it("ignores bootstrap events that do not carry a transport envelope", () => {
    const received: number[] = [];
    const unsubscribe = subscribeBootstrap((nextEnvelope) => {
      received.push(nextEnvelope.revision);
    });

    window.dispatchEvent(
      new CustomEvent("link-graph-bootstrap", {
        detail: {
          ...structuredClone(sampleState),
          snapshotRevision: 9,
        },
      }),
    );
    announceFrontendReady();

    expect(received).toEqual([]);
    unsubscribe();
  });

  it("keeps bootstrap result source unchanged", () => {
    announceFrontendReady();
    const received: string[] = [];
    const unsubscribe = subscribeBootstrap((nextEnvelope) => {
      received.push(nextEnvelope.state.generatedCodeDraftSource ?? "NULL");
    });

    dispatchBootstrapForTest({
      sessionId: "session",
      revision: 1,
      state: {
        ...EMPTY_STATE,
        generatedCodeDraftSource: "LOCAL_RULE",
      },
    });

    unsubscribe();
    expect(received).toEqual(["LOCAL_RULE"]);
    expect(window.linkGraphBootstrap?.generatedCodeDraftSource).toBe("LOCAL_RULE");
  });
});
