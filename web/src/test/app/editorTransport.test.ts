import { afterEach, describe, expect, it, vi } from "vitest";
import type { LinkGraphBootstrapState, LinkGraphIncrementalTransportEnvelope, LinkGraphSnapshotEnvelope } from "../../app/types";
import {
  acknowledgeSnapshot,
  announceFrontendReady,
  dispatchBootstrapForTest,
  resetEditorTransportForTest,
  subscribeBootstrap,
} from "../../app/editorTransport";

const sampleState = {
  workingGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: [],
        outputs: [],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
      },
    ],
    edges: [],
  },
  referenceFactGraph: null,
  designBaseline: null,
  layoutState: {
    positions: {
      "method:submit-order": { x: 120, y: 96 },
    },
  },
  semanticRevision: 1,
  layoutRevision: 1,
  snapshotRevision: 1,
  selectedNodeId: "method:submit-order",
  diffItems: [],
  syncPreviewItems: [],
  mermaidIssues: [],
  generatedCodeDrafts: [],
  generatedCodeDraftWarnings: [],
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

describe("editorTransport", () => {
  afterEach(() => {
    resetEditorTransportForTest();
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
});
