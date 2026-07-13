import { nodeTypeLabel, provenanceLabel } from "../../../labels";
import { resolveFlowchartKind } from "../../../flowchartKind";
import type { LinkGraphNode } from "../../../types";

/** 把文档字符串截断到 84 字符以内，超出部分以省略号结尾，方便节点卡片展示。 */
function shortDoc(value?: string): string | null {
  if (!value) {
    return null;
  }
  return value.length > 84 ? `${value.slice(0, 84)}...` : value;
}

/** 简化类型名显示：去掉 java.lang. 前缀，并将包路径折叠为简单类名。 */
function shortTypeName(value: string): string {
  return value
    .replace(/\bjava\.lang\./g, "")
    .replace(/\b(?:[a-z_]\w*\.)+([A-Z]\w*)/g, "$1");
}

/** 解析方法签名后得到的结构：参数列表和返回类型。 */
interface ParsedMethodSignature {
  parameters: string[];
  returnType: string | null;
}

/** 按顶层逗号拆分方法参数文本，泛型尖括号内的逗号会被忽略，避免拆错泛型参数。 */
function splitMethodParameters(parametersText: string): string[] {
  const parameters: string[] = [];
  let genericDepth = 0;
  let segmentStart = 0;
  for (let index = 0; index < parametersText.length; index += 1) {
    const char = parametersText[index];
    if (char === "<") {
      genericDepth += 1;
    } else if (char === ">") {
      genericDepth = Math.max(0, genericDepth - 1);
    } else if (char === "," && genericDepth === 0) {
      const segment = parametersText.slice(segmentStart, index).trim();
      if (segment) {
        parameters.push(segment);
      }
      segmentStart = index + 1;
    }
  }
  const tailSegment = parametersText.slice(segmentStart).trim();
  if (tailSegment) {
    parameters.push(tailSegment);
  }
  return parameters;
}

/** 解析方法签名字符串，正则匹配宿主.方法名(参数):返回类型，提取参数列表与返回类型。 */
function parseMethodSignature(signature?: string): ParsedMethodSignature | null {
  const normalized = signature?.trim();
  if (!normalized) {
    return null;
  }
  const match = normalized.match(/^(.*)\.([^.]+)\((.*)\)(?::(.+))?$/);
  if (!match) {
    return null;
  }
  return {
    parameters: splitMethodParameters(match[3] ?? ""),
    returnType: match[4]?.trim() || null,
  };
}

/** 根据流程作用域元数据返回中文标签，覆盖 Lambda、条件分支、各类循环等子类型。 */
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

/** 从方法签名中提取"宿主类.方法名"格式的简化显示字符串，括号后部分会被丢弃。 */
export function methodDisplayFromSignature(signature?: string): string | null {
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

/** 从形如"宿主.方法名"的标题中取最后一段作为方法名展示。 */
function methodNameFromTitle(title: string): string {
  const lastDotIndex = title.lastIndexOf(".");
  return lastDotIndex >= 0 ? title.slice(lastDotIndex + 1) : title;
}

/** 从方法签名中提取宿主类名（参数列表之前的最后一段），并简化为不带包路径的形式。 */
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

/** 读取流程动作节点所属的锚点方法（即该方法动作所在的方法）的显示字符串。 */
function flowActionAnchorMethod(node: LinkGraphNode): string | null {
  return methodDisplayFromSignature(node.metadata?.["flow.anchorMethod"]) ?? null;
}

/** 判断节点是否来自一次已打开的调用展开。 */
export function isInvocationExpansionNode(node: LinkGraphNode): boolean {
  return Boolean(node.metadata?.["linkGraph.expansion.id"]?.trim());
}

/** 读取调用展开节点对应的被调方法展示名。 */
export function invocationExpansionMethodLabel(node: LinkGraphNode): string | null {
  if (!isInvocationExpansionNode(node)) {
    return null;
  }
  return methodDisplayFromSignature(node.metadata?.["linkGraph.expansion.targetSignature"])
    ?? methodDisplayFromSignature(node.metadata?.["flow.ownerMethod"])
    ?? null;
}

/** 返回流程动作节点的展示文本，优先使用签名，否则退回到标题。 */
function flowActionText(node: LinkGraphNode): string | null {
  const actionText = node.signature?.trim() || node.title.trim();
  return actionText || null;
}

/** 计算节点卡片的"所有者/宿主"展示文本，按节点类型分别给出最合适的归属标识。 */
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

/** 生成节点签名预览：根据节点类型展示方法名+参数+返回类型、流程节点描述或入出参摘要。 */
export function signaturePreview(node: LinkGraphNode): string | null {
  const expansionMethod = invocationExpansionMethodLabel(node);
  if (expansionMethod) {
    return `展开方法 · ${expansionMethod}`;
  }
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
    const parsedSignature = parseMethodSignature(node.signature);
    const outputText = node.outputs.length > 0
      ? node.outputs.map(shortTypeName).join(", ")
      : parsedSignature?.returnType
        ? shortTypeName(parsedSignature.returnType)
        : null;
    const previewInputs = (node.inputs.length > 0 ? node.inputs : parsedSignature?.parameters ?? [])
      .map(shortTypeName);
    if (previewInputs.length === 0) {
      return outputText
        ? `${outputText} ${methodNameFromTitle(node.title)}()`
        : `${methodNameFromTitle(node.title)}()`;
    }
    const compactInputText = previewInputs.length <= 2
      ? previewInputs.join(", ")
      : `${previewInputs.slice(0, 2).join(", ")}, +${previewInputs.length - 2}`;
    const methodText = `${methodNameFromTitle(node.title)}(${compactInputText})`;
    const compactInline = outputText ? `${outputText} ${methodText}` : methodText;
    if (previewInputs.length <= 2 || compactInline.length <= 76) {
      return compactInline;
    }
    const compactMethodText = `${methodNameFromTitle(node.title)}(${previewInputs.length}参)`;
    return outputText ? `${outputText} ${compactMethodText}` : compactMethodText;
  }
  if (node.signature?.trim()) {
    return shortTypeName(node.signature);
  }
  if (node.inputs.length > 0 || node.outputs.length > 0) {
    return `出参 ${node.outputs.map(shortTypeName).join(", ") || "-"} | 入参 ${node.inputs.map(shortTypeName).join(", ") || "-"}`;
  }
  return null;
}

/** 返回节点的阅读态详细文本，优先签名预览，否则回退到节点类型中文标签。 */
export function readingSummaryDetail(node: LinkGraphNode): string {
  return signaturePreview(node) ?? nodeTypeLabel(node.type);
}

/** 读取节点在调用层级中的方向（上游/当前/下游），用于在卡片上展示调用方向标签。 */
export function hierarchyDirection(node: LinkGraphNode): "UPSTREAM" | "CURRENT" | "DOWNSTREAM" | null {
  const value = node.metadata?.["layout.direction"];
  return value === "UPSTREAM" || value === "CURRENT" || value === "DOWNSTREAM" ? value : null;
}

/** 计算节点的层级展示文本：组合调用序号标签和层级标签，对方法内部动作有特殊处理。 */
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

/** 将层级方向枚举翻译为中文短语：上游/当前/下游。 */
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

/** 判断节点集合中是否至少有一个节点携带层级方向信息，用于决定是否显示方向标记栏。 */
export function hasHierarchyDirections(nodes: LinkGraphNode[]): boolean {
  return nodes.some((node) => hierarchyDirection(node) !== null);
}

/** 拼接节点 tooltip 文本：包含类型、标题、签名、文档、入出参以及位置等，过滤空值后用换行分隔。 */
export function nodeTooltip(node: LinkGraphNode): string {
  const parsedSignature = node.type === "METHOD" ? parseMethodSignature(node.signature) : null;
  const derivedInputs = node.inputs.length === 0 ? parsedSignature?.parameters ?? [] : [];
  const derivedOutput = node.outputs.length === 0 && parsedSignature?.returnType ? parsedSignature.returnType : null;
  return [
    `类型: ${nodeTypeLabel(node.type)}`,
    node.title,
    node.signature,
    node.doc,
    node.inputs.length > 0 ? `输入: ${node.inputs.join(", ")}` : null,
    node.outputs.length > 0 ? `输出: ${node.outputs.join(", ")}` : null,
    derivedInputs.length > 0 ? `签名参数: ${derivedInputs.join(", ")}` : null,
    derivedOutput ? `签名返回: ${derivedOutput}` : null,
    node.location,
  ]
    .filter((item) => Boolean(item?.trim()))
    .join("\n");
}

/** 判断节点是否为条件分支（IF）类型的流程作用域节点。 */
export function isDecisionFlowScope(node: LinkGraphNode): boolean {
  return node.type === "FLOW_SCOPE" && node.metadata?.["flow.kind"] === "IF";
}

/** 判断节点是否为方法内部动作节点（FLOW_ACTION 类型）。 */
export function isFlowActionNode(node: LinkGraphNode): boolean {
  return node.type === "FLOW_ACTION";
}

/** 判断节点是否为方法边界的系统解释节点（携带特定元数据标记）。 */
function isMethodBoundaryExplanationNode(node: LinkGraphNode): boolean {
  return node.metadata?.["linkGraph.overflow.presentation"] === "METHOD_BOUNDARY"
    || Boolean(node.metadata?.["linkGraph.boundary.kind"]);
}

/** 计算节点来源角标文本与提示：根据来源标签返回设计/草稿/待确认等标识，事实来源不显示角标。 */
export function nodeSourceBadge(node: LinkGraphNode): { text: string; title: string } | null {
  if (isMethodBoundaryExplanationNode(node)) {
    return { text: "解释", title: "系统解释节点" };
  }
  const provenance = node.provenance ?? "CODE_ANALYSIS";
  switch (provenance) {
    case "CODE_ANALYSIS":
      return null;
    case "DESIGN_IMPORT":
      return { text: "设计", title: provenanceLabel(provenance) };
    case "USER_DRAFT":
      return { text: "人工草稿", title: provenanceLabel(provenance) };
    case "AI_DRAFT":
      return { text: "AI 草稿", title: provenanceLabel(provenance) };
    case "DERIVED":
      return { text: "待确认", title: provenanceLabel(provenance) };
  }
}

/** 从节点元数据中读取因提图限流而隐藏的方法数量，缺失或非法时返回 null。 */
function extractionOverflowCount(node: LinkGraphNode): number | null {
  const rawCount = Number(node.metadata?.["linkGraph.overflow.hiddenMethodCount"]);
  return Number.isFinite(rawCount) ? rawCount : null;
}

/** 读取节点所属的折叠方向（上游/下游），用于决定摘要节点的展开行为与文案。 */
function projectorOverflowDirection(node: LinkGraphNode): "UPSTREAM" | "DOWNSTREAM" | null {
  const direction = node.metadata?.["linkGraph.overflow.direction"];
  return direction === "UPSTREAM" || direction === "DOWNSTREAM" ? direction : null;
}

/** 按指定元数据键读取被折叠隐藏的节点数量，缺失或非法时返回 null。 */
function projectorOverflowHiddenCount(node: LinkGraphNode, key: string): number | null {
  const rawCount = Number(node.metadata?.[key]);
  return Number.isFinite(rawCount) ? rawCount : null;
}

/** 根据节点折叠/边界元数据生成溢出展示信息：包括标题、说明、是否可展开以及操作文案，供摘要节点渲染。 */
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

/** 复用通用流程图类型解析逻辑（入口/判断/子过程/终端/汇聚/作用域等）。 */
export function flowchartKind(node: LinkGraphNode): string {
  return resolveFlowchartKind(node);
}

/** 将流程图类型翻译为中文短语，用于卡片标题或徽标展示。 */
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

/** 读取节点所属的资源泳道元数据（代码主体、数据资源等），缺失时默认为代码主体。 */
export function resourceLane(node: LinkGraphNode): string {
  return node.metadata?.["resource.lane"] ?? "CODE";
}

/** 将资源泳道标识翻译为中文短语，未匹配的值统一显示为辅助资源。 */
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

/** 计算事实节点的文档展示文本：优先折叠摘要节点文案，再按类型给出默认描述，最后回退到原始 doc 或"暂无注释"。 */
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
