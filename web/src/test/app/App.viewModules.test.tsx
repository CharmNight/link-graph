import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../../app/App";
import { dispatchBootstrapForTest, resetEditorTransportForTest } from "../../app/editorTransport";
import {
  materializeThreeViewDocuments,
  type TestBootstrapState,
  type TestBootstrapStateInput,
} from "../../app/testBootstrapState";
import type { GraphViewPresentation } from "../../app/types";
import { expectBridgeCommand, installBridgeCommandSpy } from "./bridgeTestUtils";

vi.mock("../../app/views/fact/FactGraphView", () => ({
  FactGraphView: ({
    view,
    onAddNode,
    draftCompareProjection,
  }: {
    view: {
      visibleGraph: {
        nodes: Array<{ id: string }>;
      };
    };
    onAddNode: (kind: "METHOD" | "DOC_PAGE", position?: { x: number; y: number }) => void;
    draftCompareProjection?: {
      entryTitle: string;
      nodeStatuses: Record<string, string>;
    } | null;
  }) => (
    <div data-testid="fact-graph-view">
      <span data-testid="fact-node-count">{view.visibleGraph.nodes.length}</span>
      <span data-testid="fact-compare-title">{draftCompareProjection?.entryTitle ?? ""}</span>
      <span data-testid="fact-compare-node-count">{Object.keys(draftCompareProjection?.nodeStatuses ?? {}).length}</span>
      <button type="button" onClick={() => onAddNode("DOC_PAGE", { x: 240, y: 180 })}>
        add-node
      </button>
    </div>
  ),
}));

vi.mock("../../app/views/flowchart/FlowchartView", () => ({
  FlowchartView: ({
    view,
    onAddNode,
    onCreateEdge,
    onInsertNodeIntoEdge,
    onMoveNode,
    onMoveNodes,
    draftCompareProjection,
  }: {
    view: {
      visibleGraph: {
        nodes: Array<{ id: string; title?: string; position?: { x: number; y: number } }>;
        edges: Array<{
          id: string;
          type?: string;
          route?: { sections: Array<unknown> };
          sourceHandle?: string | null;
          targetHandle?: string | null;
        }>;
      };
    };
    onAddNode: (kind: "METHOD" | "DOC_PAGE", position?: { x: number; y: number }) => void;
    onCreateEdge: (
      sourceId: string,
      targetId: string,
      sourceHandle?: string | null,
      targetHandle?: string | null,
    ) => void;
    onInsertNodeIntoEdge?: (edgeId: string, kind: "METHOD" | "DOC_PAGE") => void;
    onMoveNode: (nodeId: string, position: { x: number; y: number }) => void;
    onMoveNodes?: (updates: Array<{ id: string; position: { x: number; y: number } }>) => void;
    draftCompareProjection?: {
      entryTitle: string;
      nodeStatuses: Record<string, string>;
    } | null;
  }) => (
    <div data-testid="flowchart-view">
      <span data-testid="flowchart-node-count">{view.visibleGraph.nodes.length}</span>
      <span data-testid="flowchart-node-titles">
        {view.visibleGraph.nodes.map((node) => `${node.id}:${node.title ?? ""}`).join("|")}
      </span>
      <span data-testid="flowchart-compare-title">{draftCompareProjection?.entryTitle ?? ""}</span>
      <span data-testid="flowchart-compare-node-count">{Object.keys(draftCompareProjection?.nodeStatuses ?? {}).length}</span>
      <span data-testid="flowchart-node-positions">
        {view.visibleGraph.nodes
          .map((node) => `${node.id}:${node.position?.x ?? ""}:${node.position?.y ?? ""}`)
          .join("|")}
      </span>
      <span data-testid="flowchart-edge-routes">
        {view.visibleGraph.edges
          .map((edge) => `${edge.id}:${edge.route ? "route" : "no-route"}`)
          .join("|")}
      </span>
      <span data-testid="flowchart-edge-types">
        {view.visibleGraph.edges
          .map((edge) => `${edge.id}:${edge.type ?? ""}`)
          .join("|")}
      </span>
      <span data-testid="flowchart-edge-handles">
        {view.visibleGraph.edges
          .map((edge) => `${edge.id}:${edge.sourceHandle ?? ""}:${edge.targetHandle ?? ""}`)
          .join("|")}
      </span>
      <button type="button" onClick={() => onAddNode("DOC_PAGE", { x: 260, y: 220 })}>
        add-flowchart-node
      </button>
      <button type="button" onClick={() => onCreateEdge("flow:entry", "flow:decision")}>
        create-flowchart-edge
      </button>
      <button
        type="button"
        onClick={() => onCreateEdge("flow:entry", "flow:decision", "source-bottom", "target-top")}
      >
        create-flowchart-handled-edge
      </button>
      <button type="button" onClick={() => onInsertNodeIntoEdge?.("edge:entry->decision", "METHOD")}>
        insert-flowchart-node
      </button>
      <button type="button" onClick={() => onMoveNode("flow:entry", { x: 640, y: 320 })}>
        move-flowchart-node
      </button>
      <button
        type="button"
        onClick={() =>
          onMoveNodes?.([
            { id: "flow:entry", position: { x: 700, y: 360 } },
            { id: "flow:decision", position: { x: 960, y: 360 } },
          ])}
      >
        move-flowchart-group
      </button>
    </div>
  ),
}));

vi.mock("../../app/views/resource/ResourceRelationView", () => ({
  ResourceRelationView: ({
    view,
    onAddNode,
    onMoveNode,
    draftCompareProjection,
  }: {
    view: {
      visibleGraph: {
        nodes: Array<{ id: string; position?: { x: number; y: number } }>;
      };
    };
    onAddNode: (kind: "METHOD" | "DOC_PAGE", position?: { x: number; y: number }) => void;
    onMoveNode: (nodeId: string, position: { x: number; y: number }) => void;
    draftCompareProjection?: {
      entryTitle: string;
      nodeStatuses: Record<string, string>;
    } | null;
  }) => (
    <div data-testid="resource-relation-view">
      <span data-testid="resource-node-count">{view.visibleGraph.nodes.length}</span>
      <span data-testid="resource-compare-title">{draftCompareProjection?.entryTitle ?? ""}</span>
      <span data-testid="resource-compare-node-count">{Object.keys(draftCompareProjection?.nodeStatuses ?? {}).length}</span>
      <span data-testid="resource-node-positions">
        {view.visibleGraph.nodes
          .map((node) => `${node.id}:${node.position?.x ?? ""}:${node.position?.y ?? ""}`)
          .join("|")}
      </span>
      <button type="button" onClick={() => onAddNode("DOC_PAGE", { x: 320, y: 240 })}>
        add-resource-node
      </button>
      <button type="button" onClick={() => onMoveNode("resource:sql", { x: 880, y: 280 })}>
        move-resource-node
      </button>
    </div>
  ),
}));

vi.mock("../../app/views/class-diagram/ClassDiagramView", () => ({
  ClassDiagramView: ({
    view,
    focusNodeRequest,
    onOpenQa,
    onRequestBeautification,
  }: {
    view: {
      visibleGraph: {
        nodes: Array<{ id: string }>;
      };
    };
    focusNodeRequest?: { nodeId: string; nonce: number } | null;
    onOpenQa: (selectedNodeId?: string) => void;
    onRequestBeautification: (selectedNodeId?: string) => void;
  }) => (
    <div data-testid="class-diagram-view">
      <span data-testid="class-diagram-node-count">{view.visibleGraph.nodes.length}</span>
      <span data-testid="class-diagram-focus-request">{focusNodeRequest?.nodeId ?? ""}</span>
      <button type="button" onClick={() => onOpenQa()}>
        class-diagram-open-qa
      </button>
      <button type="button" onClick={() => onRequestBeautification(view.visibleGraph.nodes[0]?.id)}>
        class-diagram-explain-node
      </button>
    </div>
  ),
}));

vi.mock("../../app/views/architecture/ArchitectureGraphView", () => ({
  ArchitectureGraphView: ({
    view,
  }: {
    view: {
      visibleGraph: {
        nodes: Array<{ id: string }>;
      };
    };
  }) => (
    <div data-testid="architecture-graph-view">
      <span data-testid="architecture-graph-node-count">{view.visibleGraph.nodes.length}</span>
    </div>
  ),
}));

const EMPTY_PRESENTATION: GraphViewPresentation = {
  target: {
    nodeId: null,
    title: "",
    subtitle: "",
    location: null,
  },
  lanes: [],
  hiddenBuckets: [],
  controls: {
    primaryScope: "",
    availableScopes: [],
    searchable: true,
    expandable: true,
  },
};

function bootstrapState(
  analysisDisplayMode: TestBootstrapState["analysisDisplayMode"],
  overrides: Partial<TestBootstrapStateInput> = {},
): TestBootstrapState {
  const baseInput: TestBootstrapStateInput = {
    analysisDisplayMode,
    visibleGraph: {
      nodes: [],
      edges: [],
    },
    workingGraph: {
      nodes: [],
      edges: [],
    },
    referenceFactGraph: {
      nodes: [],
      edges: [],
    },
    designBaselineGraph: null,
    mermaidIssues: [],
    diffItems: [],
    syncPreviewItems: [],
    factGraphView: {
      visibleGraph: { nodes: [], edges: [] },
      fullGraph: { nodes: [], edges: [] },
      anchorNodeId: null,
      summary: { anchorTitle: null, visibleNodeCount: 0, fullNodeCount: 0 },
      presentation: EMPTY_PRESENTATION,
    },
    flowchartView: {
      visibleGraph: { nodes: [], edges: [] },
      fullGraph: { nodes: [], edges: [] },
      anchorNodeId: null,
      summary: { nodeCount: 0, branchCount: 0, exceptionPathCount: 0 },
    },
    resourceRelationView: {
      visibleGraph: { nodes: [], edges: [] },
      fullGraph: { nodes: [], edges: [] },
      anchorNodeId: null,
      summary: { visibleNodeCount: 0, relationCount: 0, resourceCount: 0, fallbackReason: "NO_RESOURCE_UNITS", laneCounts: {} },
    },
  };
  const materializedState = materializeThreeViewDocuments({
    ...structuredClone(baseInput),
    ...overrides,
  });
  return {
    ...materializedState,
    factGraphView: Object.prototype.hasOwnProperty.call(overrides, "factGraphView")
      ? overrides.factGraphView
      : materializedState.factGraphView,
    flowchartView: Object.prototype.hasOwnProperty.call(overrides, "flowchartView")
      ? overrides.flowchartView
      : materializedState.flowchartView,
    resourceRelationView: Object.prototype.hasOwnProperty.call(overrides, "resourceRelationView")
      ? overrides.resourceRelationView
      : materializedState.resourceRelationView,
  };
}

describe("App view modules", () => {
  afterEach(() => {
    window.linkGraphBootstrap = undefined;
    window.linkGraphBridge = undefined;
    resetEditorTransportForTest();
  });

  it("renders the flowchart view module when the current mode is FLOWCHART", () => {
    window.linkGraphBootstrap = bootstrapState("FLOWCHART");

    render(<App />);

    expect(screen.getByTestId("flowchart-view")).toBeInTheDocument();
  });

  it("renders the resource relation view module when the current mode is RESOURCE_RELATION_VIEW", () => {
    window.linkGraphBootstrap = bootstrapState("RESOURCE_RELATION_VIEW");

    render(<App />);

    expect(screen.getByTestId("resource-relation-view")).toBeInTheDocument();
  });

  it("keeps the final assistant workbench available when opening class diagrams", () => {
    window.linkGraphBootstrap = bootstrapState("CLASS_DIAGRAM", {
      visibleGraph: {
        nodes: [
          {
            id: "class:writer",
            type: "CLASS",
            title: "ConfirmedDraftArtifactWriter",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        ],
        edges: [],
      },
    });

    render(<App />);

    expect(screen.getByTestId("class-diagram-view")).toBeInTheDocument();
    expect(screen.getByTestId("hybrid-workbench-layout")).toHaveClass("outline-collapsed");
    expect(screen.getByTestId("hybrid-workbench-layout")).not.toHaveClass("workbench-collapsed");
    expect(screen.getByRole("complementary", { name: "AI 类图工作台" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "介绍这个类" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "解释关系" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "追问类图" })).toBeInTheDocument();
    // 主操作固定「下一步」，类图模式与链路模式的区分由副标题承载
    expect(within(screen.getByRole("banner", { name: "链路任务栏" })).getByRole("button", { name: "下一步" })).toBeInTheDocument();
    expect(within(screen.getByRole("banner", { name: "链路任务栏" })).getByText(/解释类关系/)).toBeInTheDocument();
    // P1: 五段状态条已删除，阶段 badge 精简为进度指示「X/5 · 短名」
    expect(screen.queryByRole("group", { name: "AI 工作状态" })).not.toBeInTheDocument();
    expect(screen.queryByText("理解类图")).not.toBeInTheDocument();
    expect(screen.queryByText("类图问答")).not.toBeInTheDocument();
    expect(screen.queryByText("理解代码")).not.toBeInTheDocument();
    expect(screen.queryByText("代码问答")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(/^\d+\/5 · /);
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  });

  it("submits class descriptions separately from class relationship explanations", async () => {
    const user = userEvent.setup();
    installBridgeCommandSpy();
    window.linkGraphBootstrap = bootstrapState("CLASS_DIAGRAM", {
      visibleGraph: {
        nodes: [
          {
            id: "class:quota-manager",
            type: "CLASS",
            title: "ClientRequestQuotaManager",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        ],
        edges: [],
      },
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "介绍这个类" }));

    const composer = screen.getByRole("textbox", { name: "AI 类图工作台输入框" });
    expect(composer).toHaveValue(
      "请介绍类图节点“ClientRequestQuotaManager”：说明这个类的职责、核心字段/构造依赖、对外协作关系、典型使用场景，以及建议继续下钻的位置。",
    );

    await user.click(screen.getByRole("button", { name: "发送到 AI 类图工作台" }));

    expectBridgeCommand("requestAssistantTask", expect.objectContaining({
      actionId: "DESCRIBE_CLASS",
      sceneId: "WORKSPACE_CLASS_DIAGRAM",
      intent: "DESCRIBE_CLASS",
      prompt: "请介绍类图节点“ClientRequestQuotaManager”：说明这个类的职责、核心字段/构造依赖、对外协作关系、典型使用场景，以及建议继续下钻的位置。",
      selectedNodeIds: ["class:quota-manager"],
      selectedDiffItemIds: [],
      target: { kind: "NewTask" },
      explanationGranularity: "BUSINESS",
    }));

    await user.click(screen.getByRole("button", { name: "解释关系" }));
    await user.click(screen.getByRole("button", { name: "发送到 AI 类图工作台" }));

    expectBridgeCommand("requestAssistantTask", expect.objectContaining({
      actionId: "EXPLAIN_STRUCTURE",
      sceneId: "WORKSPACE_CLASS_DIAGRAM",
      intent: "EXPLAIN_CODE",
      prompt: "请解释类图节点“ClientRequestQuotaManager”的字段关联、构造参数、返回值、参数和类型依赖关系；只解释图上的结构关系，不要介绍类职责，也不要按方法调用顺序讲解。",
      selectedNodeIds: ["class:quota-manager"],
      selectedDiffItemIds: [],
      target: { kind: "NewTask" },
      explanationGranularity: "BUSINESS",
    }));
  });

  it("primes class diagram qa with structural relation wording", async () => {
    const user = userEvent.setup();
    installBridgeCommandSpy();
    window.linkGraphBootstrap = bootstrapState("CLASS_DIAGRAM", {
      visibleGraph: {
        nodes: [
          {
            id: "class:quota-manager",
            type: "CLASS",
            title: "ClientRequestQuotaManager",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        ],
        edges: [],
      },
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "class-diagram-open-qa" }));

    const composer = screen.getByRole("textbox", { name: "AI 类图工作台输入框" });
    expect(composer).toHaveValue(
      "请围绕当前类图的类、字段、构造参数、返回值和类型依赖关系进行问答，重点确认结构关系是否完整、是否存在遗漏的字段关联或类型依赖。",
    );
    expect(composer).not.toHaveValue(expect.stringContaining("业务链路"));
    expect(composer).not.toHaveValue(expect.stringContaining("异常分支"));

    await user.click(screen.getByRole("button", { name: "发送到 AI 类图工作台" }));

    expectBridgeCommand("requestAssistantTask", expect.objectContaining({
      actionId: "ASK_CONTEXT",
      sceneId: "WORKSPACE_CLASS_DIAGRAM",
      intent: "ASK_CODE",
      prompt: "请围绕当前类图的类、字段、构造参数、返回值和类型依赖关系进行问答，重点确认结构关系是否完整、是否存在遗漏的字段关联或类型依赖。",
    }));
    expect(screen.getByText("已提交 AI 类图工作台请求。")).toBeInTheDocument();
    expect(screen.queryByText("已提交 AI 代码工作台请求。")).not.toBeInTheDocument();
  });

  it("submits class diagram node explanations without focusing the viewport", async () => {
    const user = userEvent.setup();
    installBridgeCommandSpy();
    window.linkGraphBootstrap = bootstrapState("CLASS_DIAGRAM", {
      visibleGraph: {
        nodes: [
          {
            id: "class:quota-manager",
            type: "CLASS",
            title: "ClientRequestQuotaManager",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        ],
        edges: [],
      },
    });

    render(<App />);

    expect(screen.getByTestId("class-diagram-focus-request")).toHaveTextContent("");

    await user.click(screen.getByRole("button", { name: "class-diagram-explain-node" }));

    expectBridgeCommand("requestAssistantTask", expect.objectContaining({
      actionId: "EXPLAIN_STRUCTURE",
      sceneId: "WORKSPACE_CLASS_DIAGRAM",
      intent: "EXPLAIN_CODE",
      prompt: "请解释类图节点“ClientRequestQuotaManager”的字段关联、构造参数、返回值、参数和类型依赖关系；只解释图上的结构关系，不要介绍类职责，也不要按方法调用顺序讲解。",
      selectedNodeIds: ["class:quota-manager"],
      selectedDiffItemIds: [],
      target: { kind: "NewTask" },
      explanationGranularity: "BUSINESS",
    }));
    expect(screen.getByText("已提交 AI 类图工作台请求。")).toBeInTheDocument();
    expect(screen.getByTestId("class-diagram-focus-request")).toHaveTextContent("");
  });

  it("resets stale assistant action and selected nodes when switching from class diagrams to flowcharts", async () => {
    const user = userEvent.setup();
    installBridgeCommandSpy();
    const classPrompt = "请介绍类图节点“ClientRequestQuotaManager”：说明这个类的职责、核心字段/构造依赖、对外协作关系、典型使用场景，以及建议继续下钻的位置。";
    const flowGraph = {
      nodes: [
        {
          id: "flow:entry",
          type: "METHOD" as const,
          title: "SubmitOrderFlow",
          inputs: [],
          outputs: [],
          certainty: "PROVEN" as const,
          bindingStatus: "BOUND" as const,
        },
      ],
      edges: [],
    };
    window.linkGraphBootstrap = bootstrapState("CLASS_DIAGRAM", {
      selectedNodeId: "class:quota-manager",
      visibleGraph: {
        nodes: [
          {
            id: "class:quota-manager",
            type: "CLASS",
            title: "ClientRequestQuotaManager",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        ],
        edges: [],
      },
      flowchartView: {
        visibleGraph: flowGraph,
        fullGraph: flowGraph,
        anchorNodeId: "flow:entry",
        summary: { nodeCount: 1, branchCount: 0, exceptionPathCount: 0 },
      },
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "介绍这个类" }));
    expect(screen.getByRole("textbox", { name: "AI 类图工作台输入框" })).toHaveValue(classPrompt);

    const flowState = bootstrapState("FLOWCHART", {
      selectedNodeId: "flow:entry",
      visibleGraph: flowGraph,
      workingGraph: flowGraph,
      referenceFactGraph: flowGraph,
      flowchartView: {
        visibleGraph: flowGraph,
        fullGraph: flowGraph,
        anchorNodeId: "flow:entry",
        summary: { nodeCount: 1, branchCount: 0, exceptionPathCount: 0 },
      },
      assistantSessionState: {
        sessionId: "assistant-session-test",
        activeIntent: "DESCRIBE_CLASS",
        activeActionId: "DESCRIBE_CLASS",
        contextLocked: false,
        context: {
          selectedNodeIds: ["class:quota-manager"],
          selectedDiffItemIds: [],
          analysisDisplayMode: "CLASS_DIAGRAM",
          currentSceneId: "WORKSPACE_CLASS_DIAGRAM",
          selectedMethodSignature: null,
          scopeLabel: "ClientRequestQuotaManager",
        },
        composer: {
          draft: classPrompt,
          target: { kind: "NewTask" },
          draftSource: "AUTO",
          actionId: "DESCRIBE_CLASS",
          sceneId: "WORKSPACE_CLASS_DIAGRAM",
        },
        turns: [],
      },
    });
    dispatchBootstrapForTest({
      sessionId: "assistant-session-test",
      revision: 2,
      state: flowState,
    });

    await waitFor(() => {
      expect(screen.getByRole("complementary", { name: "AI 代码工作台" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "介绍这个类" })).not.toBeInTheDocument();
    });

    expect(screen.getByRole("button", { name: "解释当前节点" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("textbox", { name: "AI 工作台输入框" })).toHaveValue(
      "请讲解节点“SubmitOrderFlow”在当前代码链路中的作用、上下游关系和关键分支。",
    );

    await user.click(screen.getByRole("button", { name: "发送到 AI 代码工作台" }));

    expectBridgeCommand("requestAssistantTask", expect.objectContaining({
      actionId: "EXPLAIN_FLOW",
      sceneId: "WORKSPACE_FLOWCHART",
      intent: "EXPLAIN_CODE",
      prompt: "请讲解节点“SubmitOrderFlow”在当前代码链路中的作用、上下游关系和关键分支。",
      selectedNodeIds: ["flow:entry"],
    }));
  });

  it("keeps the final assistant workbench available when opening the project structure graph", () => {
    const state = bootstrapState("ARCHITECTURE_GRAPH", {
      visibleGraph: {
        nodes: [
          {
            id: "component:entry",
            type: "COMPONENT",
            title: "entry",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        ],
        edges: [],
      },
    });
    window.linkGraphBootstrap = {
      ...state,
      architectureGraphView: {
        ...state.architectureGraphView!,
        anchorNodeId: "component:entry",
        summary: {
          ...state.architectureGraphView!.summary,
          indexed: {
            view: "ARCHITECTURE",
            anchorNodeId: "component:entry",
            anchorTitle: "entry",
            scopeKind: "PROJECT",
            scopeLabel: "整个项目",
            relationKinds: [],
            depth: 2,
            projectNodeCount: 1,
            projectClassCount: 1,
            externalNodeCount: 0,
            jdkNodeCount: 0,
            scopedNodeCount: 1,
            visibleNodeCount: 1,
            hiddenNodeCount: 0,
            hiddenEdgeCount: 0,
            candidateNodeCount: 1,
            candidateEdgeCount: 0,
            truncated: false,
            completeness: "COMPLETE",
            cacheState: "HIT",
          },
        },
      },
    };

    render(<App />);

    expect(screen.getByTestId("architecture-graph-view")).toBeInTheDocument();
    expect(screen.getByTestId("hybrid-workbench-layout")).toHaveClass("outline-collapsed");
    expect(screen.getByTestId("hybrid-workbench-layout")).not.toHaveClass("workbench-collapsed");
    expect(screen.getByRole("complementary", { name: "AI 代码工作台" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "追问代码" })).toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  });

  it("keeps the fact view document in sync after local fact-graph edits instead of relying on a render-time graph override", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FACT_GRAPH");

    render(<App />);

    expect(screen.getByTestId("fact-node-count")).toHaveTextContent("0");

    await user.click(screen.getByRole("button", { name: "add-node" }));

    expect(screen.getByTestId("fact-node-count")).toHaveTextContent("1");
  });

  it("passes draft compare projection into the active fact-graph view module", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FACT_GRAPH", {
        factGraphView: {
          visibleGraph: {
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
          fullGraph: {
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
          anchorNodeId: "method:submit-order",
          summary: { anchorTitle: "OrderController.submit", visibleNodeCount: 1, fullNodeCount: 1 },
          presentation: EMPTY_PRESENTATION,
        },
        workingGraph: {
          nodes: [
            {
              id: "method:submit-order",
              type: "METHOD",
              title: "OrderController.submit",
              inputs: [],
              outputs: [],
              doc: "增加失败补偿处理说明。",
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
          ],
          edges: [],
        },
        referenceFactGraph: {
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
        draftWorkbenchState: {
          draftChanges: [
            {
              entryId: "draft-change-compensate",
              kind: "CHANGE",
              title: "补充失败补偿说明",
              sourceChangeId: "change-compensate",
              targetStepIds: ["step-submit-order"],
              targetNodeIds: ["method:submit-order"],
              beforeState: "当前没有失败补偿说明",
              afterState: "补充失败补偿逻辑说明",
              reason: "当前链路缺少失败补偿语义。",
              impactSummary: "影响订单提交失败后的处理理解。",
              claimType: "CODE_FACT",
              evidence: [],
            },
          ],
          draftNotes: [],
        },
      });

    render(<App />);

    expect(screen.getByTestId("fact-compare-node-count")).toHaveTextContent("0");

    await user.click(screen.getByRole("button", { name: "查看流程变化" }));

    expect(screen.getByTestId("fact-compare-title")).toHaveTextContent("补充失败补偿说明");
    expect(screen.getByTestId("fact-compare-node-count")).toHaveTextContent("1");
  });

  it("passes the after-state flowchart node title into the active flowchart view module instead of leaving the stale visible title in place", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART", {
      flowchartView: {
        visibleGraph: {
          nodes: [
            {
              id: "method:file-download",
              type: "METHOD",
              title: "CommonController.fileDownload",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "flowchart.kind": "ENTRY",
              },
            },
            {
              id: "scope:file-download-if",
              type: "FLOW_SCOPE",
              title: "if (delete)",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "flowchart.kind": "DECISION",
                "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
              },
            },
          ],
          edges: [],
        },
        fullGraph: {
          nodes: [
            {
              id: "method:file-download",
              type: "METHOD",
              title: "CommonController.fileDownload",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "flowchart.kind": "ENTRY",
              },
            },
            {
              id: "scope:file-download-if",
              type: "FLOW_SCOPE",
              title: "if (delete)",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "flowchart.kind": "DECISION",
                "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
              },
            },
          ],
          edges: [],
        },
        anchorNodeId: "method:file-download",
        summary: { nodeCount: 2, branchCount: 1, exceptionPathCount: 0 },
      },
      workingGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "scope:file-download-if",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "flowchart.kind": "DECISION",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
            },
          },
        ],
        edges: [],
      },
      draftWorkbenchState: {
        draftChanges: [
          {
            entryId: "draft-change-delete-guard",
            kind: "CHANGE",
            title: "将删除条件收紧为显式 true 判断",
            sourceChangeId: "change-delete-guard",
            targetStepIds: [],
            targetNodeIds: ["scope:file-download-if"],
            beforeState: "if (delete)",
            afterState: "if (delete == true)",
            reason: "需要显式判断布尔值。",
            impactSummary: "影响删除分支。",
            claimType: "CODE_FACT",
            evidence: [
              {
                id: "finding-delete-guard",
                claim: "当前源码里直接能看到删除判断条件。",
                evidenceLevel: "DIRECT_SOURCE",
                references: [{ nodeId: "scope:file-download-if" }],
              },
            ],
            graphPatch: {
              summary: "调整删除判断",
              operations: [
                {
                  id: "patch-op-delete-guard",
                  action: "UPDATE_NODE",
                  elementKind: "NODE",
                  elementId: "scope:file-download-if",
                  node: {
                    id: "scope:file-download-if",
                    type: "FLOW_SCOPE",
                    title: "if (delete == true)",
                    inputs: [],
                    outputs: [],
                    certainty: "LLM_SUGGESTED",
                    bindingStatus: "BOUND",
                    metadata: {
                      "flowchart.kind": "DECISION",
                    },
                  },
                },
              ],
              addedNodeIds: [],
              removedNodeIds: [],
              addedEdgeIds: [],
              removedEdgeIds: [],
            },
          },
        ],
        draftNotes: [],
      },
      selectedNodeId: "scope:file-download-if",
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "改动列表" }));

    expect(screen.getByTestId("flowchart-node-titles")).toHaveTextContent("scope:file-download-if:if (delete == true)");
  });

  it("falls back to the draft after-state title when flowchart patch normalization has not supplied a node payload", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART", {
      flowchartView: {
        visibleGraph: {
          nodes: [
            {
              id: "method:file-download",
              type: "METHOD",
              title: "CommonController.fileDownload",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "flowchart.kind": "ENTRY",
              },
            },
            {
              id: "scope:file-download-if",
              type: "FLOW_SCOPE",
              title: "if (delete)",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "flowchart.kind": "DECISION",
                "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
              },
            },
          ],
          edges: [],
        },
        fullGraph: {
          nodes: [
            {
              id: "method:file-download",
              type: "METHOD",
              title: "CommonController.fileDownload",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "flowchart.kind": "ENTRY",
              },
            },
            {
              id: "scope:file-download-if",
              type: "FLOW_SCOPE",
              title: "if (delete)",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "flowchart.kind": "DECISION",
                "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
              },
            },
          ],
          edges: [],
        },
        anchorNodeId: "method:file-download",
        summary: { nodeCount: 2, branchCount: 1, exceptionPathCount: 0 },
      },
      workingGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "scope:file-download-if",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "flowchart.kind": "DECISION",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
            },
          },
        ],
        edges: [],
      },
      draftWorkbenchState: {
        draftChanges: [
          {
            entryId: "draft-change-delete-guard",
            kind: "CHANGE",
            title: "将删除条件从 if (delete) 改为仅在明确为 true 时删除",
            sourceChangeId: "change-delete-guard",
            targetStepIds: [],
            targetNodeIds: ["scope:file-download-if"],
            beforeState: "if (delete)",
            afterState: "将条件更新为显式 true 判断语义，例如 Boolean.TRUE.equals(delete)。",
            reason: "需要规避 null 边界风险。",
            impactSummary: "只影响删除分支判断方式。",
            claimType: "CODE_FACT",
            evidence: [
              {
                id: "finding-delete-guard",
                claim: "当前源码里直接能看到删除判断条件。",
                evidenceLevel: "DIRECT_SOURCE",
                references: [{ nodeId: "method:file-download" }, { nodeId: "scope:file-download-if" }],
              },
            ],
            graphPatch: {
              summary: "调整删除判断",
              operations: [
                {
                  id: "patch-op-delete-guard",
                  action: "UPDATE_NODE",
                  elementKind: "NODE",
                  elementId: "scope:file-download-if",
                },
              ],
              addedNodeIds: [],
              removedNodeIds: [],
              addedEdgeIds: [],
              removedEdgeIds: [],
            },
          },
        ],
        draftNotes: [],
      },
      selectedNodeId: "scope:file-download-if",
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "改动列表" }));

    expect(screen.getByTestId("flowchart-node-titles")).toHaveTextContent("method:file-download:CommonController.fileDownload");
    expect(screen.getByTestId("flowchart-node-titles")).toHaveTextContent(
      "scope:file-download-if:将条件更新为显式 true 判断语义，例如 Boolean.TRUE.equals(delete)。",
    );
  });

  it.each([
    {
      mode: "FLOWCHART" as const,
      countTestId: "flowchart-compare-node-count",
      titleTestId: "flowchart-compare-title",
      state: bootstrapState("FLOWCHART", {
        flowchartView: {
          visibleGraph: {
            nodes: [
              {
                id: "method:submit-order",
                type: "METHOD",
                title: "OrderController.submit",
                inputs: [],
                outputs: [],
                certainty: "PROVEN",
                bindingStatus: "BOUND",
                metadata: {
                  "flowchart.kind": "ENTRY",
                },
              },
            ],
            edges: [],
          },
          fullGraph: {
            nodes: [
              {
                id: "method:submit-order",
                type: "METHOD",
                title: "OrderController.submit",
                inputs: [],
                outputs: [],
                doc: "增加失败补偿处理说明。",
                certainty: "PROVEN",
                bindingStatus: "BOUND",
              },
            ],
            edges: [],
          },
          anchorNodeId: "method:submit-order",
          summary: { nodeCount: 1, branchCount: 0, exceptionPathCount: 0 },
        },
        referenceWorkingGraph: {
          nodes: [
            {
              id: "method:submit-order",
              type: "METHOD",
              title: "OrderController.submit",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "flowchart.kind": "ENTRY",
              },
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
              inputs: [],
              outputs: [],
              doc: "增加失败补偿处理说明。",
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
          ],
          edges: [],
        },
        referenceFactGraph: {
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
        draftWorkbenchState: {
          draftChanges: [
            {
              entryId: "draft-change-compensate",
              kind: "CHANGE",
              title: "补充失败补偿说明",
              sourceChangeId: "change-compensate",
              targetStepIds: ["step-submit-order"],
              targetNodeIds: ["method:submit-order"],
              beforeState: "当前没有失败补偿说明",
              afterState: "补充失败补偿逻辑说明",
              reason: "当前链路缺少失败补偿语义。",
              impactSummary: "影响订单提交失败后的处理理解。",
              claimType: "CODE_FACT",
              evidence: [],
              graphPatch: {
                summary: "补充失败补偿说明",
                operations: [
                  {
                    id: "patch-op-submit-order-doc",
                    action: "UPDATE_NODE",
                    elementKind: "NODE",
                    elementId: "method:submit-order",
                    node: {
                      id: "method:submit-order",
                      type: "METHOD",
                      title: "OrderController.submit",
                      inputs: [],
                      outputs: [],
                      doc: "增加失败补偿处理说明。",
                      certainty: "PROVEN",
                      bindingStatus: "BOUND",
                    },
                  },
                ],
                addedNodeIds: [],
                removedNodeIds: [],
                addedEdgeIds: [],
                removedEdgeIds: [],
              },
            },
          ],
          draftNotes: [],
        },
      }),
    },
    {
      mode: "RESOURCE_RELATION_VIEW" as const,
      tabName: "resource-relation-view",
      countTestId: "resource-compare-node-count",
      titleTestId: "resource-compare-title",
      state: bootstrapState("RESOURCE_RELATION_VIEW", {
        resourceRelationView: {
          visibleGraph: {
            nodes: [
              {
                id: "method:submit-order",
                type: "METHOD",
                title: "OrderController.submit",
                inputs: [],
                outputs: [],
                certainty: "PROVEN",
                bindingStatus: "BOUND",
                metadata: {
                  "resource.lane": "CODE",
                },
              },
            ],
            edges: [],
          },
          fullGraph: {
            nodes: [
              {
                id: "method:submit-order",
                type: "METHOD",
                title: "OrderController.submit",
                inputs: [],
                outputs: [],
                doc: "增加失败补偿处理说明。",
                certainty: "PROVEN",
                bindingStatus: "BOUND",
              },
            ],
            edges: [],
          },
          anchorNodeId: "method:submit-order",
          summary: { visibleNodeCount: 1, relationCount: 0, resourceCount: 1, fallbackReason: "NO_BINDING_RELATIONS", laneCounts: { CODE: 1 } },
        },
        referenceWorkingGraph: {
          nodes: [
            {
              id: "method:submit-order",
              type: "METHOD",
              title: "OrderController.submit",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              metadata: {
                "resource.lane": "CODE",
              },
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
              inputs: [],
              outputs: [],
              doc: "增加失败补偿处理说明。",
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
          ],
          edges: [],
        },
        referenceFactGraph: {
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
        draftWorkbenchState: {
          draftChanges: [
            {
              entryId: "draft-change-compensate",
              kind: "CHANGE",
              title: "补充失败补偿说明",
              sourceChangeId: "change-compensate",
              targetStepIds: ["step-submit-order"],
              targetNodeIds: ["method:submit-order"],
              beforeState: "当前没有失败补偿说明",
              afterState: "补充失败补偿逻辑说明",
              reason: "当前链路缺少失败补偿语义。",
              impactSummary: "影响订单提交失败后的处理理解。",
              claimType: "CODE_FACT",
              evidence: [],
              graphPatch: {
                summary: "补充失败补偿说明",
                operations: [
                  {
                    id: "patch-op-submit-order-doc",
                    action: "UPDATE_NODE",
                    elementKind: "NODE",
                    elementId: "method:submit-order",
                    node: {
                      id: "method:submit-order",
                      type: "METHOD",
                      title: "OrderController.submit",
                      inputs: [],
                      outputs: [],
                      doc: "增加失败补偿处理说明。",
                      certainty: "PROVEN",
                      bindingStatus: "BOUND",
                      metadata: {
                        "resource.lane": "CODE",
                      },
                    },
                  },
                ],
                addedNodeIds: [],
                removedNodeIds: [],
                addedEdgeIds: [],
                removedEdgeIds: [],
              },
            },
          ],
          draftNotes: [],
        },
      }),
    },
  ])("passes a draft compare projection into the active $mode view module when the selected draft change has a real graph diff", async ({
    state,
    countTestId,
    titleTestId,
  }) => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = state;

    render(<App />);

    expect(screen.getByTestId(countTestId)).toHaveTextContent("0");

    await user.click(screen.getByRole("button", { name: "查看流程变化" }));

    expect(screen.getByTestId(titleTestId)).toHaveTextContent("补充失败补偿说明");
    expect(screen.getByTestId(countTestId)).toHaveTextContent("1");
  });

  it("does not pass a compare projection into the active view when the selected draft change has no real graph diff", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FACT_GRAPH", {
      factGraphView: {
        visibleGraph: {
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
        fullGraph: {
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
        anchorNodeId: "method:submit-order",
        summary: { anchorTitle: "OrderController.submit", visibleNodeCount: 1, fullNodeCount: 1 },
        presentation: EMPTY_PRESENTATION,
      },
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
      referenceFactGraph: {
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
      draftWorkbenchState: {
        draftChanges: [
          {
            entryId: "draft-change-compensate",
            kind: "CHANGE",
            title: "补充失败补偿说明",
            sourceChangeId: "change-compensate",
            targetStepIds: ["step-submit-order"],
            targetNodeIds: ["method:submit-order"],
            beforeState: "当前没有失败补偿说明",
            afterState: "补充失败补偿逻辑说明",
            reason: "当前链路缺少失败补偿语义。",
            impactSummary: "影响订单提交失败后的处理理解。",
            claimType: "CODE_FACT",
            evidence: [],
          },
        ],
        draftNotes: [],
      },
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "查看流程变化" }));

    expect(screen.getByTestId("fact-compare-title")).toBeEmptyDOMElement();
    expect(screen.getByTestId("fact-compare-node-count")).toHaveTextContent("0");
  });

  it("keeps the flowchart view document in sync after local node drags", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART", {
      flowchartView: {
        visibleGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              position: { x: 120, y: 96 },
              sourceTag: "FACT",
            },
          ],
          edges: [],
        },
        fullGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              position: { x: 120, y: 96 },
              sourceTag: "FACT",
            },
          ],
          edges: [],
        },
        anchorNodeId: "flow:entry",
        summary: { nodeCount: 1, branchCount: 0, exceptionPathCount: 0 },
      },
    });

    render(<App />);

    expect(screen.getByTestId("flowchart-node-positions")).toHaveTextContent("flow:entry:120:96");

    await user.click(screen.getByRole("button", { name: "move-flowchart-node" }));

    expect(screen.getByTestId("flowchart-node-positions")).toHaveTextContent("flow:entry:640:320");
  });

  it("keeps the flowchart view document in sync after local node creation", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART");

    render(<App />);

    expect(screen.getByTestId("flowchart-node-count")).toHaveTextContent("0");

    await user.click(screen.getByRole("button", { name: "add-flowchart-node" }));

    expect(screen.getByTestId("flowchart-node-count")).toHaveTextContent("1");
  });

  it("creates control-flow edges inside the flowchart view instead of reusing fact-graph call semantics", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART", {
      flowchartView: {
        visibleGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
            {
              id: "flow:decision",
              type: "FLOW_ACTION",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
          ],
          edges: [],
        },
        fullGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
            {
              id: "flow:decision",
              type: "FLOW_ACTION",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
          ],
          edges: [],
        },
        anchorNodeId: "flow:entry",
        summary: { nodeCount: 2, branchCount: 0, exceptionPathCount: 0 },
      },
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "create-flowchart-edge" }));

    expect(screen.getByTestId("flowchart-edge-types")).toHaveTextContent(
      "design-link:flow:entry->flow:decision:CONTROL_FLOW",
    );
  });

  it("keeps manually created flowchart edge handle intent inside the flowchart view document", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART", {
      flowchartView: {
        visibleGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
            {
              id: "flow:decision",
              type: "FLOW_ACTION",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
          ],
          edges: [],
        },
        fullGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
            {
              id: "flow:decision",
              type: "FLOW_ACTION",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
            },
          ],
          edges: [],
        },
        anchorNodeId: "flow:entry",
        summary: { nodeCount: 2, branchCount: 0, exceptionPathCount: 0 },
      },
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "create-flowchart-handled-edge" }));

    expect(screen.getByTestId("flowchart-edge-handles")).toHaveTextContent(
      "design-link:flow:entry->flow:decision:source-bottom:target-top",
    );
  });

  it("inserts a node into an existing flowchart edge by replacing the old edge with two control-flow edges", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART", {
      flowchartView: {
        visibleGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              position: { x: 120, y: 96 },
            },
            {
              id: "flow:decision",
              type: "FLOW_ACTION",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              position: { x: 420, y: 96 },
            },
          ],
          edges: [
            {
              id: "edge:entry->decision",
              type: "CONTROL_FLOW",
              source: "flow:entry",
              target: "flow:decision",
            },
          ],
        },
        fullGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              position: { x: 120, y: 96 },
            },
            {
              id: "flow:decision",
              type: "FLOW_ACTION",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              position: { x: 420, y: 96 },
            },
          ],
          edges: [
            {
              id: "edge:entry->decision",
              type: "CONTROL_FLOW",
              source: "flow:entry",
              target: "flow:decision",
            },
          ],
        },
        anchorNodeId: "flow:entry",
        summary: { nodeCount: 2, branchCount: 0, exceptionPathCount: 0 },
      },
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "insert-flowchart-node" }));

    expect(screen.getByTestId("flowchart-node-count")).toHaveTextContent("3");
    expect(screen.getByTestId("flowchart-edge-types")).toHaveTextContent(
      "edge:entry->decision:before:CONTROL_FLOW|edge:entry->decision:after:CONTROL_FLOW",
    );
  });

  it("keeps the routed flowchart edge geometry after a local node drag, so the edge renderer can continue adjusting the stored route", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART", {
      flowchartView: {
        visibleGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 120, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
            {
              id: "flow:decision",
              type: "METHOD",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 420, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
          ],
          edges: [
            {
              id: "edge:entry->decision",
              type: "CONTROL_FLOW",
              source: "flow:entry",
              target: "flow:decision",
              route: {
                sections: [
                  {
                    startPoint: { x: 120, y: 160 },
                    bendPoints: [{ x: 120, y: 220 }],
                    endPoint: { x: 420, y: 220 },
                  },
                ],
              },
            },
          ],
        },
        fullGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 120, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
            {
              id: "flow:decision",
              type: "METHOD",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 420, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
          ],
          edges: [
            {
              id: "edge:entry->decision",
              type: "CONTROL_FLOW",
              source: "flow:entry",
              target: "flow:decision",
              route: {
                sections: [
                  {
                    startPoint: { x: 120, y: 160 },
                    bendPoints: [{ x: 120, y: 220 }],
                    endPoint: { x: 420, y: 220 },
                  },
                ],
              },
            },
          ],
        },
        anchorNodeId: "flow:entry",
        summary: { nodeCount: 2, branchCount: 1, exceptionPathCount: 0 },
      },
    });

    render(<App />);

    expect(screen.getByTestId("flowchart-edge-routes")).toHaveTextContent("edge:entry->decision:route");

    await user.click(screen.getByRole("button", { name: "move-flowchart-node" }));

    expect(screen.getByTestId("flowchart-edge-routes")).toHaveTextContent("edge:entry->decision:route");
  });

  it("keeps the flowchart view document in sync after grouped drags", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART", {
      flowchartView: {
        visibleGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 120, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
            {
              id: "flow:decision",
              type: "METHOD",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 420, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
          ],
          edges: [],
        },
        fullGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 120, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
            {
              id: "flow:decision",
              type: "METHOD",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 420, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
          ],
          edges: [],
        },
        anchorNodeId: "flow:entry",
        summary: { nodeCount: 2, branchCount: 1, exceptionPathCount: 0 },
      },
    });

    render(<App />);

    expect(screen.getByTestId("flowchart-node-positions")).toHaveTextContent(
      "flow:entry:120:96|flow:decision:420:96",
    );

    await user.click(screen.getByRole("button", { name: "move-flowchart-group" }));

    await waitFor(() => {
      expect(screen.getByTestId("flowchart-node-positions")).toHaveTextContent(
        "flow:entry:700:360|flow:decision:960:360",
      );
    });
  });

  it("keeps semantic flowchart nodes in sync during grouped drags alongside manual nodes", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("FLOWCHART", {
      flowchartView: {
        visibleGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              position: { x: 120, y: 96 },
              sourceTag: "FACT",
            },
            {
              id: "flow:decision",
              type: "METHOD",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 420, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
          ],
          edges: [],
        },
        fullGraph: {
          nodes: [
            {
              id: "flow:entry",
              type: "METHOD",
              title: "entry",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              position: { x: 120, y: 96 },
              sourceTag: "FACT",
            },
            {
              id: "flow:decision",
              type: "METHOD",
              title: "decision",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 420, y: 96 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
          ],
          edges: [],
        },
        anchorNodeId: "flow:entry",
        summary: { nodeCount: 2, branchCount: 1, exceptionPathCount: 0 },
      },
    });

    render(<App />);

    expect(screen.getByTestId("flowchart-node-positions")).toHaveTextContent(
      "flow:entry:120:96|flow:decision:420:96",
    );

    await user.click(screen.getByRole("button", { name: "move-flowchart-group" }));

    await waitFor(() => {
      expect(screen.getByTestId("flowchart-node-positions")).toHaveTextContent(
        "flow:entry:700:360|flow:decision:960:360",
      );
    });
  });

  it("keeps the resource relation view document in sync after local node drags", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("RESOURCE_RELATION_VIEW", {
      resourceRelationView: {
        visibleGraph: {
          nodes: [
            {
              id: "resource:sql",
              type: "SQL",
              title: "insert order",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 220, y: 140 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
          ],
          edges: [],
        },
        fullGraph: {
          nodes: [
            {
              id: "resource:sql",
              type: "SQL",
              title: "insert order",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              position: { x: 220, y: 140 },
              sourceTag: "DRAFT_MANUAL",
              metadata: {
                "linkGraph.manual": "true",
              },
            },
          ],
          edges: [],
        },
        anchorNodeId: "resource:sql",
        summary: { visibleNodeCount: 1, relationCount: 0, resourceCount: 1, fallbackReason: "NO_BINDING_RELATIONS", laneCounts: { DATA: 1 } },
      },
    });

    render(<App />);

    expect(screen.getByTestId("resource-node-positions")).toHaveTextContent("resource:sql:220:140");

    await user.click(screen.getByRole("button", { name: "move-resource-node" }));

    expect(screen.getByTestId("resource-node-positions")).toHaveTextContent("resource:sql:880:280");
  });

  it("keeps the resource relation view document in sync after local node creation", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = bootstrapState("RESOURCE_RELATION_VIEW");

    render(<App />);

    expect(screen.getByTestId("resource-node-count")).toHaveTextContent("0");

    await user.click(screen.getByRole("button", { name: "add-resource-node" }));

    expect(screen.getByTestId("resource-node-count")).toHaveTextContent("1");
  });

  it("does not rebuild a fact view from raw visibleGraph when the dedicated view document is missing", () => {
    window.linkGraphBootstrap = bootstrapState("FACT_GRAPH", {
      visibleGraph: {
        nodes: [
          {
            id: "method:raw-visible",
            type: "METHOD",
            title: "RawVisibleNode",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        ],
        edges: [],
      },
      factGraphView: undefined,
    });

    render(<App />);

    expect(screen.getByTestId("fact-node-count")).toHaveTextContent("0");
  });
});
