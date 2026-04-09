import { act, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { resetEditorTransportForTest } from "./editorTransport";
import { materializeThreeViewDocuments } from "./testBootstrapState";
import type { LinkGraphBootstrapState } from "./types";

vi.mock("./views/fact/FactGraphView", () => ({
  FactGraphView: ({
    view,
  }: {
    view: {
      visibleGraph: {
        nodes: Array<{
          id: string;
          position?: { x: number; y: number };
        }>;
      };
    };
  }) => (
    <div>
      {view.visibleGraph.nodes.map((node) => (
        <div key={node.id}>
          {`${node.id}:${node.position?.x ?? "na"},${node.position?.y ?? "na"}`}
        </div>
      ))}
    </div>
  ),
}));

const bootstrapState = materializeThreeViewDocuments({
  visibleGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: ["java.lang.String"],
        outputs: ["com.example.SubmitResult"],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        position: { x: 120, y: 96 },
      },
    ],
    edges: [],
  },
  workingGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: ["java.lang.String"],
        outputs: ["com.example.SubmitResult"],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        position: { x: 120, y: 96 },
      },
    ],
    edges: [],
  },
  referenceFactGraph: null,
  designBaselineGraph: null,
  mermaidIssues: [],
  diffItems: [],
  syncPreviewItems: [],
  selectedNodeId: "method:submit-order",
  semanticRevision: 3,
  layoutRevision: 1,
  snapshotRevision: 4,
} as const satisfies LinkGraphBootstrapState);

describe("App bootstrap revisions", () => {
  beforeEach(() => {
    resetEditorTransportForTest();
  });

  it("applies layout-only bootstrap updates without needing a semantic graph refresh", async () => {
    window.linkGraphBootstrap = structuredClone(bootstrapState);
    window.linkGraphBridge = {
      nodeSelected: vi.fn(),
    };

    render(<App />);

    expect(await screen.findByText("method:submit-order:120,96")).toBeInTheDocument();

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: materializeThreeViewDocuments({
            ...structuredClone(bootstrapState),
            visibleGraph: {
              nodes: [
                {
                  ...structuredClone(bootstrapState.visibleGraph.nodes[0]),
                  position: { x: 640, y: 320 },
                },
              ],
              edges: [],
            },
            workingGraph: {
              nodes: [
                {
                  ...structuredClone(bootstrapState.workingGraph.nodes[0]),
                  position: { x: 640, y: 320 },
                },
              ],
              edges: [],
            },
            layoutState: {
              positions: {
                "method:submit-order": { x: 640, y: 320 },
              },
            },
            semanticRevision: 3,
            layoutRevision: 2,
            snapshotRevision: 5,
          }),
        }),
      );
    });

    expect(await screen.findByText("method:submit-order:640,320")).toBeInTheDocument();
  });
});
