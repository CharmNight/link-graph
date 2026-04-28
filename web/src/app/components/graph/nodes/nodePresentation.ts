import { nodeTypeLabel, sourceTagLabel } from "../../../labels";
import { resolveFlowchartKind } from "../../../flowchartKind";
import type { LinkGraphNode } from "../../../types";

function shortDoc(value?: string): string | null {
  if (!value) {
    return null;
  }
  return value.length > 84 ? `${value.slice(0, 84)}...` : value;
}

function shortTypeName(value: string): string {
  return value
    .replace(/\bjava\.lang\./g, "")
    .replace(/\b(?:[a-z_]\w*\.)+([A-Z]\w*)/g, "$1");
}

export function flowScopeKindLabel(node: LinkGraphNode): string | null {
  if (node.type !== "FLOW_SCOPE") {
    return null;
  }
  switch (node.metadata?.["flow.kind"]) {
    case "LAMBDA":
      return "Lambda 作用域";
    case "IF":
      return "条件分支";
    case "FOREACH":
      return "For-Each 作用域";
    case "FOR":
      return "For 循环";
    case "WHILE":
      return "While 循环";
    case "DO_WHILE":
      return "Do-While 循环";
    default:
      return "流程作用域";
  }
}

function methodDisplayFromSignature(signature?: string): string | null {
  if (!signature?.trim()) {
    return null;
  }
  const source = signature.trim();
  const methodBoundary = source.indexOf("(");
  const effectiveBoundary = methodBoundary >= 0 ? methodBoundary : source.length;
  const lastDotIndex = source.lastIndexOf(".", effectiveBoundary);
  if (lastDotIndex <= 0) {
    return shortTypeName(source);
  }
  const owner = shortTypeName(source.slice(0, lastDotIndex));
  const methodName = source.slice(lastDotIndex + 1, effectiveBoundary);
  return `${owner}.${methodName}`;
}

function methodNameFromTitle(title: string): string {
  const lastDotIndex = title.lastIndexOf(".");
  return lastDotIndex >= 0 ? title.slice(lastDotIndex + 1) : title;
}

function ownerNameFromSignature(signature?: string): string | null {
  if (!signature) {
    return null;
  }
  const lastMethodDot = signature.lastIndexOf(".", signature.indexOf("("));
  if (lastMethodDot <= 0) {
    return null;
  }
  return shortTypeName(signature.slice(0, lastMethodDot));
}

function flowActionAnchorMethod(node: LinkGraphNode): string | null {
  return methodDisplayFromSignature(node.metadata?.["flow.anchorMethod"]) ?? null;
}

function flowActionText(node: LinkGraphNode): string | null {
  const actionText = node.signature?.trim() || node.title.trim();
  return actionText || null;
}

export function ownerPreview(node: LinkGraphNode): string {
  if (node.type === "TERMINAL") {
    return "流程终止";
  }
  if (node.type === "MERGE") {
    return "流程汇合";
  }
  if (node.type === "FLOW_SCOPE") {
    return node.title;
  }
  if (node.type === "FLOW_ACTION") {
    return flowActionText(node) ?? "当前方法内部动作";
  }
  if (node.type === "METHOD") {
    const lastDotIndex = node.title.lastIndexOf(".");
    if (lastDotIndex >= 0) {
      return node.title.slice(0, lastDotIndex);
    }
    return ownerNameFromSignature(node.signature) ?? node.title;
  }
  if (node.signature?.trim()) {
    return shortTypeName(node.signature);
  }
  return node.title;
}

export function signaturePreview(node: LinkGraphNode): string | null {
  if (node.type === "TERMINAL") {
    return node.metadata?.["terminal.kind"] === "RETURN" ? "返回路径结束" : nodeTypeLabel(node.type);
  }
  if (node.type === "MERGE") {
    return "多条流程在这里重新汇合";
  }
  if (node.type === "FLOW_SCOPE") {
    const kindLabel = flowScopeKindLabel(node);
    const signature = node.signature?.trim();
    if (kindLabel && signature) {
      return `${kindLabel} · ${signature}`;
    }
    return signature || kindLabel;
  }
  if (node.type === "FLOW_ACTION") {
    const anchorMethod = flowActionAnchorMethod(node);
    return anchorMethod ? `所属方法 · ${anchorMethod}` : "所属方法 · 当前方法";
  }
  if (node.type === "METHOD") {
    const outputText = node.outputs.length > 0 ? node.outputs.map(shortTypeName).join(", ") : "void";
    const previewInputs = node.inputs.map(shortTypeName);
    if (previewInputs.length === 0) {
      return `${outputText} ${methodNameFromTitle(node.title)}()`;
    }
    const compactInputText = previewInputs.length <= 2
      ? previewInputs.join(", ")
      : `${previewInputs.slice(0, 2).join(", ")}, +${previewInputs.length - 2}`;
    const compactInline = `${outputText} ${methodNameFromTitle(node.title)}(${compactInputText})`;
    if (previewInputs.length <= 2 || compactInline.length <= 76) {
      return compactInline;
    }
    return `${outputText} ${methodNameFromTitle(node.title)}(${previewInputs.length}参)`;
  }
  if (node.signature?.trim()) {
    return shortTypeName(node.signature);
  }
  if (node.inputs.length > 0 || node.outputs.length > 0) {
    return `出参 ${node.outputs.map(shortTypeName).join(", ") || "-"} | 入参 ${node.inputs.map(shortTypeName).join(", ") || "-"}`;
  }
  return null;
}

export function readingSummaryDetail(node: LinkGraphNode): string {
  return signaturePreview(node) ?? nodeTypeLabel(node.type);
}

export function hierarchyDirection(node: LinkGraphNode): "UPSTREAM" | "CURRENT" | "DOWNSTREAM" | null {
  const value = node.metadata?.["layout.direction"];
  return value === "UPSTREAM" || value === "CURRENT" || value === "DOWNSTREAM" ? value : null;
}

export function hierarchyLabel(node: LinkGraphNode): string | null {
  const sequenceLabel = node.metadata?.["layout.sequenceLabel"]?.trim() || null;
  const levelLabel = node.metadata?.["layout.levelLabel"]?.trim() || null;
  if (isFlowActionNode(node) && hierarchyDirection(node) === "CURRENT") {
    const actionSequenceLabel = sequenceLabel?.replace("调用", "动作") ?? null;
    if (actionSequenceLabel) {
      return `${actionSequenceLabel} · 当前方法内部`;
    }
    return "当前方法内部";
  }
  if (sequenceLabel && levelLabel) {
    return `${sequenceLabel} · ${levelLabel}`;
  }
  return sequenceLabel ?? levelLabel;
}

export function hierarchyDirectionLabel(node: LinkGraphNode): string | null {
  switch (hierarchyDirection(node)) {
    case "UPSTREAM":
      return "上游";
    case "CURRENT":
      return "当前";
    case "DOWNSTREAM":
      return "下游";
    default:
      return null;
  }
}

export function hasHierarchyDirections(nodes: LinkGraphNode[]): boolean {
  return nodes.some((node) => hierarchyDirection(node) !== null);
}

export function nodeTooltip(node: LinkGraphNode): string {
  return [
    `类型: ${nodeTypeLabel(node.type)}`,
    node.title,
    node.signature,
    node.doc,
    node.inputs.length > 0 ? `输入: ${node.inputs.join(", ")}` : null,
    node.outputs.length > 0 ? `输出: ${node.outputs.join(", ")}` : null,
    node.location,
  ]
    .filter((item) => Boolean(item?.trim()))
    .join("\n");
}

export function isDecisionFlowScope(node: LinkGraphNode): boolean {
  return node.type === "FLOW_SCOPE" && node.metadata?.["flow.kind"] === "IF";
}

export function isFlowActionNode(node: LinkGraphNode): boolean {
  return node.type === "FLOW_ACTION";
}

function isMethodBoundaryExplanationNode(node: LinkGraphNode): boolean {
  return node.metadata?.["linkGraph.overflow.presentation"] === "METHOD_BOUNDARY"
    || Boolean(node.metadata?.["linkGraph.boundary.kind"]);
}

export function nodeSourceBadge(node: LinkGraphNode): { text: string; title: string } | null {
  if (isMethodBoundaryExplanationNode(node)) {
    return { text: "解释", title: "系统解释节点" };
  }
  const sourceTag = node.sourceTag ?? "FACT";
  switch (sourceTag) {
    case "FACT":
      return null;
    case "DESIGN_BASELINE":
      return { text: "设计", title: sourceTagLabel(sourceTag) };
    case "DRAFT_MANUAL":
      return { text: "人工草稿", title: sourceTagLabel(sourceTag) };
    case "DRAFT_AI":
      return { text: "AI 草稿", title: sourceTagLabel(sourceTag) };
    case "UNCERTAIN_FACT":
      return { text: "待确认", title: sourceTagLabel(sourceTag) };
  }
}

function extractionOverflowCount(node: LinkGraphNode): number | null {
  const rawCount = Number(node.metadata?.["linkGraph.overflow.hiddenMethodCount"]);
  return Number.isFinite(rawCount) ? rawCount : null;
}

function projectorOverflowDirection(node: LinkGraphNode): "UPSTREAM" | "DOWNSTREAM" | null {
  const direction = node.metadata?.["linkGraph.overflow.direction"];
  return direction === "UPSTREAM" || direction === "DOWNSTREAM" ? direction : null;
}

function projectorOverflowHiddenCount(node: LinkGraphNode, key: string): number | null {
  const rawCount = Number(node.metadata?.[key]);
  return Number.isFinite(rawCount) ? rawCount : null;
}

export function overflowPresentation(node: LinkGraphNode): {
  docLine: string;
  ownerLine: string;
  signatureLine: string;
  expandable: boolean;
  expandActionLabel: string | null;
  metaLine: string;
} | null {
  const hiddenMethodCount = extractionOverflowCount(node);
  const overflowTitlePrefix = node.metadata?.["linkGraph.overflow.titlePrefix"]?.trim();
  if (hiddenMethodCount !== null && overflowTitlePrefix) {
    return {
      docLine: "提图限流摘要",
      ownerLine: overflowTitlePrefix,
      signatureLine: `当前省略 ${hiddenMethodCount} 个方法，可继续展开这一支`,
      expandable: true,
      expandActionLabel: "继续展开此分支",
      metaLine: "右键可继续展开整支链路",
    };
  }

  const direction = projectorOverflowDirection(node);
  if (direction) {
    const presentation = node.metadata?.["linkGraph.overflow.presentation"];
    const hiddenNodeCount = node.metadata?.["linkGraph.hiddenNodeCount"] ?? "?";
    const hiddenEdgeCount = node.metadata?.["linkGraph.hiddenEdgeCount"] ?? "?";
    const hiddenCurrentMethodNodeCount = projectorOverflowHiddenCount(node, "linkGraph.hidden.currentMethodNodeCount");
    const hiddenCrossMethodNodeCount = projectorOverflowHiddenCount(node, "linkGraph.hidden.crossMethodNodeCount");
    const boundaryNodeCount = projectorOverflowHiddenCount(node, "linkGraph.overflow.boundaryNodeCount");
    if (presentation === "METHOD_BOUNDARY" && boundaryNodeCount !== null && boundaryNodeCount > 0) {
      return {
        docLine: "系统解释节点",
        ownerLine: "当前方法图在方法边界停住了",
        signatureLine: `下游方法内部另有 ${boundaryNodeCount} 个节点，这些节点不属于当前方法体`,
        expandable: false,
        expandActionLabel: null,
        metaLine: "这不是代码节点；它只是在提示当前图停在方法边界。",
      };
    }
    const ownerLine = hiddenCurrentMethodNodeCount && hiddenCrossMethodNodeCount
      ? "当前方法与跨方法均有折叠"
      : hiddenCurrentMethodNodeCount
        ? "当前方法内部仍有折叠"
        : hiddenCrossMethodNodeCount
          ? "跨方法扩展仍有折叠"
          : direction === "UPSTREAM"
            ? "上游未展开"
            : "下游未展开";
    const signatureLine = hiddenCurrentMethodNodeCount || hiddenCrossMethodNodeCount
      ? [
          hiddenCurrentMethodNodeCount ? `当前方法内部省略 ${hiddenCurrentMethodNodeCount} 个节点` : null,
          hiddenCrossMethodNodeCount ? `跨方法扩展省略 ${hiddenCrossMethodNodeCount} 个节点` : null,
        ].filter((item): item is string => Boolean(item)).join("；")
      : `当前交互窗口省略 ${hiddenNodeCount} 个节点 / ${hiddenEdgeCount} 条链路`;
    return {
      docLine: "画布摘要节点",
      ownerLine,
      signatureLine,
      expandable: true,
      expandActionLabel: direction === "UPSTREAM" ? "展开更多上游" : "展开更多下游",
      metaLine: "右键可继续展开整支链路",
    };
  }

  if (node.metadata?.["linkGraph.boundary.kind"]) {
    return {
      docLine: "系统解释节点",
      ownerLine: "当前方法图在方法边界停住了",
      signatureLine: node.signature?.trim() || "静态提取在这里遇到了明确边界",
      expandable: false,
      expandActionLabel: null,
      metaLine: "这不是代码节点；它只是在提示当前图停在方法边界。",
    };
  }

  return null;
}

export function flowchartKind(node: LinkGraphNode): string {
  return resolveFlowchartKind(node);
}

export function flowchartKindLabel(node: LinkGraphNode): string {
  switch (flowchartKind(node)) {
    case "ENTRY":
      return "入口方法";
    case "DECISION":
      return "流程判断";
    case "SUBROUTINE":
      return "调用子过程";
    case "TERMINAL":
      return node.metadata?.["terminal.kind"] === "RETURN" ? "返回结束" : "流程结束";
    case "MERGE":
      return "流程汇合";
    case "SCOPE":
      return "流程作用域";
    default:
      return "处理步骤";
  }
}

export function resourceLane(node: LinkGraphNode): string {
  return node.metadata?.["resource.lane"] ?? "CODE";
}

export function resourceLaneLabel(lane: string): string {
  switch (lane) {
    case "CODE":
      return "代码主体";
    case "INTEGRATION":
      return "外部接口";
    case "DATA":
      return "数据资源";
    case "CONFIG":
      return "配置资源";
    case "DOC":
      return "文档资源";
    default:
      return "辅助资源";
  }
}

export function factNodeDocText(node: LinkGraphNode): string {
  const overflow = overflowPresentation(node);
  return overflow?.docLine
    ?? (node.type === "TERMINAL" ? "流程终止节点" : null)
    ?? (node.type === "MERGE" ? "流程汇合节点" : null)
    ?? flowScopeKindLabel(node)
    ?? (isFlowActionNode(node) ? "当前方法内部动作" : null)
    ?? shortDoc(node.doc)
    ?? "暂无注释";
}
