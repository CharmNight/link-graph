import { act, cleanup, render } from "@testing-library/react/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { resetEditorTransportForTest } from "../../app/editorTransport";
import { materializeThreeViewDocuments, type TestBootstrapState } from "../../app/testBootstrapState";
import type { LinkGraphEdge, LinkGraphNode } from "../../app/types";
import {
  expectBridgeCommand,
  expectNoBridgeCommand,
  installBridgeCommandSpy,
} from "./bridgeTestUtils";

vi.mock("../../app/views/fact/FactGraphView", () => ({
  FactGraphView: () => <div data-testid="graph-canvas" />,
}));

vi.mock("../../app/views/flowchart/FlowchartView", () => ({
  FlowchartView: () => <div data-testid="flowchart-canvas" />,
}));

vi.mock("../../app/views/resource/ResourceRelationView", () => ({
  ResourceRelationView: () => <div data-testid="resource-canvas" />,
}));

import { App } from "../../app/App";

const bootstrapState = materializeThreeViewDocuments({
  visibleGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        signature: "com.example.OrderController.submit():void",
        inputs: ["java.lang.String"],
        outputs: ["void"],
        doc: "提交订单入口。",
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        provenance: "CODE_ANALYSIS",
      },
      {
        id: "class:order-draft-dto",
        type: "CLASS",
        title: "OrderDraftDto",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        provenance: "CODE_ANALYSIS",
      },
    ],
    edges: [
      {
        id: "call:submit-order->order-draft-dto",
        type: "CALL",
        source: "method:submit-order",
        target: "class:order-draft-dto",
        provenance: "CODE_ANALYSIS",
      },
    ],
  },
  workingGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        signature: "com.example.OrderController.submit():void",
        inputs: ["java.lang.String"],
        outputs: ["void"],
        doc: "提交订单入口。",
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        provenance: "CODE_ANALYSIS",
      },
      {
        id: "class:order-draft-dto",
        type: "CLASS",
        title: "OrderDraftDto",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
        provenance: "CODE_ANALYSIS",
      },
    ],
    edges: [
      {
        id: "call:submit-order->order-draft-dto",
        type: "CALL",
        source: "method:submit-order",
        target: "class:order-draft-dto",
        provenance: "CODE_ANALYSIS",
      },
    ],
  },
  referenceFactGraph: null,
  designBaselineGraph: null,
  draftPatchPreview: null,
  canUndoDraftPatchApply: false,
  lastAppliedDraftPatchSummary: null,
  qaResult: null,
  diffReviewResult: null,
  mermaidIssues: [],
  diffItems: [],
  syncPreviewItems: [],
  generationPlan: null,
  generatedCodeDrafts: [],
  generatedCodeDraftWarnings: [],
  generatedCodeDraftWriteReport: null,
  selectedNodeId: "method:submit-order",
  operationFeedback: {
    level: "SUCCESS",
    message: "已加载当前编辑器上下文链路",
  },
  sourceNavigationState: {
    phase: "IDLE",
    nodeId: null,
    result: null,
    targetPath: null,
    line: null,
    column: null,
    errorMessage: null,
  },
});

function buildDenseBootstrapState(nodeCount: number): TestBootstrapState {
  const nodes: LinkGraphNode[] = Array.from({ length: nodeCount }, (_, index) => ({
    id: `method:dense-${index}`,
    type: "METHOD",
    title: `DenseController.handle${index}`,
    location: `src/main/java/com/example/DenseController.java:${index + 1}:1`,
    signature: `com.example.DenseController.handle${index}(java.lang.String):void`,
    inputs: ["java.lang.String"],
    outputs: ["void"],
    doc: `压测节点 ${index}`,
    confidence: "VERIFIED",
    binding: "CODE_BOUND",
    provenance: "CODE_ANALYSIS",
  }));
  const edges: LinkGraphEdge[] = nodes.slice(1).map((node, index) => ({
    id: `call:dense-${index}->dense-${index + 1}`,
    type: "CALL",
    source: nodes[index]!.id,
    target: node.id,
    provenance: "CODE_ANALYSIS",
  }));
  return materializeThreeViewDocuments({
    ...structuredClone(bootstrapState),
    visibleGraph: {
      nodes,
      edges,
    },
    workingGraph: {
      nodes,
      edges,
    },
    referenceFactGraph: null,
    selectedNodeId: nodes[0]?.id ?? null,
    operationFeedback: null,
  });
}

function dispatchBootstrapState(state: TestBootstrapState, revision = state.snapshotRevision ?? 1) {
  window.dispatchEvent(
    new CustomEvent("link-graph-bootstrap", {
      detail: {
        sessionId: "test-session-bootstrap-performance",
        revision,
        state,
      },
    }),
  );
}

describe("App bootstrap performance", () => {
  beforeEach(() => {
    resetEditorTransportForTest();
    vi.useRealTimers();
    window.__linkGraphDebugEnabled = false;
    window.__linkGraphTraceBuffer = [];
    window.__linkGraphInteractionProbe = false;
    window.linkGraphBootstrap = structuredClone(bootstrapState);
    installBridgeCommandSpy();
  });

  afterEach(() => {
    vi.useRealTimers();
    cleanup();
    window.__linkGraphDebugEnabled = false;
    window.__linkGraphTraceBuffer = [];
    window.__linkGraphInteractionProbe = false;
  });

  it("does not run auto layout again when bootstrap only refreshes feedback", () => {
    render(<App />);

    act(() => {
      dispatchBootstrapState({
        ...structuredClone(bootstrapState),
        operationFeedback: {
          level: "INFO",
          message: "只更新提示文案，不应再次整图布局。",
        },
      });
    });

    expectNoBridgeCommand("applyGraphEditScript");
  });

  it("does not treat working-graph-only semantic revisions as visible graph rebuilds", () => {
    window.__linkGraphDebugEnabled = true;
    window.__linkGraphTraceBuffer = [];
    render(<App />);

    act(() => {
      dispatchBootstrapState(materializeThreeViewDocuments({
        ...structuredClone(bootstrapState),
        workingGraph: {
          nodes: [
            ...structuredClone(bootstrapState.workingGraph.nodes),
            {
              id: "working-note:submit-order",
              type: "DOC_PAGE",
              title: "仅存在于工作图的草稿说明",
              inputs: [],
              outputs: [],
              confidence: "VERIFIED",
              binding: "CODE_BOUND",
              provenance: "AI_DRAFT",
            },
          ],
          edges: [
            ...structuredClone(bootstrapState.workingGraph.edges),
            {
              id: "generates:submit-order->draft-note",
              type: "GENERATES",
              source: "method:submit-order",
              target: "working-note:submit-order",
              provenance: "AI_DRAFT",
            },
          ],
        },
        semanticRevision: 4,
        layoutRevision: 1,
        snapshotRevision: 2,
      }));
    });

    expectNoBridgeCommand("applyGraphEditScript");
    expectNoBridgeCommand("layoutChanged");
  });

  it("does not re-run semantic normalization when bootstrap only advances layout revision", () => {
    const revisionState = {
      ...structuredClone(bootstrapState),
      semanticRevision: 3,
      layoutRevision: 1,
      snapshotRevision: 4,
    };

    window.linkGraphBootstrap = revisionState;
    render(<App />);

    act(() => {
      dispatchBootstrapState(materializeThreeViewDocuments({
        ...structuredClone(revisionState),
        visibleGraph: {
          nodes: [
            {
              ...structuredClone(revisionState.visibleGraph.nodes[0]),
              position: { x: 640, y: 320 },
            },
            structuredClone(revisionState.visibleGraph.nodes[1]),
          ],
          edges: structuredClone(revisionState.visibleGraph.edges),
        },
        workingGraph: {
          nodes: [
            {
              ...structuredClone(revisionState.workingGraph.nodes[0]),
              position: { x: 640, y: 320 },
            },
            structuredClone(revisionState.workingGraph.nodes[1]),
          ],
          edges: structuredClone(revisionState.workingGraph.edges),
        },
        layoutState: {
          positions: {
            "method:submit-order": { x: 640, y: 320 },
          },
        },
        semanticRevision: 3,
        layoutRevision: 2,
        snapshotRevision: 5,
      }));
    });

    expectNoBridgeCommand("applyGraphEditScript");
  });

  it("does not read semantic metadata when revisions show only a feedback refresh", () => {
    let metadataReads = 0;
    const trackedMetadata = {};
    Object.defineProperty(trackedMetadata, "semantic.hint", {
      enumerable: true,
      get() {
        metadataReads += 1;
        return "draft";
      },
    });

    const trackedBootstrapState = materializeThreeViewDocuments({
      ...structuredClone(bootstrapState),
      visibleGraph: {
        nodes: [
          {
            ...structuredClone(bootstrapState.visibleGraph.nodes[0]),
            position: { x: 120, y: 96 },
            metadata: trackedMetadata as Record<string, string>,
          },
          structuredClone(bootstrapState.visibleGraph.nodes[1]),
        ],
        edges: structuredClone(bootstrapState.visibleGraph.edges),
      },
      semanticRevision: 3,
      layoutRevision: 1,
      snapshotRevision: 4,
    });

    window.linkGraphBootstrap = trackedBootstrapState;
    render(<App />);

    metadataReads = 0;

    act(() => {
      dispatchBootstrapState({
        ...trackedBootstrapState,
        operationFeedback: {
          level: "INFO",
          message: "只刷新反馈，不应再触发语义图签名读取。",
        },
        snapshotRevision: 5,
      });
    });

    expect(metadataReads).toBeLessThanOrEqual(1);
  });

  it("keeps source-navigation probe alive after move updates on large graphs", () => {
    vi.useFakeTimers();
    window.__linkGraphInteractionProbe = true;
    window.linkGraphBootstrap = buildDenseBootstrapState(120);

    render(<App />);

    act(() => {
      vi.advanceTimersByTime(260);
    });
    act(() => {
      vi.advanceTimersByTime(260);
    });
    vi.useRealTimers();

    expectBridgeCommand("requestSourceNavigation", {
      nodeId: "method:dense-0",
    });

    window.__linkGraphInteractionProbe = false;
  }, 15000);

  it("completes source-navigation probe from typed state instead of parsing feedback text", async () => {
    vi.useFakeTimers();
    window.__linkGraphDebugEnabled = true;
    window.__linkGraphTraceBuffer = [];
    window.__linkGraphInteractionProbe = true;
    window.linkGraphBootstrap = buildDenseBootstrapState(120);

    render(<App />);

    act(() => {
      vi.advanceTimersByTime(260);
    });
    act(() => {
      vi.advanceTimersByTime(260);
    });

    act(() => {
      dispatchBootstrapState({
        ...buildDenseBootstrapState(120),
        snapshotRevision: 2,
        operationFeedback: {
          level: "SUCCESS",
          message: "这个文案不应该被 probe 用来判断源码导航完成。",
        },
        sourceNavigationState: {
          nodeId: "method:dense-0",
          phase: "SUCCEEDED",
          result: "OPENED",
          targetPath: "src/main/java/com/example/DenseController.java",
          line: 1,
          column: 1,
        },
      });
    });

    act(() => {
      vi.runAllTimers();
    });
    vi.useRealTimers();

    expect(
      window.__linkGraphTraceBuffer?.some((entry) => entry.includes("probe.app.sourceNavigation.completed")),
    ).toBe(true);
  });
});
