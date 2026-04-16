import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { layoutFlowchartView } from "./flowchartLayout";
import { buildFlowchartEdges, buildFlowchartNodes, FLOWCHART_NODE_TYPES } from "./flowchartNodes";
import type { LinkGraphEdge, LinkGraphNode } from "../../types";

const updateNodeInternalsMock = vi.fn();

vi.mock("@xyflow/react", () => ({
  Handle: ({
    id,
    type,
    position,
    style,
  }: {
    id?: string;
    type: string;
    position: string;
    style?: Record<string, string>;
  }) => (
    <div
      data-testid="react-flow-handle"
      data-handle-id={id}
      data-type={type}
      data-position={position}
      data-style-top={style?.top}
      data-style-left={style?.left}
      data-style-right={style?.right}
      data-style-transform={style?.transform}
      data-style-opacity={style?.opacity}
    />
  ),
  MarkerType: {
    ArrowClosed: "arrowclosed",
  },
  Position: {
    Left: "left",
    Right: "right",
    Top: "top",
    Bottom: "bottom",
  },
  useUpdateNodeInternals: () => updateNodeInternalsMock,
}));

vi.mock("../../components/graph/nodes/FlowchartNodeCard", () => ({
  FlowchartNodeCard: () => <div data-testid="flowchart-node-card">flowchart-node</div>,
}));

function decisionNode(): LinkGraphNode {
  return {
    id: "scope:if",
    type: "FLOW_SCOPE",
    title: "if (order.isValid())",
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata: {
      "flow.kind": "IF",
      "flowchart.kind": "DECISION",
    },
  };
}

function loopDecisionNode(): LinkGraphNode {
  return {
    id: "scope:foreach",
    type: "FLOW_SCOPE",
    title: "for (line : lines)",
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata: {
      "flow.kind": "FOREACH",
      "flowchart.kind": "DECISION",
    },
  };
}

function mergeNode(metadata?: Record<string, string>): LinkGraphNode {
  return {
    id: "merge:after",
    type: "MERGE",
    title: "汇合",
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata: {
      "flowchart.kind": "MERGE",
      ...(metadata ?? {}),
    },
  };
}

function terminalNode(id = "terminal:return", title = "return"): LinkGraphNode {
  return {
    id,
    type: "TERMINAL",
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata: {
      "terminal.kind": "RETURN",
      "flowchart.kind": "TERMINAL",
    },
  };
}

function methodNode(id: string, title: string): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  };
}

function expectBidirectionalPortsOnAllSides(container: HTMLElement) {
  expect(container.querySelector('[data-handle-id="target-top"]')).toHaveAttribute("data-position", "top");
  expect(container.querySelector('[data-handle-id="source-top"]')).toHaveAttribute("data-position", "top");
  expect(container.querySelector('[data-handle-id="target-right"]')).toHaveAttribute("data-position", "right");
  expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-position", "right");
  expect(container.querySelector('[data-handle-id="target-bottom"]')).toHaveAttribute("data-position", "bottom");
  expect(container.querySelector('[data-handle-id="source-bottom"]')).toHaveAttribute("data-position", "bottom");
  expect(container.querySelector('[data-handle-id="target-left"]')).toHaveAttribute("data-position", "left");
  expect(container.querySelector('[data-handle-id="source-left"]')).toHaveAttribute("data-position", "left");
}

function runtimeFileDownloadTopology(): { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] } {
  const nodes: LinkGraphNode[] = [
    {
      ...methodNode("method:anchor", "CommonController.fileDownload"),
      metadata: { "flowchart.kind": "ENTRY" },
    },
    {
      ...methodNode("scope:try", "try"),
      type: "FLOW_SCOPE",
      metadata: { "flow.kind": "TRY", "flowchart.kind": "SCOPE" },
    },
    {
      ...methodNode("terminal:throw", "throw new Exception(...)"),
      type: "TERMINAL",
      metadata: { "terminal.kind": "THROW", "flowchart.kind": "TERMINAL" },
    },
    {
      ...methodNode("action:guard-condition", "!checkAllowDownload(fileName)"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "ACTION", "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("invoke:check-allow-download", "FileUtils.checkAllowDownload(fileName)"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "INVOCATION", "flowchart.kind": "SUBROUTINE" },
    },
    {
      ...methodNode("scope:guard", "if (!allowed)"),
      type: "FLOW_SCOPE",
      metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
    },
    {
      ...methodNode("action:real-file-name", "realFileName = ..."),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "ACTION", "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:download-path-prefix", "downloadPath = ..."),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "ACTION", "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("invoke:get-download-path", "RuoYiConfig.getDownloadPath()"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "INVOCATION", "flowchart.kind": "SUBROUTINE" },
    },
    {
      ...methodNode("action:file-path", "filePath = ..."),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "ACTION", "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:prepare-header", "prepare attachment header"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "ACTION", "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("invoke:set-attachment", "FileUtils.setAttachmentResponseHeader(...)"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "INVOCATION", "flowchart.kind": "SUBROUTINE" },
    },
    {
      ...methodNode("action:prepare-write", "prepare writeBytes(...)"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "ACTION", "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("invoke:write-bytes", "FileUtils.writeBytes(...)"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "INVOCATION", "flowchart.kind": "SUBROUTINE" },
    },
    {
      ...methodNode("scope:delete-if", "if (delete)"),
      type: "FLOW_SCOPE",
      metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
    },
    {
      ...methodNode("action:delete", "deleteFile(filePath)"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "ACTION", "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("invoke:delete-file", "FileUtils.deleteFile(filePath)"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "INVOCATION", "flowchart.kind": "SUBROUTINE" },
    },
    {
      ...methodNode("action:catch", "log(e)"),
      type: "FLOW_ACTION",
      metadata: { "flow.kind": "ACTION", "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("merge:after", "汇合"),
      type: "MERGE",
      metadata: { "flowchart.kind": "MERGE" },
    },
    {
      ...methodNode("terminal:return", "return"),
      type: "TERMINAL",
      metadata: { "terminal.kind": "RETURN", "flowchart.kind": "TERMINAL" },
    },
  ];
  const edges: LinkGraphEdge[] = [
    { id: "entry-try", type: "CONTROL_FLOW", source: "method:anchor", target: "scope:try" },
    { id: "try-normal", type: "CONTROL_FLOW", source: "scope:try", target: "action:guard-condition" },
    { id: "try-exception", type: "CONTROL_FLOW", source: "scope:try", target: "action:catch", label: "EXCEPTION" },
    { id: "guard-condition-check", type: "CONTROL_FLOW", source: "action:guard-condition", target: "invoke:check-allow-download" },
    { id: "check-guard", type: "CONTROL_FLOW", source: "invoke:check-allow-download", target: "scope:guard" },
    { id: "guard-throw", type: "CONTROL_FLOW", source: "scope:guard", target: "terminal:throw", label: "TRUE" },
    { id: "guard-continue", type: "CONTROL_FLOW", source: "scope:guard", target: "action:real-file-name", label: "FALSE" },
    { id: "real-name-prefix", type: "CONTROL_FLOW", source: "action:real-file-name", target: "action:download-path-prefix" },
    { id: "prefix-download-path", type: "CONTROL_FLOW", source: "action:download-path-prefix", target: "invoke:get-download-path" },
    { id: "download-path-file-path", type: "CONTROL_FLOW", source: "invoke:get-download-path", target: "action:file-path" },
    { id: "file-path-prepare-header", type: "CONTROL_FLOW", source: "action:file-path", target: "action:prepare-header" },
    { id: "prepare-header-set-attachment", type: "CONTROL_FLOW", source: "action:prepare-header", target: "invoke:set-attachment" },
    { id: "set-attachment-prepare-write", type: "CONTROL_FLOW", source: "invoke:set-attachment", target: "action:prepare-write" },
    { id: "prepare-write-write-bytes", type: "CONTROL_FLOW", source: "action:prepare-write", target: "invoke:write-bytes" },
    { id: "write-bytes-delete-if", type: "CONTROL_FLOW", source: "invoke:write-bytes", target: "scope:delete-if" },
    { id: "delete-if-true", type: "CONTROL_FLOW", source: "scope:delete-if", target: "action:delete", label: "TRUE" },
    { id: "delete-action-invoke", type: "CONTROL_FLOW", source: "action:delete", target: "invoke:delete-file" },
    { id: "delete-invoke-merge", type: "CONTROL_FLOW", source: "invoke:delete-file", target: "merge:after" },
    { id: "delete-if-false", type: "CONTROL_FLOW", source: "scope:delete-if", target: "merge:after", label: "FALSE" },
    { id: "catch-merge", type: "CONTROL_FLOW", source: "action:catch", target: "merge:after" },
    { id: "merge-return", type: "CONTROL_FLOW", source: "merge:after", target: "terminal:return" },
  ];
  return { nodes, edges };
}

describe("buildFlowchartNodes", () => {
  it("refreshes React Flow internals after rendering a custom flowchart node", () => {
    updateNodeInternalsMock.mockClear();
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    render(
      <FlowchartNode
        id="scope:if"
        data={{
          node: decisionNode(),
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(updateNodeInternalsMock).toHaveBeenCalledWith("scope:if");
  });

  it("does not expose connectable handle hover state in read-only flowchart nodes", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        data={{
          node: decisionNode(),
        }}
        selected={false}
        isConnectable={false}
      />,
    );

    expect(container.querySelector(".flowchart-react-node.kind-decision")).not.toHaveClass("is-connectable");
  });

  it("keeps regular flowchart handles flush with the node border so edges start on the visible node endpoint", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        id="method:anchor"
        data={{
          node: {
            ...methodNode("method:anchor", "CommonController.fileDownload"),
            metadata: { "flowchart.kind": "ENTRY" },
          },
        }}
        selected={false}
        isConnectable
      />,
    );

    expectBidirectionalPortsOnAllSides(container);
    expect(container.querySelector('[data-handle-id="target-top"]')).toHaveAttribute("data-style-transform", "translate(-50%, 0)");
    expect(container.querySelector('[data-handle-id="source-top"]')).toHaveAttribute("data-style-transform", "translate(-50%, 0)");
    expect(container.querySelector('[data-handle-id="target-bottom"]')).toHaveAttribute("data-style-transform", "translate(-50%, 0)");
    expect(container.querySelector('[data-handle-id="source-bottom"]')).toHaveAttribute("data-style-transform", "translate(-50%, 0)");
    expect(container.querySelector('[data-handle-id="target-right"]')).toHaveAttribute("data-style-top", "50%");
    expect(container.querySelector('[data-handle-id="target-right"]')).toHaveAttribute("data-style-transform", "translate(0, -50%)");
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-style-top", "50%");
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-style-transform", "translate(0, -50%)");
    expect(container.querySelector('[data-handle-id="target-left"]')).toHaveAttribute("data-style-top", "50%");
    expect(container.querySelector('[data-handle-id="target-left"]')).toHaveAttribute("data-style-transform", "translate(0, -50%)");
    expect(container.querySelector('[data-handle-id="source-left"]')).toHaveAttribute("data-style-top", "50%");
    expect(container.querySelector('[data-handle-id="source-left"]')).toHaveAttribute("data-style-transform", "translate(0, -50%)");
  });

  it("renders decision handles on the top and both side vertices for local branch and loop routing", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        data={{
          node: decisionNode(),
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector(".flowchart-react-node.kind-decision")).toHaveClass("is-connectable");
    expect(screen.getAllByTestId("react-flow-handle")).toHaveLength(8);
    expectBidirectionalPortsOnAllSides(container);
    expect(container.querySelector('[data-handle-id="target-top"]')).toHaveAttribute("data-style-transform", "translate(-50%, 0)");
    expect(container.querySelector('[data-handle-id="source-top"]')).toHaveAttribute("data-style-transform", "translate(-50%, 0)");
    expect(container.querySelector('[data-handle-id="source-left"]')).toHaveAttribute("data-style-top", "50%");
    expect(container.querySelector('[data-handle-id="source-left"]')).toHaveAttribute("data-style-transform", "translate(0, -50%)");
    expect(container.querySelector('[data-handle-id="source-left"]')).toHaveAttribute("data-style-opacity", "0.28");
    expect(container.querySelector('[data-handle-id="target-left"]')).toHaveAttribute("data-style-top", "50%");
    expect(container.querySelector('[data-handle-id="target-left"]')).toHaveAttribute("data-style-transform", "translate(0, -50%)");
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-style-top", "50%");
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-style-transform", "translate(0, -50%)");
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-style-opacity", "0.28");
    expect(container.querySelector('[data-handle-id="target-right"]')).toHaveAttribute("data-style-top", "50%");
    expect(container.querySelector('[data-handle-id="target-right"]')).toHaveAttribute("data-style-transform", "translate(0, -50%)");
    expect(container.querySelector('[data-handle-id="target-bottom"]')).toHaveAttribute("data-style-transform", "translate(-50%, 0)");
    expect(container.querySelector('[data-handle-id="source-bottom"]')).toHaveAttribute("data-style-transform", "translate(-50%, 0)");
  });

  it("renders foreach loop scopes with the same decision handles as branch nodes", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        id="scope:foreach"
        data={{
          node: loopDecisionNode(),
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector(".flowchart-react-node.kind-decision")).toBeInTheDocument();
    expect(screen.getAllByTestId("react-flow-handle")).toHaveLength(8);
    expectBidirectionalPortsOnAllSides(container);
  });

  it("keeps side-entry target handles available on loop decision nodes so pre-test back-edges can re-enter locally", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        id="scope:foreach"
        data={{
          node: loopDecisionNode(),
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector('[data-handle-id="target-left"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-right"]')).toBeInTheDocument();
  });

  it("keeps terminal nodes connectable on all four sides while routing can still choose not to emit from them automatically", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        id="terminal:return"
        data={{
          node: terminalNode(),
          hasExceptionSource: false,
          mergeLeftTargetCount: 0,
          mergeRightTargetCount: 0,
        }}
        selected={false}
        isConnectable
      />,
    );

    expectBidirectionalPortsOnAllSides(container);
  });

  it("keeps merge nodes connectable on all four sides while retaining indexed merge inlet handles for routing", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        id="merge:after"
        data={{
          node: mergeNode({
            "flowchart.mergeLeftTargetCount": "1",
            "flowchart.mergeRightTargetCount": "1",
          }),
          hasExceptionSource: false,
          mergeLeftTargetCount: 1,
          mergeRightTargetCount: 1,
        }}
        selected={false}
        isConnectable
      />,
    );

    expectBidirectionalPortsOnAllSides(container);
    expect(container.querySelector('[data-handle-id="target-left-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-right-0"]')).toBeInTheDocument();
  });

  it("keeps the decision wrapper stretched to the same minimum height as the ELK layout box", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        data={{
          node: decisionNode(),
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector(".flowchart-react-node.kind-decision")).toHaveStyle({
      minHeight: "228px",
    });
  });

  it("does not paint a rectangular React Flow shell around decision nodes", () => {
    const builtNode = buildFlowchartNodes({
      nodes: [decisionNode()],
      edges: [],
      selectedNodeId: "scope:if",
      explanationFocusNodeId: null,
      draftChangedNodeIds: [],
      nodeSizeRegistry: createNodeSizeRegistry(),
    })[0];

    expect(builtNode?.className ?? "").toContain("flowchart-rf-node");
    expect(builtNode?.className ?? "").toContain("kind-decision");
    expect(builtNode?.style).toMatchObject({
      background: "transparent",
      boxShadow: "none",
      border: "none",
      borderRadius: 0,
    });
  });

  it("switches flowchart edges to the shared routed edge renderer when ELK route data is present", () => {
    const routeEdge: LinkGraphEdge = {
      id: "edge:if->true",
      type: "CONTROL_FLOW",
      source: "scope:if",
      target: "action:true",
      label: "TRUE",
      route: {
        sections: [
          {
            startPoint: { x: 100, y: 80 },
            bendPoints: [{ x: 100, y: 120 }, { x: 40, y: 120 }],
            endPoint: { x: 40, y: 180 },
          },
        ],
      },
    };
    const decision = decisionNode();
    const targetNode: LinkGraphNode = {
      id: "action:true",
      type: "FLOW_ACTION",
      title: "persistOrder()",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: { "flowchart.kind": "PROCESS" },
    };

    const builtEdge = buildFlowchartEdges({
      edges: [routeEdge],
      nodeIndex: new Map([
        [decision.id, decision],
        [targetNode.id, targetNode],
      ]),
    })[0];

    expect(builtEdge?.type).toBe("routedEdge");
    expect(builtEdge?.data).toMatchObject({
      route: routeEdge.route,
    });
  });

  it("resolves decision source handles from the actual target direction instead of hard-coded branch labels", () => {
    const decision: LinkGraphNode = {
      ...decisionNode(),
      position: { x: 420, y: 240 },
    };
    const leftTarget: LinkGraphNode = {
      id: "action:left",
      type: "FLOW_ACTION",
      title: "continueLeft()",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 120, y: 420 },
      metadata: { "flowchart.kind": "PROCESS" },
    };
    const rightTarget: LinkGraphNode = {
      id: "action:right",
      type: "FLOW_ACTION",
      title: "continueRight()",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 840, y: 420 },
      metadata: { "flowchart.kind": "PROCESS" },
    };
    const lowerTarget: LinkGraphNode = {
      id: "action:down",
      type: "FLOW_ACTION",
      title: "continueDown()",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 420, y: 620 },
      metadata: { "flowchart.kind": "PROCESS" },
    };

    const builtEdges = buildFlowchartEdges({
      edges: [
        { id: "edge:false-left", type: "CONTROL_FLOW", source: decision.id, target: leftTarget.id, label: "FALSE" },
        { id: "edge:true-right", type: "CONTROL_FLOW", source: decision.id, target: rightTarget.id, label: "TRUE" },
        { id: "edge:default-down", type: "CONTROL_FLOW", source: decision.id, target: lowerTarget.id, label: "DEFAULT" },
      ],
      nodeIndex: new Map([
        [decision.id, decision],
        [leftTarget.id, leftTarget],
        [rightTarget.id, rightTarget],
        [lowerTarget.id, lowerTarget],
      ]),
    });

    expect(builtEdges.find((edge) => edge.id === "edge:false-left")?.sourceHandle).toBe("source-left");
    expect(builtEdges.find((edge) => edge.id === "edge:true-right")?.sourceHandle).toBe("source-right");
    expect(builtEdges.find((edge) => edge.id === "edge:default-down")?.sourceHandle).toBe("source-bottom");
  });

  it("keeps guard-style fallthrough on the bottom handle so the main flow does not leave the diamond from a side port", () => {
    const decision: LinkGraphNode = {
      ...decisionNode(),
      position: { x: 420, y: 240 },
    };
    const terminalTarget: LinkGraphNode = {
      id: "terminal:throw",
      type: "TERMINAL",
      title: "throw new Exception(...)",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 120, y: 620 },
      metadata: { "terminal.kind": "THROW", "flowchart.kind": "TERMINAL" },
    };
    const fallthroughTarget: LinkGraphNode = {
      id: "action:continue",
      type: "FLOW_ACTION",
      title: "realFileName = ...",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 760, y: 620 },
      metadata: { "flowchart.kind": "PROCESS" },
    };

    const builtEdges = buildFlowchartEdges({
      edges: [
        { id: "edge:guard-throw", type: "CONTROL_FLOW", source: decision.id, target: terminalTarget.id, label: "TRUE" },
        { id: "edge:guard-continue", type: "CONTROL_FLOW", source: decision.id, target: fallthroughTarget.id, label: "FALSE" },
      ],
      nodeIndex: new Map([
        [decision.id, decision],
        [terminalTarget.id, terminalTarget],
        [fallthroughTarget.id, fallthroughTarget],
      ]),
    });

    expect(builtEdges.find((edge) => edge.id === "edge:guard-throw")?.sourceHandle).toBe("source-left");
    expect(builtEdges.find((edge) => edge.id === "edge:guard-continue")?.sourceHandle).toBe("source-bottom");
  });

  it("keeps single-branch if fallthrough on the bottom handle when the skipped branch goes directly to merge", () => {
    const decision: LinkGraphNode = {
      ...decisionNode(),
      position: { x: 420, y: 240 },
      title: "if (delete)",
    };
    const actionTarget: LinkGraphNode = {
      id: "action:delete",
      type: "FLOW_ACTION",
      title: "deleteFile(filePath)",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 760, y: 620 },
      metadata: { "flowchart.kind": "PROCESS" },
    };
    const mergeTarget: LinkGraphNode = {
      id: "merge:after-delete",
      type: "MERGE",
      title: "汇合",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 760, y: 860 },
      metadata: { "flowchart.kind": "MERGE" },
    };

    const builtEdges = buildFlowchartEdges({
      edges: [
        { id: "edge:delete-true", type: "CONTROL_FLOW", source: decision.id, target: actionTarget.id, label: "TRUE" },
        { id: "edge:delete-false", type: "CONTROL_FLOW", source: decision.id, target: mergeTarget.id, label: "FALSE" },
      ],
      nodeIndex: new Map([
        [decision.id, decision],
        [actionTarget.id, actionTarget],
        [mergeTarget.id, mergeTarget],
      ]),
    });

    expect(builtEdges.find((edge) => edge.id === "edge:delete-true")?.sourceHandle).toBe("source-right");
    expect(builtEdges.find((edge) => edge.id === "edge:delete-false")?.sourceHandle).toBe("source-bottom");
  });

  it("keeps the semantic false fallthrough on the bottom merge lane even when a manual design link adds a third outgoing branch", () => {
    const decision: LinkGraphNode = {
      ...decisionNode(),
      position: { x: 420, y: 240 },
      title: "if (delete)",
    };
    const actionTarget: LinkGraphNode = {
      id: "action:delete",
      type: "FLOW_ACTION",
      title: "deleteFile(filePath)",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 760, y: 620 },
      metadata: { "flowchart.kind": "PROCESS" },
    };
    const mergeTarget: LinkGraphNode = {
      id: "merge:after-delete",
      type: "MERGE",
      title: "汇合",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 760, y: 860 },
      metadata: { "flowchart.kind": "MERGE" },
    };
    const manualBypassTarget: LinkGraphNode = {
      id: "invoke:delete-file",
      type: "FLOW_ACTION",
      title: "FileUtils.deleteFile(filePath)",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "DESIGN_ONLY",
      position: { x: 980, y: 620 },
      metadata: { "flowchart.kind": "SUBROUTINE" },
      sourceTag: "DRAFT_MANUAL",
    };

    const builtEdges = buildFlowchartEdges({
      edges: [
        { id: "edge:delete-true", type: "CONTROL_FLOW", source: decision.id, target: actionTarget.id, label: "TRUE" },
        { id: "edge:delete-false", type: "CONTROL_FLOW", source: decision.id, target: mergeTarget.id, label: "FALSE" },
        {
          id: "design-link:delete-bypass",
          type: "CONTROL_FLOW",
          source: decision.id,
          target: manualBypassTarget.id,
          sourceTag: "DRAFT_MANUAL",
        },
      ],
      nodeIndex: new Map([
        [decision.id, decision],
        [actionTarget.id, actionTarget],
        [mergeTarget.id, mergeTarget],
        [manualBypassTarget.id, manualBypassTarget],
      ]),
    });

    expect(builtEdges.find((edge) => edge.id === "edge:delete-true")?.sourceHandle).toBe("source-right");
    expect(builtEdges.find((edge) => edge.id === "edge:delete-false")?.sourceHandle).toBe("source-bottom");
    expect(builtEdges.find((edge) => edge.id === "edge:delete-false")?.targetHandle).toBe("target-top");
    expect(builtEdges.find((edge) => edge.id === "design-link:delete-bypass")?.sourceHandle).toBe("source-right");
  });

  it("locks semantic flowchart nodes while keeping manual draft nodes draggable", () => {
    const registry = createNodeSizeRegistry();
    const semantic = {
      ...methodNode("method:semantic", "CommonController.uploadFiles"),
      position: { x: 120, y: 96 },
      sourceTag: "FACT" as const,
      metadata: { "flowchart.kind": "ENTRY" },
    };
    const manual = {
      ...methodNode("design:manual", "人工补充节点"),
      position: { x: 520, y: 96 },
      bindingStatus: "DESIGN_ONLY" as const,
      sourceTag: "DRAFT_MANUAL" as const,
      metadata: {
        "flowchart.kind": "PROCESS",
        "linkGraph.manual": "true",
      },
    };

    const builtNodes = buildFlowchartNodes({
      nodes: [semantic, manual],
      edges: [],
      selectedNodeId: null,
      nodeSizeRegistry: registry,
    });

    expect(builtNodes.find((node) => node.id === semantic.id)?.draggable).toBe(false);
    expect(builtNodes.find((node) => node.id === manual.id)?.draggable).toBe(true);
  });

  it("prefers explicit edge handle intent over inferred decision semantics when building flowchart edges", () => {
    const decision = {
      ...decisionNode(),
      position: { x: 420, y: 180 },
    };
    const rightTarget: LinkGraphNode = {
      id: "action:right",
      type: "FLOW_ACTION",
      title: "右侧节点",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 760, y: 180 },
      metadata: { "flowchart.kind": "PROCESS" },
    };

    const builtEdges = buildFlowchartEdges({
      edges: [
        {
          id: "design-link:explicit-left",
          type: "CONTROL_FLOW",
          source: decision.id,
          target: rightTarget.id,
          sourceHandle: "source-left",
          targetHandle: "target-top",
          sourceTag: "DRAFT_MANUAL",
        },
      ],
      nodeIndex: new Map([
        [decision.id, decision],
        [rightTarget.id, rightTarget],
      ]),
    });

    expect(builtEdges.find((edge) => edge.id === "design-link:explicit-left")?.sourceHandle).toBe("source-left");
    expect(builtEdges.find((edge) => edge.id === "design-link:explicit-left")?.targetHandle).toBe("target-top");
  });

  it("renders an extra right-side source handle for non-decision flow nodes that expose an exception outlet", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;
    const tryNode: LinkGraphNode = {
      id: "scope:try",
      type: "FLOW_SCOPE",
      title: "try",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "flow.kind": "TRY",
        "flowchart.kind": "PROCESS",
        "flowchart.hasExceptionSource": "true",
      },
    };

    const { container } = render(
      <FlowchartNode
        data={{
          node: tryNode,
          hasExceptionSource: true,
          mergeLeftTargetCount: 0,
          mergeRightTargetCount: 0,
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector('[data-handle-id="source-right"]')).not.toBeNull();
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-position", "right");
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-style-top", "50%");
  });

  it("renders multiple right-side target handles for merge nodes when layout metadata declares stacked same-side inlets", () => {
    updateNodeInternalsMock.mockClear();
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        id="merge:after"
        data={{
          node: mergeNode({
            "flowchart.mergeRightTargetCount": "2",
          }),
          hasExceptionSource: false,
          mergeLeftTargetCount: 0,
          mergeRightTargetCount: 2,
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector('[data-handle-id="target-right-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-right-1"]')).toBeInTheDocument();
  });

  it("keeps the right-side authoring source handle available on non-terminal flow nodes even before an exception edge exists", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;
    const processNode: LinkGraphNode = {
      id: "action:prepare-header",
      type: "FLOW_ACTION",
      title: "prepare attachment header",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "flowchart.kind": "PROCESS",
      },
    };

    const { container } = render(
      <FlowchartNode
        id="action:prepare-header"
        data={{
          node: processNode,
          hasExceptionSource: false,
          mergeLeftTargetCount: 0,
          mergeRightTargetCount: 0,
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector('[data-handle-id="source-right"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="source-right"]')).toHaveAttribute("data-position", "right");
  });

  it("keeps left and right merge inlets available for authoring even when the current topology has not occupied them yet", () => {
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;

    const { container } = render(
      <FlowchartNode
        id="merge:after"
        data={{
          node: mergeNode({
            "flowchart.mergeLeftTargetCount": "0",
            "flowchart.mergeRightTargetCount": "0",
          }),
          hasExceptionSource: false,
          mergeLeftTargetCount: 0,
          mergeRightTargetCount: 0,
        }}
        selected={false}
        isConnectable
      />,
    );

    expect(container.querySelector('[data-handle-id="target-left-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-right-0"]')).toBeInTheDocument();
  });

  it("routes exception-labelled control flow out of non-decision flow nodes through the right-side source handle", () => {
    const tryNode: LinkGraphNode = {
      id: "scope:try",
      type: "FLOW_SCOPE",
      title: "try",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "flow.kind": "TRY",
        "flowchart.kind": "PROCESS",
        "flowchart.hasExceptionSource": "true",
      },
      position: { x: 420, y: 240 },
    };
    const normalTarget: LinkGraphNode = {
      id: "action:normal",
      type: "FLOW_ACTION",
      title: "doWork()",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 420, y: 620 },
      metadata: { "flowchart.kind": "PROCESS" },
    };
    const exceptionTarget: LinkGraphNode = {
      id: "action:exception",
      type: "FLOW_ACTION",
      title: "handleException()",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      position: { x: 820, y: 420 },
      metadata: { "flowchart.kind": "PROCESS" },
    };

    const builtEdges = buildFlowchartEdges({
      edges: [
        { id: "edge:try-normal", type: "CONTROL_FLOW", source: tryNode.id, target: normalTarget.id },
        { id: "edge:try-exception", type: "CONTROL_FLOW", source: tryNode.id, target: exceptionTarget.id, label: "EXCEPTION" },
      ],
      nodeIndex: new Map([
        [tryNode.id, tryNode],
        [normalTarget.id, normalTarget],
        [exceptionTarget.id, exceptionTarget],
      ]),
    });

    expect(builtEdges.find((edge) => edge.id === "edge:try-normal")?.sourceHandle).toBe("source-bottom");
    expect(builtEdges.find((edge) => edge.id === "edge:try-exception")?.sourceHandle).toBe("source-right");
  });

  it("keeps the real fileDownload try exception edge on the right-side source handle after layout metadata is applied", async () => {
    const { nodes, edges } = runtimeFileDownloadTopology();
    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const tryNode = laidOut.nodes.find((node) => node.id === "scope:try");

    expect(tryNode?.metadata?.["flowchart.hasExceptionSource"]).toBe("true");

    const builtEdges = buildFlowchartEdges({
      edges: laidOut.edges,
      nodeIndex: new Map(laidOut.nodes.map((node) => [node.id, node])),
    });

    expect(builtEdges.find((edge) => edge.id === "try-normal")?.sourceHandle).toBe("source-bottom");
    expect(builtEdges.find((edge) => edge.id === "try-exception")?.sourceHandle).toBe("source-right");
  });

  it("routes pre-test loop back-edges into a loop decision side target instead of forcing them through target-top", () => {
    const loopNode: LinkGraphNode = {
      id: "scope:foreach",
      type: "FLOW_SCOPE",
      title: "for (file : files)",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "flow.kind": "FOREACH",
        "flow.scopeCategory": "LOOP_PRE_TEST",
        "flowchart.kind": "DECISION",
      },
      position: { x: 160, y: 240 },
    };
    const bodyTail: LinkGraphNode = {
      id: "action:add-new-file-name",
      type: "FLOW_ACTION",
      title: "newFileNames.add(...)",
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: { "flowchart.kind": "PROCESS" },
      position: { x: 160, y: 720 },
    };

    const builtEdges = buildFlowchartEdges({
      edges: [
        {
          id: "edge:loop-back",
          type: "CONTROL_FLOW",
          source: bodyTail.id,
          target: loopNode.id,
          metadata: { "flow.edgeRole": "LOOP_BACK" },
        },
      ],
      nodeIndex: new Map([
        [loopNode.id, loopNode],
        [bodyTail.id, bodyTail],
      ]),
    });

    expect(builtEdges.find((edge) => edge.id === "edge:loop-back")?.targetHandle).not.toBe("target-top");
  });

  it("keeps the real fileDownload catch recovery lane and delete cleanup lane on different right-side merge handles", async () => {
    const { nodes, edges } = runtimeFileDownloadTopology();
    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });

    const builtEdges = buildFlowchartEdges({
      edges: laidOut.edges,
      nodeIndex: new Map(laidOut.nodes.map((node) => [node.id, node])),
    });
    const catchMerge = builtEdges.find((edge) => edge.id === "catch-merge");
    const deleteMerge = builtEdges.find((edge) => edge.id === "delete-invoke-merge");

    expect(catchMerge?.targetHandle).not.toBe("target-top");
    expect(deleteMerge?.targetHandle).not.toBe("target-top");
    expect(catchMerge?.targetHandle).not.toBe(deleteMerge?.targetHandle);
  });

  it("rebuilds exception and merge runtime handles from routed topology even after local flowchart metadata is stripped during a layout-only update", async () => {
    const { nodes, edges } = runtimeFileDownloadTopology();
    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const strippedNodes = laidOut.nodes.map((node) => {
      const nextMetadata = Object.fromEntries(
        Object.entries(node.metadata ?? {}).filter(([key]) =>
          key !== "flowchart.hasExceptionSource"
          && key !== "flowchart.mergeLeftTargetCount"
          && key !== "flowchart.mergeRightTargetCount"),
      );
      return {
        ...node,
        metadata: Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined,
      };
    });
    const builtEdges = buildFlowchartEdges({
      edges: laidOut.edges,
      nodeIndex: new Map(strippedNodes.map((node) => [node.id, node])),
    });
    const builtNodes = buildFlowchartNodes({
      nodes: strippedNodes,
      edges: laidOut.edges,
      selectedNodeId: null,
      explanationFocusNodeId: null,
      draftChangedNodeIds: [],
      nodeSizeRegistry: createNodeSizeRegistry(),
    });
    const FlowchartNode = FLOWCHART_NODE_TYPES.flowchartNode as (props: Record<string, unknown>) => JSX.Element;
    const tryNode = builtNodes.find((node) => node.id === "scope:try");
    const mergeAfterNode = builtNodes.find((node) => node.id === "merge:after");

    expect(builtEdges.find((edge) => edge.id === "try-exception")?.sourceHandle).toBe("source-right");
    expect(builtEdges.find((edge) => edge.id === "catch-merge")?.targetHandle).toBe("target-right-0");
    expect(builtEdges.find((edge) => edge.id === "delete-invoke-merge")?.targetHandle).toBe("target-left-0");

    const { container } = render(
      <>
        {tryNode ? (
          <FlowchartNode
            id={tryNode.id}
            data={tryNode.data}
            selected={false}
            isConnectable
          />
        ) : null}
        {mergeAfterNode ? (
          <FlowchartNode
            id={mergeAfterNode.id}
            data={mergeAfterNode.data}
            selected={false}
            isConnectable
          />
        ) : null}
      </>,
    );

    expect(container.querySelector('[data-handle-id="source-right"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-left-0"]')).toBeInTheDocument();
    expect(container.querySelector('[data-handle-id="target-right-0"]')).toBeInTheDocument();
  });

  it("marks explanation focus nodes and draft change nodes with dedicated React Flow classes", () => {
    const builtNodes = buildFlowchartNodes({
      nodes: [
        {
          ...methodNode("method:anchor", "CommonController.fileDownload"),
          metadata: { "flowchart.kind": "ENTRY" },
        },
        {
          ...methodNode("action:guard", "validate()"),
          type: "FLOW_ACTION",
          metadata: { "flowchart.kind": "PROCESS" },
        },
      ],
      edges: [],
      selectedNodeId: "method:anchor",
      explanationFocusNodeId: "method:anchor",
      draftChangedNodeIds: ["action:guard"],
      nodeSizeRegistry: createNodeSizeRegistry(),
    });

    expect(builtNodes.find((node) => node.id === "method:anchor")?.className ?? "").toContain("is-explanation-focus");
    expect(builtNodes.find((node) => node.id === "action:guard")?.className ?? "").toContain("is-draft-change");
  });
});
