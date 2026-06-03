import { describe, expect, it } from "vitest";
import {
  FLOWCHART_DECISION_MIN_HEIGHT,
  FLOWCHART_DECISION_WIDTH,
  flowchartNodeCardWidth,
} from "../../../../app/graphNodeSizing";
import type { GraphPosition } from "../../../../app/types";
import type { LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import { layoutFlowchartView } from "../../../../app/views/flowchart/flowchartLayout";

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

function uploadFilesLoopTopology(): { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] } {
  const nodes: LinkGraphNode[] = [
    {
      ...methodNode("method:anchor", "CommonController.uploadFiles"),
      metadata: { "flowchart.kind": "ENTRY" },
    },
    {
      ...methodNode("scope:try", "try"),
      type: "FLOW_SCOPE",
      metadata: { "flow.kind": "TRY", "flowchart.kind": "SCOPE" },
    },
    {
      ...methodNode("action:file-path", "filePath = getUploadPath()"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:init-urls", "urls = new ArrayList<>()"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:init-file-names", "fileNames = new ArrayList<>()"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:init-new-file-names", "newFileNames = new ArrayList<>()"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("scope:foreach", "for (file : files)"),
      type: "FLOW_SCOPE",
      metadata: {
        "flow.kind": "FOREACH",
        "flow.scopeCategory": "LOOP_PRE_TEST",
        "flowchart.kind": "DECISION",
      },
    },
    {
      ...methodNode("action:upload", "fileName = upload(filePath, file)"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:get-url", "url = serverConfig.getUrl() + fileName"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:add-url", "urls.add(url)"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:add-file-name", "fileNames.add(fileName)"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:add-new-file-name", "newFileNames.add(getName(fileName))"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:success", "ajax = AjaxResult.success()"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:put-urls", "ajax.put(urls, ...)"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:put-file-names", "ajax.put(fileNames, ...)"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:put-new-file-names", "ajax.put(newFileNames, ...)"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("action:catch", "AjaxResult.error(e.getMessage())"),
      type: "FLOW_ACTION",
      metadata: { "flowchart.kind": "PROCESS" },
    },
    {
      ...methodNode("terminal:return-error", "return AjaxResult.error(...)"),
      type: "TERMINAL",
      metadata: { "terminal.kind": "RETURN", "flowchart.kind": "TERMINAL" },
    },
  ];
  const edges: LinkGraphEdge[] = [
    { id: "entry-try", type: "CONTROL_FLOW", source: "method:anchor", target: "scope:try" },
    { id: "try-file-path", type: "CONTROL_FLOW", source: "scope:try", target: "action:file-path" },
    { id: "try-exception", type: "CONTROL_FLOW", source: "scope:try", target: "action:catch", label: "EXCEPTION" },
    { id: "file-path-init-urls", type: "CONTROL_FLOW", source: "action:file-path", target: "action:init-urls" },
    { id: "init-urls-init-file-names", type: "CONTROL_FLOW", source: "action:init-urls", target: "action:init-file-names" },
    { id: "init-file-names-init-new-file-names", type: "CONTROL_FLOW", source: "action:init-file-names", target: "action:init-new-file-names" },
    { id: "init-new-file-names-foreach", type: "CONTROL_FLOW", source: "action:init-new-file-names", target: "scope:foreach" },
    {
      id: "foreach-body",
      type: "CONTROL_FLOW",
      source: "scope:foreach",
      target: "action:upload",
      metadata: { "flow.edgeRole": "LOOP_BODY" },
    },
    { id: "upload-get-url", type: "CONTROL_FLOW", source: "action:upload", target: "action:get-url" },
    { id: "get-url-add-url", type: "CONTROL_FLOW", source: "action:get-url", target: "action:add-url" },
    { id: "add-url-add-file-name", type: "CONTROL_FLOW", source: "action:add-url", target: "action:add-file-name" },
    { id: "add-file-name-add-new-file-name", type: "CONTROL_FLOW", source: "action:add-file-name", target: "action:add-new-file-name" },
    {
      id: "add-new-file-name-back",
      type: "CONTROL_FLOW",
      source: "action:add-new-file-name",
      target: "scope:foreach",
      metadata: { "flow.edgeRole": "LOOP_BACK" },
    },
    {
      id: "foreach-exit",
      type: "CONTROL_FLOW",
      source: "scope:foreach",
      target: "action:success",
      metadata: { "flow.edgeRole": "LOOP_EXIT" },
    },
    { id: "success-put-urls", type: "CONTROL_FLOW", source: "action:success", target: "action:put-urls" },
    { id: "put-urls-put-file-names", type: "CONTROL_FLOW", source: "action:put-urls", target: "action:put-file-names" },
    { id: "put-file-names-put-new-file-names", type: "CONTROL_FLOW", source: "action:put-file-names", target: "action:put-new-file-names" },
    { id: "catch-return-error", type: "CONTROL_FLOW", source: "action:catch", target: "terminal:return-error" },
  ];
  return { nodes, edges };
}

function attachmentPointInsideDecision(
  edge: LinkGraphEdge,
  decision: LinkGraphNode,
): GraphPosition {
  const section = edge.route?.sections[0];
  if (!section || !decision.position) {
    throw new Error("missing decision attachment section");
  }
  const withinDecisionBounds = (point: GraphPosition) =>
    point.x >= decision.position!.x - 1
    && point.x <= decision.position!.x + FLOWCHART_DECISION_WIDTH + 1
    && point.y >= decision.position!.y - 1
    && point.y <= decision.position!.y + FLOWCHART_DECISION_MIN_HEIGHT + 1;
  return withinDecisionBounds(section.startPoint) ? section.startPoint : section.endPoint;
}

function decisionCenterX(decision: LinkGraphNode): number {
  if (!decision.position) {
    throw new Error("missing decision position");
  }
  return decision.position.x + FLOWCHART_DECISION_WIDTH / 2;
}

function routePoints(edge: LinkGraphEdge): GraphPosition[] {
  return edge.route?.sections.flatMap((section) => [
    section.startPoint,
    ...(section.bendPoints ?? []),
    section.endPoint,
  ]) ?? [];
}

function routeSegments(edge: LinkGraphEdge) {
  const points = routePoints(edge);
  return points.slice(1).map((point, index) => ({
    startPoint: points[index]!,
    endPoint: point,
  }));
}

function routeTotalLength(edge: LinkGraphEdge): number {
  return routeSegments(edge).reduce((total, segment) => (
    total
    + Math.abs(segment.startPoint.x - segment.endPoint.x)
    + Math.abs(segment.startPoint.y - segment.endPoint.y)
  ), 0);
}

function routeBounds(edge: LinkGraphEdge) {
  const points = routePoints(edge);
  if (points.length === 0) {
    throw new Error("missing route points");
  }
  return {
    minX: Math.min(...points.map((point) => point.x)),
    maxX: Math.max(...points.map((point) => point.x)),
    minY: Math.min(...points.map((point) => point.y)),
    maxY: Math.max(...points.map((point) => point.y)),
  };
}

function nodeBounds(node: LinkGraphNode) {
  if (!node.position) {
    throw new Error(`missing node position for ${node.id}`);
  }
  return {
    left: node.position.x,
    right: node.position.x + flowchartNodeCardWidth(node),
    top: node.position.y,
    bottom: node.position.y + (node.metadata?.["flowchart.kind"] === "DECISION" ? FLOWCHART_DECISION_MIN_HEIGHT : 156),
  };
}

function segmentIntersectsNode(
  segment: { startPoint: GraphPosition; endPoint: GraphPosition },
  node: LinkGraphNode,
): boolean {
  const bounds = nodeBounds(node);
  if (Math.abs(segment.startPoint.x - segment.endPoint.x) <= 0.5) {
    const x = segment.startPoint.x;
    if (x <= bounds.left + 1 || x >= bounds.right - 1) {
      return false;
    }
    const top = Math.min(segment.startPoint.y, segment.endPoint.y);
    const bottom = Math.max(segment.startPoint.y, segment.endPoint.y);
    return Math.max(top, bounds.top) < Math.min(bottom, bounds.bottom);
  }
  if (Math.abs(segment.startPoint.y - segment.endPoint.y) <= 0.5) {
    const y = segment.startPoint.y;
    if (y <= bounds.top + 1 || y >= bounds.bottom - 1) {
      return false;
    }
    const left = Math.min(segment.startPoint.x, segment.endPoint.x);
    const right = Math.max(segment.startPoint.x, segment.endPoint.x);
    return Math.max(left, bounds.left) < Math.min(right, bounds.right);
  }
  return true;
}

function routeIntersectsAnyNode(edge: LinkGraphEdge, nodes: LinkGraphNode[]): boolean {
  return routeSegments(edge).some((segment) => nodes.some((node) => segmentIntersectsNode(segment, node)));
}

function isVerticalSegment(segment: { startPoint: GraphPosition; endPoint: GraphPosition }) {
  return Math.abs(segment.startPoint.x - segment.endPoint.x) <= 0.5;
}

function isHorizontalSegment(segment: { startPoint: GraphPosition; endPoint: GraphPosition }) {
  return Math.abs(segment.startPoint.y - segment.endPoint.y) <= 0.5;
}

function routeSegmentsIntersect(
  left: { startPoint: GraphPosition; endPoint: GraphPosition },
  right: { startPoint: GraphPosition; endPoint: GraphPosition },
): boolean {
  if (isVerticalSegment(left) && isHorizontalSegment(right)) {
    const x = left.startPoint.x;
    const y = right.startPoint.y;
    const verticalTop = Math.min(left.startPoint.y, left.endPoint.y);
    const verticalBottom = Math.max(left.startPoint.y, left.endPoint.y);
    const horizontalLeft = Math.min(right.startPoint.x, right.endPoint.x);
    const horizontalRight = Math.max(right.startPoint.x, right.endPoint.x);
    return x > horizontalLeft + 1 && x < horizontalRight - 1 && y > verticalTop + 1 && y < verticalBottom - 1;
  }
  if (isHorizontalSegment(left) && isVerticalSegment(right)) {
    return routeSegmentsIntersect(right, left);
  }
  return false;
}

function routesIntersect(left: LinkGraphEdge, right: LinkGraphEdge): boolean {
  return routeSegments(left).some((leftSegment) =>
    routeSegments(right).some((rightSegment) => routeSegmentsIntersect(leftSegment, rightSegment)),
  );
}

function verticalOverlapLength(
  left: { startPoint: GraphPosition; endPoint: GraphPosition },
  right: { startPoint: GraphPosition; endPoint: GraphPosition },
): number {
  if (!isVerticalSegment(left) || !isVerticalSegment(right)) {
    return 0;
  }
  if (Math.abs(left.startPoint.x - right.startPoint.x) > 0.5) {
    return 0;
  }
  const top = Math.max(
    Math.min(left.startPoint.y, left.endPoint.y),
    Math.min(right.startPoint.y, right.endPoint.y),
  );
  const bottom = Math.min(
    Math.max(left.startPoint.y, left.endPoint.y),
    Math.max(right.startPoint.y, right.endPoint.y),
  );
  return Math.max(0, bottom - top);
}

function sharedVerticalOverlap(edgeA: LinkGraphEdge, edgeB: LinkGraphEdge): number {
  return Math.max(
    0,
    ...routeSegments(edgeA).flatMap((leftSegment) =>
      routeSegments(edgeB).map((rightSegment) => verticalOverlapLength(leftSegment, rightSegment)),
    ),
  );
}

function nodeCenterX(node: LinkGraphNode): number {
  if (!node.position) {
    throw new Error("missing node position");
  }
  return node.position.x + flowchartNodeCardWidth(node) / 2;
}

function expectDecisionAttachmentMatchesBottomFlow(
  edge: LinkGraphEdge,
  decision: LinkGraphNode,
) {
  if (!decision.position) {
    throw new Error("missing decision position");
  }
  const attachment = attachmentPointInsideDecision(edge, decision);
  expect(attachment.x).toBeCloseTo(decisionCenterX(decision), 0);
  expect(attachment.y).toBeCloseTo(decision.position.y + FLOWCHART_DECISION_MIN_HEIGHT, 0);
}

function expectDecisionAttachmentMatchesTargetSide(
  edge: LinkGraphEdge,
  decision: LinkGraphNode,
  target: LinkGraphNode,
) {
  if (!decision.position || !target.position) {
    throw new Error("missing positioned nodes");
  }
  const attachment = attachmentPointInsideDecision(edge, decision);
  if (target.position.x + 1 < decision.position.x) {
    expect(attachment.x).toBeCloseTo(decision.position.x, 0);
    return;
  }
  if (target.position.x > decision.position.x + 1) {
    expect(attachment.x).toBeCloseTo(decision.position.x + FLOWCHART_DECISION_WIDTH, 0);
    return;
  }
  expect(attachment.y).toBeCloseTo(decision.position.y + FLOWCHART_DECISION_MIN_HEIGHT, 0);
}

describe("layoutFlowchartView", () => {
  it("anchors decision branches to the side of the laid-out target column instead of hard-coding TRUE left and FALSE right", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:anchor", "OrderService.submit"),
        metadata: { "flowchart.kind": "ENTRY" },
      },
      {
        ...methodNode("scope:if", "if (valid)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("action:false", "return invalid"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:true", "persistOrder()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("merge:join", "汇合"),
        type: "MERGE",
        metadata: { "flowchart.kind": "MERGE" },
      },
      {
        ...methodNode("terminal:return", "返回"),
        type: "TERMINAL",
        metadata: { "terminal.kind": "RETURN", "flowchart.kind": "TERMINAL" },
      },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "contains-entry", type: "CONTAINS_FLOW", source: "method:anchor", target: "scope:if" },
      { id: "if-false", type: "CONTROL_FLOW", source: "scope:if", target: "action:false", label: "FALSE" },
      { id: "if-true", type: "CONTROL_FLOW", source: "scope:if", target: "action:true", label: "TRUE" },
      { id: "false-merge", type: "CONTROL_FLOW", source: "action:false", target: "merge:join", label: "FALSE" },
      { id: "true-merge", type: "CONTROL_FLOW", source: "action:true", target: "merge:join", label: "TRUE" },
      { id: "merge-return", type: "CONTROL_FLOW", source: "merge:join", target: "terminal:return" },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const anchor = index.get("method:anchor")!;
    const decision = index.get("scope:if")!;
    const trueBranch = index.get("action:true")!;
    const falseBranch = index.get("action:false")!;
    const merge = index.get("merge:join")!;
    const terminal = index.get("terminal:return")!;
    const falseEdge = laidOut.edges.find((edge) => edge.id === "if-false")!;
    const trueEdge = laidOut.edges.find((edge) => edge.id === "if-true")!;

    expect(decision.position?.y).toBeGreaterThan(anchor.position?.y ?? 0);
    expect(trueBranch.position?.y).toBeGreaterThan(decision.position?.y ?? 0);
    expect(falseBranch.position?.y).toBeGreaterThan(decision.position?.y ?? 0);
    expect(falseBranch.position?.x).toBeLessThan(trueBranch.position?.x ?? Number.POSITIVE_INFINITY);
    expect(merge.position?.y).toBeGreaterThan(Math.max(trueBranch.position?.y ?? 0, falseBranch.position?.y ?? 0));
    expect(terminal.position?.y).toBeGreaterThan(merge.position?.y ?? 0);
    expectDecisionAttachmentMatchesTargetSide(falseEdge, decision, falseBranch);
    expectDecisionAttachmentMatchesTargetSide(trueEdge, decision, trueBranch);
    expect(falseEdge.route?.sections[0]?.bendPoints?.length).toBeGreaterThan(0);
  });

  it("projects decision branch anchors onto the visible diamond edge without inventing an extra exception-only handle", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("scope:if", "if (valid)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("action:false", "return invalid"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:exception", "throw invalid"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "if-false", type: "CONTROL_FLOW", source: "scope:if", target: "action:false", label: "FALSE" },
      { id: "if-exception", type: "CONTROL_FLOW", source: "scope:if", target: "action:exception", label: "EXCEPTION" },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "scope:if",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const decision = index.get("scope:if")!;
    const falseTarget = index.get("action:false")!;
    const exceptionTarget = index.get("action:exception")!;

    expectDecisionAttachmentMatchesTargetSide(laidOut.edges.find((edge) => edge.id === "if-false")!, decision, falseTarget);
    expectDecisionAttachmentMatchesTargetSide(laidOut.edges.find((edge) => edge.id === "if-exception")!, decision, exceptionTarget);
  });

  it("keeps guard-style fallthrough on the decision bottom axis instead of pushing the main flow into a side column", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:anchor", "CommonController.fileDownload"),
        metadata: { "flowchart.kind": "ENTRY" },
      },
      {
        ...methodNode("scope:guard", "if (!allowed)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("terminal:throw", "throw new Exception(...)"),
        type: "TERMINAL",
        metadata: { "terminal.kind": "THROW", "flowchart.kind": "TERMINAL" },
      },
      {
        ...methodNode("action:continue", "realFileName = ..."),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:next", "writeBytes(...)"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "entry-guard", type: "CONTAINS_FLOW", source: "method:anchor", target: "scope:guard" },
      { id: "guard-throw", type: "CONTROL_FLOW", source: "scope:guard", target: "terminal:throw", label: "TRUE" },
      { id: "guard-continue", type: "CONTROL_FLOW", source: "scope:guard", target: "action:continue", label: "FALSE" },
      { id: "continue-next", type: "CONTROL_FLOW", source: "action:continue", target: "action:next" },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const decision = index.get("scope:guard")!;
    const continueNode = index.get("action:continue")!;
    const throwNode = index.get("terminal:throw")!;
    const continueEdge = laidOut.edges.find((edge) => edge.id === "guard-continue")!;
    const throwEdge = laidOut.edges.find((edge) => edge.id === "guard-throw")!;

    expectDecisionAttachmentMatchesBottomFlow(continueEdge, decision);
    expect(Math.abs((continueNode.position?.x ?? 0) + 162 - decisionCenterX(decision))).toBeLessThanOrEqual(4);
    expectDecisionAttachmentMatchesTargetSide(throwEdge, decision, throwNode);
  });

  it("keeps single-branch if fallthrough on the decision bottom axis when the skipped branch goes straight to merge", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:anchor", "CommonController.fileDownload"),
        metadata: { "flowchart.kind": "ENTRY" },
      },
      {
        ...methodNode("scope:delete-if", "if (delete)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("action:delete", "deleteFile(filePath)"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("merge:after-delete", "汇合"),
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
      { id: "entry-delete-if", type: "CONTAINS_FLOW", source: "method:anchor", target: "scope:delete-if" },
      { id: "delete-true", type: "CONTROL_FLOW", source: "scope:delete-if", target: "action:delete", label: "TRUE" },
      { id: "delete-false", type: "CONTROL_FLOW", source: "scope:delete-if", target: "merge:after-delete", label: "FALSE" },
      { id: "delete-merge", type: "CONTROL_FLOW", source: "action:delete", target: "merge:after-delete", label: "TRUE" },
      { id: "merge-return", type: "CONTROL_FLOW", source: "merge:after-delete", target: "terminal:return" },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const decision = index.get("scope:delete-if")!;
    const merge = index.get("merge:after-delete")!;
    const falseEdge = laidOut.edges.find((edge) => edge.id === "delete-false")!;

    expectDecisionAttachmentMatchesBottomFlow(falseEdge, decision);
    expect(Math.abs((merge.position?.x ?? 0) + 66 - decisionCenterX(decision))).toBeLessThanOrEqual(4);
  });

  it("routes a manual decision link as a real third branch instead of preserving the old centered fallthrough", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:anchor", "CommonController.fileDownload"),
        metadata: { "flowchart.kind": "ENTRY" },
      },
      {
        ...methodNode("scope:delete-if", "if (delete)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("action:delete", "deleteFile(filePath)"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("invoke:delete-file", "FileUtils.deleteFile(filePath)"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "SUBROUTINE" },
        sourceTag: "DRAFT_MANUAL",
      },
      {
        ...methodNode("merge:after-delete", "汇合"),
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
      { id: "entry-delete-if", type: "CONTAINS_FLOW", source: "method:anchor", target: "scope:delete-if" },
      { id: "delete-true", type: "CONTROL_FLOW", source: "scope:delete-if", target: "action:delete", label: "TRUE" },
      { id: "delete-false", type: "CONTROL_FLOW", source: "scope:delete-if", target: "merge:after-delete", label: "FALSE" },
      {
        id: "design-link:delete-bypass",
        type: "CONTROL_FLOW",
        source: "scope:delete-if",
        target: "invoke:delete-file",
        sourceTag: "DRAFT_MANUAL",
      },
      { id: "delete-merge", type: "CONTROL_FLOW", source: "action:delete", target: "merge:after-delete", label: "TRUE" },
      { id: "merge-return", type: "CONTROL_FLOW", source: "merge:after-delete", target: "terminal:return" },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const decision = index.get("scope:delete-if")!;
    const merge = index.get("merge:after-delete")!;
    const falseEdge = laidOut.edges.find((edge) => edge.id === "delete-false")!;

    expectDecisionAttachmentMatchesTargetSide(falseEdge, decision, merge);
    expect(nodeCenterX(merge)).toBeGreaterThan(decisionCenterX(decision) + 20);
  });

  it("feeds explicit edge handles into ELK so a manual decision edge can leave from the requested side", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("scope:decision", "if (manual)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("action:right-target", "rightTarget()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
    ];
    const edges: LinkGraphEdge[] = [
      {
        id: "design-link:explicit-left",
        type: "CONTROL_FLOW",
        source: "scope:decision",
        target: "action:right-target",
        sourceHandle: "source-left",
        targetHandle: "target-top",
        sourceTag: "DRAFT_MANUAL",
      },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "scope:decision",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const decision = laidOut.nodes.find((node) => node.id === "scope:decision")!;
    const explicitEdge = laidOut.edges.find((edge) => edge.id === "design-link:explicit-left")!;
    const attachment = attachmentPointInsideDecision(explicitEdge, decision);

    expect(attachment.x).toBeLessThan(decisionCenterX(decision));
  });

  it("routes pre-test loop body and exit from explicit flow roles instead of guessing from TRUE/FALSE labels", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:anchor", "LoopService.run"),
        metadata: { "flowchart.kind": "ENTRY" },
      },
      {
        ...methodNode("scope:loop", "while (hasNext())"),
        type: "FLOW_SCOPE",
        metadata: {
          "flow.kind": "WHILE",
          "flow.scopeCategory": "LOOP_PRE_TEST",
          "flowchart.kind": "DECISION",
        },
      },
      {
        ...methodNode("action:body", "process(item)"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:after", "finish()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "entry-loop", type: "CONTROL_FLOW", source: "method:anchor", target: "scope:loop" },
      {
        id: "loop-body",
        type: "CONTROL_FLOW",
        source: "scope:loop",
        target: "action:body",
        metadata: { "flow.edgeRole": "LOOP_BODY" },
      },
      {
        id: "body-back",
        type: "CONTROL_FLOW",
        source: "action:body",
        target: "scope:loop",
        metadata: { "flow.edgeRole": "LOOP_BACK" },
      },
      {
        id: "loop-exit",
        type: "CONTROL_FLOW",
        source: "scope:loop",
        target: "action:after",
        metadata: { "flow.edgeRole": "LOOP_EXIT" },
      },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const loop = index.get("scope:loop")!;
    const body = index.get("action:body")!;
    const after = index.get("action:after")!;
    const bodyEdge = laidOut.edges.find((edge) => edge.id === "loop-body")!;
    const exitEdge = laidOut.edges.find((edge) => edge.id === "loop-exit")!;

    expectDecisionAttachmentMatchesBottomFlow(bodyEdge, loop);
    expectDecisionAttachmentMatchesTargetSide(exitEdge, loop, after);
    expect(Math.abs(nodeCenterX(body) - decisionCenterX(loop))).toBeLessThanOrEqual(4);
  });

  it("routes post-test loop exit downward and back-edge sideways from explicit flow roles", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:anchor", "LoopService.flush"),
        metadata: { "flowchart.kind": "ENTRY" },
      },
      {
        ...methodNode("action:body", "normalize(current)"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("scope:loop", "do-while (current > 0)"),
        type: "FLOW_SCOPE",
        metadata: {
          "flow.kind": "DO_WHILE",
          "flow.scopeCategory": "LOOP_POST_TEST",
          "flowchart.kind": "DECISION",
        },
      },
      {
        ...methodNode("action:after", "return current"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "entry-body", type: "CONTROL_FLOW", source: "method:anchor", target: "action:body" },
      { id: "body-guard", type: "CONTROL_FLOW", source: "action:body", target: "scope:loop" },
      {
        id: "guard-back",
        type: "CONTROL_FLOW",
        source: "scope:loop",
        target: "action:body",
        metadata: { "flow.edgeRole": "LOOP_BACK" },
      },
      {
        id: "guard-exit",
        type: "CONTROL_FLOW",
        source: "scope:loop",
        target: "action:after",
        metadata: { "flow.edgeRole": "LOOP_EXIT" },
      },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const loop = index.get("scope:loop")!;
    const body = index.get("action:body")!;
    const after = index.get("action:after")!;
    const backEdge = laidOut.edges.find((edge) => edge.id === "guard-back")!;
    const exitEdge = laidOut.edges.find((edge) => edge.id === "guard-exit")!;
    const backAttachment = attachmentPointInsideDecision(backEdge, loop);

    expectDecisionAttachmentMatchesBottomFlow(exitEdge, loop);
    expect(after.position?.y).toBeGreaterThan(loop.position?.y ?? 0);
    expect(body.position?.y).toBeLessThan(loop.position?.y ?? Number.POSITIVE_INFINITY);
    expect(backAttachment.x).not.toBeCloseTo(decisionCenterX(loop), 0);
  });

  it("declares a right-side process source port for an explicit manual edge even before the topology contains an exception branch", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("action:try-body", "tryBody()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:catch", "catchBody()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
    ];
    const edges: LinkGraphEdge[] = [
      {
        id: "design-link:explicit-process-right",
        type: "CONTROL_FLOW",
        source: "action:try-body",
        target: "action:catch",
        sourceHandle: "source-right",
        targetHandle: "target-top",
        sourceTag: "DRAFT_MANUAL",
      },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "action:try-body",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const sourceNode = laidOut.nodes.find((node) => node.id === "action:try-body")!;
    const explicitEdge = laidOut.edges.find((edge) => edge.id === "design-link:explicit-process-right")!;

    expect(explicitEdge.route?.sections[0]?.startPoint.x ?? 0).toBeGreaterThan(nodeCenterX(sourceNode));
  });

  it("keeps the main flow on a stable center column when an early throw guard is followed by a later merge-return branch", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:anchor", "CommonController.fileDownload"),
        metadata: { "flowchart.kind": "ENTRY" },
      },
      {
        ...methodNode("scope:guard", "if (!allowed)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("terminal:throw", "throw new Exception(...)"),
        type: "TERMINAL",
        metadata: { "terminal.kind": "THROW", "flowchart.kind": "TERMINAL" },
      },
      {
        ...methodNode("action:real-file-name", "realFileName = ..."),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:write-bytes", "writeBytes(...)"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("scope:delete-if", "if (delete)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("action:delete", "deleteFile(filePath)"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("merge:after-delete", "汇合"),
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
      { id: "entry-guard", type: "CONTAINS_FLOW", source: "method:anchor", target: "scope:guard" },
      { id: "guard-throw", type: "CONTROL_FLOW", source: "scope:guard", target: "terminal:throw", label: "TRUE" },
      { id: "guard-continue", type: "CONTROL_FLOW", source: "scope:guard", target: "action:real-file-name", label: "FALSE" },
      { id: "continue-write", type: "CONTROL_FLOW", source: "action:real-file-name", target: "action:write-bytes" },
      { id: "write-delete-if", type: "CONTROL_FLOW", source: "action:write-bytes", target: "scope:delete-if" },
      { id: "delete-true", type: "CONTROL_FLOW", source: "scope:delete-if", target: "action:delete", label: "TRUE" },
      { id: "delete-false", type: "CONTROL_FLOW", source: "scope:delete-if", target: "merge:after-delete", label: "FALSE" },
      { id: "delete-merge", type: "CONTROL_FLOW", source: "action:delete", target: "merge:after-delete", label: "TRUE" },
      { id: "merge-return", type: "CONTROL_FLOW", source: "merge:after-delete", target: "terminal:return" },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const guard = index.get("scope:guard")!;
    const throwNode = index.get("terminal:throw")!;
    const continueNode = index.get("action:real-file-name")!;
    const writeNode = index.get("action:write-bytes")!;
    const deleteIf = index.get("scope:delete-if")!;
    const merge = index.get("merge:after-delete")!;
    const returnNode = index.get("terminal:return")!;
    const continueEdge = laidOut.edges.find((edge) => edge.id === "guard-continue")!;

    expectDecisionAttachmentMatchesBottomFlow(continueEdge, guard);
    expect(Math.abs(nodeCenterX(continueNode) - decisionCenterX(guard))).toBeLessThanOrEqual(4);
    expect(Math.abs(nodeCenterX(writeNode) - decisionCenterX(guard))).toBeLessThanOrEqual(4);
    expect(Math.abs(decisionCenterX(deleteIf) - decisionCenterX(guard))).toBeLessThanOrEqual(4);
    expect(throwNode.position?.y).toBeLessThanOrEqual(continueNode.position?.y ?? Number.POSITIVE_INFINITY);
    expect(returnNode.position?.y).toBeGreaterThan(merge.position?.y ?? 0);
  });

  it("separates TRY normal flow and exception flow onto different outgoing stems instead of stacking them on one vertical port", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("scope:try", "try"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "TRY", "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:normal", "doWork()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:exception", "handleException()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "try-normal", type: "CONTROL_FLOW", source: "scope:try", target: "action:normal" },
      { id: "try-exception", type: "CONTROL_FLOW", source: "scope:try", target: "action:exception", label: "EXCEPTION" },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "scope:try",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const normalEdge = laidOut.edges.find((edge) => edge.id === "try-normal")!;
    const exceptionEdge = laidOut.edges.find((edge) => edge.id === "try-exception")!;

    expect(sharedVerticalOverlap(normalEdge, exceptionEdge)).toBeLessThanOrEqual(1);
  });

  it("separates long exception recovery and local true-branch merge inputs so they do not collapse into one shared right-side lane", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:anchor", "CommonController.fileDownload"),
        metadata: { "flowchart.kind": "ENTRY" },
      },
      {
        ...methodNode("scope:try", "try"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "TRY", "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:normal", "realFileName = ..."),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("scope:delete-if", "if (delete)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("action:delete", "deleteFile(filePath)"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      {
        ...methodNode("action:catch", "handleException()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
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
      { id: "entry-try", type: "CONTAINS_FLOW", source: "method:anchor", target: "scope:try" },
      { id: "try-normal", type: "CONTROL_FLOW", source: "scope:try", target: "action:normal" },
      { id: "try-exception", type: "CONTROL_FLOW", source: "scope:try", target: "action:catch", label: "EXCEPTION" },
      { id: "normal-delete-if", type: "CONTROL_FLOW", source: "action:normal", target: "scope:delete-if" },
      { id: "delete-false", type: "CONTROL_FLOW", source: "scope:delete-if", target: "merge:after", label: "FALSE" },
      { id: "delete-true", type: "CONTROL_FLOW", source: "scope:delete-if", target: "action:delete", label: "TRUE" },
      { id: "catch-merge", type: "CONTROL_FLOW", source: "action:catch", target: "merge:after", label: "EXCEPTION" },
      { id: "delete-merge", type: "CONTROL_FLOW", source: "action:delete", target: "merge:after", label: "TRUE" },
      { id: "merge-return", type: "CONTROL_FLOW", source: "merge:after", target: "terminal:return" },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const catchEdge = laidOut.edges.find((edge) => edge.id === "catch-merge")!;
    const deleteEdge = laidOut.edges.find((edge) => edge.id === "delete-merge")!;

    expect(sharedVerticalOverlap(catchEdge, deleteEdge)).toBeLessThanOrEqual(1);
  });

  it("keeps the real fileDownload try exception branch off the main stem in the runtime topology", async () => {
    const { nodes, edges } = runtimeFileDownloadTopology();

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const normalEdge = laidOut.edges.find((edge) => edge.id === "try-normal")!;
    const exceptionEdge = laidOut.edges.find((edge) => edge.id === "try-exception")!;

    expect(sharedVerticalOverlap(normalEdge, exceptionEdge)).toBeLessThanOrEqual(1);
    expect(exceptionEdge.route?.sections[0]?.startPoint.x ?? 0).toBeGreaterThan(
      (normalEdge.route?.sections[0]?.startPoint.x ?? 0) + 40,
    );
  });

  it("keeps the real fileDownload catch recovery lane separate from the delete cleanup merge lane", async () => {
    const { nodes, edges } = runtimeFileDownloadTopology();

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const catchMerge = laidOut.edges.find((edge) => edge.id === "catch-merge")!;
    const deleteMerge = laidOut.edges.find((edge) => edge.id === "delete-invoke-merge")!;

    expect(sharedVerticalOverlap(catchMerge, deleteMerge)).toBeLessThanOrEqual(1);
  });

  it("keeps the post-loop uploadFiles success chain in topological order instead of dropping the exit node below its own successors", async () => {
    const { nodes, edges } = uploadFilesLoopTopology();

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const loop = index.get("scope:foreach")!;
    const success = index.get("action:success")!;
    const putUrls = index.get("action:put-urls")!;
    const putFileNames = index.get("action:put-file-names")!;
    const putNewFileNames = index.get("action:put-new-file-names")!;

    expect(success.position?.y).toBeGreaterThan(loop.position?.y ?? 0);
    expect(putUrls.position?.y).toBeGreaterThan(success.position?.y ?? Number.POSITIVE_INFINITY);
    expect(putFileNames.position?.y).toBeGreaterThan(putUrls.position?.y ?? Number.POSITIVE_INFINITY);
    expect(putNewFileNames.position?.y).toBeGreaterThan(putFileNames.position?.y ?? Number.POSITIVE_INFINITY);
  });

  it("keeps the uploadFiles foreach back-edge in a tight local corridor instead of routing it around the whole loop column", async () => {
    const { nodes, edges } = uploadFilesLoopTopology();

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const loop = index.get("scope:foreach")!;
    const bodyTail = index.get("action:add-new-file-name")!;
    const backEdge = laidOut.edges.find((edge) => edge.id === "add-new-file-name-back")!;
    const bounds = routeBounds(backEdge);

    expect(routeTotalLength(backEdge)).toBeLessThan(
      ((bodyTail.position?.y ?? 0) - (loop.position?.y ?? 0)) + 420,
    );
    expect(bounds.maxY - bounds.minY).toBeLessThan(
      (bodyTail.position?.y ?? 0) - (loop.position?.y ?? 0) + 220,
    );
  });

  it("places invocation expansion batches in a right-side lane and routes CALL edges away from the main flow", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:caller", "Caller.run"),
        metadata: {
          "flowchart.kind": "ENTRY",
          "flow.ownerMethod": "com.example.Caller.run():void",
        },
      },
      {
        ...methodNode("invoke:create-info", "调用 SystemService.createInfo"),
        type: "FLOW_ACTION",
        signature: "com.example.SystemService.createInfo():void",
        metadata: {
          "flow.kind": "INVOCATION",
          "flowchart.kind": "SUBROUTINE",
          "flow.ownerMethod": "com.example.Caller.run():void",
        },
      },
      {
        ...methodNode("scope:after-call", "if (ok)"),
        type: "FLOW_SCOPE",
        metadata: {
          "flow.kind": "IF",
          "flowchart.kind": "DECISION",
          "flow.ownerMethod": "com.example.Caller.run():void",
        },
      },
      {
        ...methodNode("method:create-info", "SystemService.createInfo"),
        signature: "com.example.SystemService.createInfo():void",
        metadata: {
          "flowchart.kind": "ENTRY",
          "flow.ownerMethod": "com.example.SystemService.createInfo():void",
          "linkGraph.expansion.id": "invocation:1",
          "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
          "linkGraph.expansion.rootNodeId": "method:create-info",
        },
      },
      {
        ...methodNode("action:save-info", "saveInfo()"),
        type: "FLOW_ACTION",
        metadata: {
          "flow.kind": "ACTION",
          "flowchart.kind": "PROCESS",
          "flow.ownerMethod": "com.example.SystemService.createInfo():void",
          "linkGraph.expansion.id": "invocation:1",
          "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
          "linkGraph.expansion.rootNodeId": "method:create-info",
        },
      },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "caller-invoke", type: "CONTROL_FLOW", source: "method:caller", target: "invoke:create-info" },
      { id: "invoke-after", type: "CONTROL_FLOW", source: "invoke:create-info", target: "scope:after-call" },
      {
        id: "invoke-expanded",
        type: "CALL",
        source: "invoke:create-info",
        target: "method:create-info",
        metadata: {
          "linkGraph.expansion.id": "invocation:1",
          "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
        },
      },
      {
        id: "expanded-save",
        type: "CONTROL_FLOW",
        source: "method:create-info",
        target: "action:save-info",
        metadata: {
          "linkGraph.expansion.id": "invocation:1",
          "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
        },
      },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:caller",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const invocation = index.get("invoke:create-info")!;
    const afterCall = index.get("scope:after-call")!;
    const expandedRoot = index.get("method:create-info")!;
    const expandedAction = index.get("action:save-info")!;
    const callEdge = laidOut.edges.find((edge) => edge.id === "invoke-expanded")!;
    const mainFlowRight = Math.max(
      nodeBounds(index.get("method:caller")!).right,
      nodeBounds(invocation).right,
      nodeBounds(afterCall).right,
    );

    expect(expandedRoot.position?.x).toBeGreaterThan(mainFlowRight + 40);
    expect(expandedAction.position?.x).toBeGreaterThan(mainFlowRight + 40);
    expect(expandedAction.position?.y).toBeGreaterThan(expandedRoot.position?.y ?? 0);
    expect(routeIntersectsAnyNode(callEdge, [index.get("method:caller")!, invocation, afterCall, expandedRoot, expandedAction])).toBe(false);
  });

  it("routes CALL edges around same-layer branch nodes between the source invocation and expansion lane", async () => {
    const expansionMetadata = {
      "linkGraph.expansion.id": "invocation:branch",
      "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
      "linkGraph.expansion.rootNodeId": "method:create-info",
    };
    const nodes: LinkGraphNode[] = [
      { ...methodNode("method:caller", "Caller.run"), metadata: { "flowchart.kind": "ENTRY" } },
      {
        ...methodNode("scope:choose", "if (createInfo)"),
        type: "FLOW_SCOPE",
        metadata: { "flow.kind": "IF", "flowchart.kind": "DECISION" },
      },
      {
        ...methodNode("invoke:create-info", "调用 SystemService.createInfo"),
        type: "FLOW_ACTION",
        metadata: { "flow.kind": "INVOCATION", "flowchart.kind": "SUBROUTINE" },
      },
      {
        ...methodNode("action:peer-branch", "peerBranchWork()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS" },
      },
      { ...methodNode("merge:after", "汇合"), type: "MERGE", metadata: { "flowchart.kind": "MERGE" } },
      { ...methodNode("method:create-info", "SystemService.createInfo"), metadata: { "flowchart.kind": "ENTRY", ...expansionMetadata } },
      {
        ...methodNode("action:save-info", "saveInfo()"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS", ...expansionMetadata },
      },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "caller-choose", type: "CONTROL_FLOW", source: "method:caller", target: "scope:choose" },
      { id: "choose-create", type: "CONTROL_FLOW", source: "scope:choose", target: "invoke:create-info", label: "FALSE" },
      { id: "choose-peer", type: "CONTROL_FLOW", source: "scope:choose", target: "action:peer-branch", label: "TRUE" },
      { id: "create-merge", type: "CONTROL_FLOW", source: "invoke:create-info", target: "merge:after" },
      { id: "peer-merge", type: "CONTROL_FLOW", source: "action:peer-branch", target: "merge:after" },
      { id: "call-create", type: "CALL", source: "invoke:create-info", target: "method:create-info", metadata: expansionMetadata },
      { id: "create-internal", type: "CONTROL_FLOW", source: "method:create-info", target: "action:save-info", metadata: expansionMetadata },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:caller",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const invocation = index.get("invoke:create-info")!;
    const peerBranch = index.get("action:peer-branch")!;
    const callEdge = laidOut.edges.find((edge) => edge.id === "call-create")!;

    expect(peerBranch.position?.x).toBeGreaterThan(invocation.position?.x ?? Number.POSITIVE_INFINITY);
    expect(routeIntersectsAnyNode(callEdge, [peerBranch])).toBe(false);
  });

  it("keeps expansion CALL edges out of the main control-flow corridor in a wide method graph", async () => {
    const expansionMetadata = {
      "linkGraph.expansion.id": "invocation:file-check",
      "linkGraph.expansion.sourceInvocationNodeId": "invoke:check-allow-download",
      "linkGraph.expansion.rootNodeId": "method:check-allow-download",
    };
    const { nodes: baseNodes, edges: baseEdges } = runtimeFileDownloadTopology();
    const nodes: LinkGraphNode[] = [
      ...baseNodes,
      {
        ...methodNode("method:check-allow-download", "FileUtils.checkAllowDownload"),
        metadata: { "flowchart.kind": "ENTRY", ...expansionMetadata },
      },
      {
        ...methodNode("action:check-extension", "check extension allowlist"),
        type: "FLOW_ACTION",
        metadata: { "flowchart.kind": "PROCESS", ...expansionMetadata },
      },
    ];
    const edges: LinkGraphEdge[] = [
      ...baseEdges,
      {
        id: "call:check-allow-download",
        type: "CALL",
        source: "invoke:check-allow-download",
        target: "method:check-allow-download",
        metadata: expansionMetadata,
      },
      {
        id: "expanded:check-extension",
        type: "CONTROL_FLOW",
        source: "method:check-allow-download",
        target: "action:check-extension",
        metadata: expansionMetadata,
      },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const callEdge = laidOut.edges.find((edge) => edge.id === "call:check-allow-download")!;
    const mainNodes = laidOut.nodes.filter((node) => !node.metadata?.["linkGraph.expansion.id"]);
    const mainControlEdges = laidOut.edges.filter((edge) => edge.type === "CONTROL_FLOW" && !edge.metadata?.["linkGraph.expansion.id"]);
    const intersectingNodeIds = mainNodes
      .filter((node) => routeIntersectsAnyNode(callEdge, [node]))
      .map((node) => node.id);
    const intersectingEdgeIds = mainControlEdges
      .filter((edge) => routesIntersect(callEdge, edge))
      .map((edge) => edge.id);

    expect(intersectingNodeIds).toEqual([]);
    expect(intersectingEdgeIds).toEqual([]);
    expect(index.get("method:check-allow-download")?.position?.x).toBeGreaterThan(
      Math.max(...mainNodes.map((node) => nodeBounds(node).right)) + 40,
    );
  });

  it("stacks multiple invocation expansion batches by their source call order without overlapping", async () => {
    const expansionMetadata = (expansionId: string, sourceInvocationNodeId: string, rootNodeId: string) => ({
      "linkGraph.expansion.id": expansionId,
      "linkGraph.expansion.sourceInvocationNodeId": sourceInvocationNodeId,
      "linkGraph.expansion.rootNodeId": rootNodeId,
    });
    const nodes: LinkGraphNode[] = [
      { ...methodNode("method:caller", "Caller.run"), metadata: { "flowchart.kind": "ENTRY" } },
      { ...methodNode("invoke:first", "调用 first"), type: "FLOW_ACTION", metadata: { "flow.kind": "INVOCATION", "flowchart.kind": "SUBROUTINE" } },
      { ...methodNode("invoke:second", "调用 second"), type: "FLOW_ACTION", metadata: { "flow.kind": "INVOCATION", "flowchart.kind": "SUBROUTINE" } },
      { ...methodNode("method:first", "Target.first"), metadata: { "flowchart.kind": "ENTRY", ...expansionMetadata("invocation:1", "invoke:first", "method:first") } },
      { ...methodNode("action:first", "firstAction()"), type: "FLOW_ACTION", metadata: { "flowchart.kind": "PROCESS", ...expansionMetadata("invocation:1", "invoke:first", "method:first") } },
      { ...methodNode("method:second", "Target.second"), metadata: { "flowchart.kind": "ENTRY", ...expansionMetadata("invocation:2", "invoke:second", "method:second") } },
      { ...methodNode("action:second", "secondAction()"), type: "FLOW_ACTION", metadata: { "flowchart.kind": "PROCESS", ...expansionMetadata("invocation:2", "invoke:second", "method:second") } },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "caller-first", type: "CONTROL_FLOW", source: "method:caller", target: "invoke:first" },
      { id: "first-second", type: "CONTROL_FLOW", source: "invoke:first", target: "invoke:second" },
      { id: "call-first", type: "CALL", source: "invoke:first", target: "method:first", metadata: expansionMetadata("invocation:1", "invoke:first", "method:first") },
      { id: "first-internal", type: "CONTROL_FLOW", source: "method:first", target: "action:first", metadata: expansionMetadata("invocation:1", "invoke:first", "method:first") },
      { id: "call-second", type: "CALL", source: "invoke:second", target: "method:second", metadata: expansionMetadata("invocation:2", "invoke:second", "method:second") },
      { id: "second-internal", type: "CONTROL_FLOW", source: "method:second", target: "action:second", metadata: expansionMetadata("invocation:2", "invoke:second", "method:second") },
    ];

    const laidOut = await layoutFlowchartView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:caller",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const firstRoot = index.get("method:first")!;
    const firstAction = index.get("action:first")!;
    const secondRoot = index.get("method:second")!;
    const secondAction = index.get("action:second")!;
    const firstBottom = Math.max(nodeBounds(firstRoot).bottom, nodeBounds(firstAction).bottom);
    const secondTop = Math.min(nodeBounds(secondRoot).top, nodeBounds(secondAction).top);

    expect(firstRoot.position?.x).toBeCloseTo(secondRoot.position?.x ?? 0, -1);
    expect(secondTop).toBeGreaterThan(firstBottom + 40);
    expect(firstAction.position?.y).toBeGreaterThan(firstRoot.position?.y ?? 0);
    expect(secondAction.position?.y).toBeGreaterThan(secondRoot.position?.y ?? 0);
  });
});
