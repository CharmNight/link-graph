import { FifoQueue } from "./fifoQueue";
import { resolveFlowchartKind } from "./flowchartKind";
import {
  applyBootstrapEdgeRoutes,
  resolveNodePosition,
  syncNodePosition,
} from "./graphState";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  CandidateDraftChange,
  ClassDiagramViewDocument,
  DiffItem,
  DraftWorkbenchEntry,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphPatch,
  GraphPatchResult,
  GraphPosition,
  InvestigationTurnOutcome,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
} from "./types";

/**
 * 从图变更补丁中收集所有涉及的节点标识。
 * 遍历补丁里的新增/修改操作，提取节点本体、操作目标元素以及边的两端节点，
 * 去重后返回，便于上层判断哪些节点会受到这次补丁影响。
 */
export function resolveGraphPatchNodeIds(patch: GraphPatch | null | undefined): string[] {
  if (!patch) {
    return [];
  }
  const nodeIds: string[] = [];
  for (const operation of patch.operations) {
    if (operation.node?.id) {
      nodeIds.push(operation.node.id);
    }
    if (operation.elementKind === "NODE" && operation.elementId) {
      nodeIds.push(operation.elementId);
    }
    if (operation.edge?.source) {
      nodeIds.push(operation.edge.source);
    }
    if (operation.edge?.target) {
      nodeIds.push(operation.edge.target);
    }
  }
  return Array.from(new Set(nodeIds));
}

/**
 * 汇总一条草稿条目（或候选变更）所作用的节点集合。
 * 优先取补丁涉及的节点，再叠加证据链中引用到的节点；
 * 若两者皆空，则回退到条目显式声明的目标节点列表，用于驱动图谱高亮与聚焦。
 */
export function resolveDraftEntryTargetNodeIds(entry: DraftWorkbenchEntry | CandidateDraftChange | null): string[] {
  if (!entry) {
    return [];
  }
  const patchNodeIds = resolveGraphPatchNodeIds(entry.graphPatch ?? null);
  const evidenceNodeIds = (entry.evidence ?? []).flatMap(
    (finding) => finding.references.map((reference) => reference.nodeId).filter(Boolean) as string[],
  );
  const resolvedNodeIds = Array.from(new Set([...patchNodeIds, ...evidenceNodeIds]));
  if (resolvedNodeIds.length > 0) {
    return resolvedNodeIds;
  }
  return Array.from(new Set(entry.targetNodeIds));
}

/**
 * 取草稿条目作用节点列表中的首个节点作为主聚焦点。
 * 用于在没有显式锚点时，给视图提供一个默认的"主角"节点。
 */
export function resolveDraftEntryPrimaryNodeId(entry: DraftWorkbenchEntry | CandidateDraftChange | null): string | null {
  return resolveDraftEntryTargetNodeIds(entry)[0] ?? null;
}

/**
 * 解析节点归属的方法签名，用于识别流程图节点与其宿主方法的对应关系。
 * 优先使用元数据中的 flow.ownerMethod，其次回退到节点自身的 signature，
 * 用于在流程图裁剪时按"同一宿主方法"维度归集相关节点。
 */
export function resolveNodeOwnerSignature(node: LinkGraphNode | null | undefined): string | null {
  if (!node) {
    return null;
  }
  return node.metadata?.["flow.ownerMethod"]?.trim()
    || node.signature?.trim()
    || null;
}

/**
 * 以锚点节点所属的方法为边界，对整张流程图做收窄裁剪。
 * 同时保留宿主方法节点、由其触发的展开节点以及指向该方法的入口节点，
 * 让流程图聚焦于"单个方法的调用链与分支"，过滤无关噪声。
 */
export function scopeFlowchartGraphToAnchorMethod(
  graph: LinkGraphDocument,
  anchorNodeId: string | null | undefined,
): LinkGraphDocument {
  const anchorNode = graph.nodes.find((node) => node.id === anchorNodeId) ?? null;
  const anchorSignature = resolveNodeOwnerSignature(anchorNode);
  if (!anchorSignature) {
    return graph;
  }
  const ownerScopedNodes = graph.nodes.filter((node) => {
    if (node.id === anchorNodeId) {
      return true;
    }
    return resolveNodeOwnerSignature(node) === anchorSignature;
  });
  const ownerScopedNodeIds = new Set(ownerScopedNodes.map((node) => node.id));
  const ownerScopedCanonicalNodeIds = new Set(ownerScopedNodeIds);
  ownerScopedNodes.forEach((node) => {
    projectedAliasNodeIds(node).forEach((aliasNodeId) => ownerScopedCanonicalNodeIds.add(aliasNodeId));
  });
  const expansionScopedNodes = graph.nodes.filter((node) => {
    const sourceInvocationNodeId = node.metadata?.["linkGraph.expansion.sourceInvocationNodeId"]?.trim();
    return Boolean(sourceInvocationNodeId && ownerScopedCanonicalNodeIds.has(sourceInvocationNodeId));
  });
  const entryScopedNodes = graph.nodes.filter((node) => {
    if (ownerScopedNodeIds.has(node.id)) {
      return false;
    }
    if (node.metadata?.["flowchart.kind"] !== "ENTRY" && node.type !== "METHOD") {
      return false;
    }
    return graph.edges.some((edge) => edge.source === node.id && ownerScopedNodeIds.has(edge.target));
  });
  const scopedNodes = Array.from(new Map(
    [...ownerScopedNodes, ...entryScopedNodes, ...expansionScopedNodes].map((node) => [node.id, node]),
  ).values());
  if (scopedNodes.length === 0 || scopedNodes.length === graph.nodes.length) {
    return graph;
  }
  const scopedNodeIds = new Set(scopedNodes.map((node) => node.id));
  const scopedEdges = graph.edges.filter((edge) => scopedNodeIds.has(edge.source) && scopedNodeIds.has(edge.target));
  return {
    ...graph,
    nodes: scopedNodes,
    edges: scopedEdges,
  };
}

/**
 * 读取节点元数据中记录的"投影来源节点标识"列表。
 * 流程图中的合成节点会通过该字段标注其由哪些原始节点投影而来，
 * 用于在按宿主方法裁剪或匹配草稿目标时把投影节点一并纳入考量。
 */
export function projectedAliasNodeIds(node: LinkGraphNode): string[] {
  const rawAliasNodeIds = node.metadata?.["flowchart.projectedFromNodeIds"];
  if (!rawAliasNodeIds) {
    return [];
  }
  return rawAliasNodeIds
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

/**
 * 把外部传入的节点标识归一化为当前可见图中真实存在的节点 id。
 * 若请求的 id 已在图里直接命中则原样返回；
 * 否则尝试在节点的投影别名中查找，避免因投影节点替换原始节点后无法定位。
 */
export function resolveDisplayedNodeId(
  requestedNodeId: string | null | undefined,
  nodes: LinkGraphNode[],
): string | null {
  const normalizedRequestedNodeId = requestedNodeId?.trim();
  if (!normalizedRequestedNodeId) {
    return null;
  }
  if (nodes.some((node) => node.id === normalizedRequestedNodeId)) {
    return normalizedRequestedNodeId;
  }
  return nodes.find((node) => projectedAliasNodeIds(node).includes(normalizedRequestedNodeId))?.id ?? null;
}

/**
 * 把流程图中当前展示的节点与草稿/工作区版本进行字段合并。
 * 保留原节点的 id 与已有位置，叠加新版本的可展示字段与元数据，
 * 用于在"应用后"模式下即时呈现草稿带来的标题、文档等变化。
 */
function mergeFlowchartPresentationNode(
  visibleNode: LinkGraphNode,
  nextNode: LinkGraphNode,
): LinkGraphNode {
  return {
    ...visibleNode,
    ...nextNode,
    id: visibleNode.id,
    position: visibleNode.position ?? nextNode.position,
    metadata: {
      ...(visibleNode.metadata ?? {}),
      ...(nextNode.metadata ?? {}),
    },
  };
}

/**
 * 在草稿条目中查找与指定节点匹配的补丁节点，作为流程图展示版本的来源。
 * 先按节点 id 或投影别名在补丁操作中精确匹配；
 * 若未命中且节点属于回退目标范围，则用草稿的 afterState 文案构造一个标记为 DRAFT_AI 的兜底节点。
 */
function resolveFlowchartPatchNode(
  entry: DraftWorkbenchEntry,
  node: LinkGraphNode,
  fallbackTargetNodeIds: Set<string>,
): LinkGraphNode | null {
  for (const operation of entry.graphPatch?.operations ?? []) {
    if (!operation.node) {
      continue;
    }
    const patchTargetId = operation.node.id || operation.elementId;
    if (patchTargetId === node.id || projectedAliasNodeIds(node).includes(patchTargetId)) {
      return operation.node;
    }
  }
  const afterStateTitle = entry.afterState?.trim();
  const isFallbackTarget = fallbackTargetNodeIds.size === 0
    || fallbackTargetNodeIds.has(node.id)
    || projectedAliasNodeIds(node).some((aliasNodeId) => fallbackTargetNodeIds.has(aliasNodeId));
  if (!afterStateTitle || afterStateTitle === node.title.trim() || !isFallbackTarget || node.type === "METHOD") {
    return null;
  }
  return {
    ...node,
    title: afterStateTitle,
    sourceTag: "DRAFT_AI",
    metadata: {
      ...(node.metadata ?? {}),
      "draft.afterStateFallback": "true",
    },
  };
}

/**
 * 判断流程图节点在合并后是否发生了会影响展示的变化。
 * 只比较标题、文档、签名、来源标签、类型、确定度、绑定状态等用户可见字段，
 * 用于决定是否需要触发视图重渲染，避免无意义刷新。
 */
function flowchartPresentationNodeChanged(currentNode: LinkGraphNode, nextNode: LinkGraphNode): boolean {
  return currentNode.title !== nextNode.title
    || currentNode.doc !== nextNode.doc
    || currentNode.signature !== nextNode.signature
    || currentNode.sourceTag !== nextNode.sourceTag
    || currentNode.type !== nextNode.type
    || currentNode.certainty !== nextNode.certainty
    || currentNode.bindingStatus !== nextNode.bindingStatus;
}

/**
 * 在流程图视图上叠加当前草稿条目的"应用后"效果。
 * 仅在助手目标为草稿、对比模式为"应用后"且条目类型为变更时生效，
 * 把草稿补丁或工作区版本合并进可见图与全量图，让用户预览变更落地后的样子。
 */
export function overlayDraftEntryOntoFlowchartView(args: {
  view: FlowchartViewDocument;
  workingGraph: LinkGraphDocument | null;
  entry: DraftWorkbenchEntry | null;
  activeAssistantTarget: "explanation" | "qa" | "draft" | "code";
  compareMode: "after" | "compare";
}): FlowchartViewDocument {
  const {
    view,
    workingGraph,
    entry,
    activeAssistantTarget,
    compareMode,
  } = args;
  if (
    activeAssistantTarget !== "draft"
    || compareMode !== "after"
    || entry?.kind !== "CHANGE"
  ) {
    return view;
  }

  const scopedNodeIds = new Set(resolveDraftEntryTargetNodeIds(entry));
  if (scopedNodeIds.size === 0) {
    return view;
  }
  const fallbackTargetNodeIds = new Set(resolveGraphPatchNodeIds(entry.graphPatch ?? null));
  const workingNodesById = new Map((workingGraph?.nodes ?? []).map((node) => [node.id, node]));
  let changed = false;
  const overlayNode = (node: LinkGraphNode): LinkGraphNode => {
    const isTargetedVisibleNode = scopedNodeIds.has(node.id)
      || projectedAliasNodeIds(node).some((aliasNodeId) => scopedNodeIds.has(aliasNodeId));
    if (!isTargetedVisibleNode) {
      return node;
    }
    const patchNode = resolveFlowchartPatchNode(entry, node, fallbackTargetNodeIds);
    if (patchNode) {
      const mergedNode = mergeFlowchartPresentationNode(node, patchNode);
      if (flowchartPresentationNodeChanged(node, mergedNode)) {
        changed = true;
      }
      return mergedNode;
    }
    const workingNode = workingNodesById.get(node.id);
    if (workingNode) {
      const mergedNode = mergeFlowchartPresentationNode(node, workingNode);
      if (flowchartPresentationNodeChanged(node, mergedNode)) {
        changed = true;
      }
      return mergedNode;
    }
    return node;
  };

  const nextVisibleGraph = {
    ...view.visibleGraph,
    nodes: view.visibleGraph.nodes.map(overlayNode),
  };
  if (!changed) {
    return view;
  }
  return {
    ...view,
    visibleGraph: nextVisibleGraph,
    fullGraph: {
      ...view.fullGraph,
      nodes: view.fullGraph.nodes.map(overlayNode),
    },
  };
}

/**
 * 收集一条草稿条目涉及的全部宿主方法签名。
 * 既从条目作用节点反查归属方法，也合并条目显式声明的编辑作用域签名，
 * 用于在图谱上标示本次变更"触及了哪些方法"。
 */
export function resolveEntryOwnerSignatures(
  entry: DraftWorkbenchEntry | CandidateDraftChange | null,
  graph: LinkGraphDocument,
): Set<string> {
  const signatures = new Set<string>();
  const nodesById = new Map(graph.nodes.map((node) => [node.id, node]));
  for (const nodeId of resolveDraftEntryTargetNodeIds(entry)) {
    const signature = resolveNodeOwnerSignature(nodesById.get(nodeId) ?? null);
    if (signature) {
      signatures.add(signature);
    }
  }
  for (const scope of entry?.editScopes ?? []) {
    const signature = scope.symbolSignature?.trim();
    if (signature) {
      signatures.add(signature);
    }
  }
  return signatures;
}

/**
 * 选出证据条目最终指向的目标节点 id。
 * 优先使用显式声明的目标节点列表首项；若没有，则回退到证据引用里第一个带节点 id 的引用，
 * 给问答/调查流程提供一个可定位的"主要节点"。
 */
export function resolveEvidenceTargetNodeId(
  targetNodeIds: string[],
  evidence?: Array<{ references: Array<{ nodeId?: string | null }> }>,
): string | null {
  return targetNodeIds[0]
    ?? evidence?.flatMap((finding) => finding.references).find((reference) => reference.nodeId)?.nodeId
    ?? null;
}

/**
 * 从图变更结果中提取最近一轮对话的产出信息。
 * 依次尝试结果上显式记录的最新产出、近期产出列表末尾、以及问答会话内的最后一轮产出，
 * 用于在 UI 上展示"刚刚这一轮 AI 做了什么"。
 */
export function deriveLatestTurnOutcome(result: GraphPatchResult | null): InvestigationTurnOutcome | null {
  if (!result) {
    return null;
  }
  const recentTurnOutcomes = result.recentTurnOutcomes ?? [];
  const sessionTurnOutcomes = result.qaSession?.turnOutcomes ?? [];
  return result.latestTurnOutcome
    ?? recentTurnOutcomes[recentTurnOutcomes.length - 1]
    ?? sessionTurnOutcomes[sessionTurnOutcomes.length - 1]
    ?? null;
}

/**
 * 计算事实图谱视图的汇总信息。
 * 在已有汇总基础上更新锚点标题（优先取全量图、其次可见图）、可见节点数与全量节点数，
 * 供顶部状态栏等位置展示当前图谱规模与焦点。
 */
export function deriveFactGraphSummary(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId?: string | null,
  currentSummary?: FactGraphViewDocument["summary"],
) {
  return {
    ...currentSummary,
    anchorTitle: fullGraph.nodes.find((node) => node.id === anchorNodeId)?.title
      ?? visibleGraph.nodes.find((node) => node.id === anchorNodeId)?.title
      ?? null,
    visibleNodeCount: visibleGraph.nodes.length,
    fullNodeCount: fullGraph.nodes.length,
  };
}

/**
 * 计算流程图视图的汇总信息。
 * 统计节点/分支/异常路径数量，以及不完整节点、合成边等质量指标，
 * 用于反映当前流程图的规模、结构特征与数据完整度。
 */
export function deriveFlowchartSummary(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument = visibleGraph,
  currentSummary?: FlowchartViewDocument["summary"],
) {
  const incompleteNodeCount = visibleGraph.nodes.filter((node) => node.metadata?.["flow.incomplete"] === "true").length;
  const incompleteEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.incomplete"] === "true").length;
  const syntheticEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.synthetic"] === "true").length;
  const syntheticEntryEdgeCount = visibleGraph.edges.filter(
    (edge) => edge.metadata?.["flow.synthetic"] === "true" && edge.metadata?.["flow.provenance"] === "SYNTHETIC_PROJECTION",
  ).length;
  return {
    ...currentSummary,
    nodeCount: visibleGraph.nodes.length,
    branchCount: visibleGraph.nodes.filter((node) => resolveFlowchartKind(node) === "DECISION").length,
    exceptionPathCount: visibleGraph.edges.filter((edge) => edge.label?.trim().toUpperCase() === "EXCEPTION").length,
    fullNodeCount: fullGraph.nodes.length,
    fullEdgeCount: fullGraph.edges.length,
    incompleteNodeCount,
    incompleteEdgeCount,
    semanticallyIncomplete: incompleteNodeCount > 0 || incompleteEdgeCount > 0,
    syntheticEdgeCount,
    syntheticEntryEdgeCount,
  };
}

/**
 * 计算资源关系视图的汇总信息。
 * 统计资源单元数量、绑定关系数量，并按泳道（如代码、数据库、消息）分组计数；
 * 在没有关系时给出可读的缺数据原因，便于 UI 提示用户为何图是空的。
 */
export function deriveResourceRelationSummary(visibleGraph: LinkGraphDocument) {
  const resourceCount = visibleGraph.nodes.filter(isResourceRelationNode).length;
  return {
    visibleNodeCount: visibleGraph.nodes.length,
    relationCount: visibleGraph.edges.length,
    resourceCount,
    fallbackReason: visibleGraph.edges.length > 0
      ? "NONE"
      : resourceCount === 0
        ? "NO_RESOURCE_UNITS"
        : "NO_BINDING_RELATIONS",
    laneCounts: visibleGraph.nodes.reduce<Record<string, number>>((counts, node) => {
      const lane = node.metadata?.["resource.lane"] ?? "CODE";
      counts[lane] = (counts[lane] ?? 0) + 1;
      return counts;
    }, {}),
  };
}

/**
 * 判断节点是否属于资源关系视图所关注的"资源类"节点。
 * 通过元数据中的资源泳道字段或节点类型（SQL、HTTP 端点、消息主题、配置项等）来识别，
 * 用于在汇总资源数量时过滤出真正的资源单元。
 */
function isResourceRelationNode(node: LinkGraphNode): boolean {
  return node.metadata?.["resource.lane"] != null ||
    node.type.includes("RESOURCE") ||
    ["SQL", "HTTP_ENDPOINT", "MQ_TOPIC", "CONFIG_ITEM"].includes(node.type);
}

/**
 * 计算架构图谱视图的汇总信息。
 * 按节点类型分别统计模块、包、服务、组件、资源、层级、库以及 JDK 节点的数量，
 * 并累加各模块声明的类数，呈现当前架构视图的组成与规模。
 */
export function deriveArchitectureGraphSummary(visibleGraph: LinkGraphDocument) {
  return {
    moduleCount: visibleGraph.nodes.filter((node) => node.type === "MODULE").length,
    packageCount: visibleGraph.nodes.filter((node) => node.type === "PACKAGE").length,
    serviceCount: visibleGraph.nodes.filter((node) => node.type === "SERVICE").length,
    componentCount: visibleGraph.nodes.filter((node) => node.type === "COMPONENT").length,
    resourceCount: visibleGraph.nodes.filter((node) => node.type === "RESOURCE").length,
    layerCount: visibleGraph.nodes.filter((node) => node.type === "LAYER").length,
    libraryCount: visibleGraph.nodes.filter((node) => node.type === "LIBRARY").length,
    jdkCount: visibleGraph.nodes.filter((node) => node.metadata?.["architecture.node.kind"] === "JDK").length,
    relationCount: visibleGraph.edges.length,
    classCount: visibleGraph.nodes
      .map((node) => Number(node.metadata?.["architecture.classCount"] ?? "0"))
      .filter(Number.isFinite)
      .reduce((sum, count) => sum + count, 0),
    truncated: visibleGraph.truncated === true,
  };
}

/**
 * 计算类图视图的汇总信息。
 * 统计类、接口、枚举、注解、记录等类型节点数量并累加字段总数；
 * 同时填充锚点类型、邻域规模等元信息，供类图状态栏与范围说明使用。
 */
export function deriveClassDiagramSummary(visibleGraph: LinkGraphDocument) {
  return {
    classCount: visibleGraph.nodes.filter((node) => node.type === "CLASS").length,
    fieldCount: visibleGraph.nodes
      .map((node) => Number(node.metadata?.["uml.field.count"] ?? "0"))
      .filter(Number.isFinite)
      .reduce((sum, count) => sum + count, 0),
    interfaceCount: visibleGraph.nodes.filter((node) => node.type === "INTERFACE").length,
    enumCount: visibleGraph.nodes.filter((node) => node.type === "ENUM").length,
    annotationCount: visibleGraph.nodes.filter((node) => node.type === "ANNOTATION").length,
    recordCount: visibleGraph.nodes.filter((node) => node.type === "RECORD").length,
    objectCount: visibleGraph.nodes.filter((node) => node.type === "OBJECT").length,
    relationCount: visibleGraph.edges.length,
    spiProviderCount: 0,
    reflectionRelationCount: 0,
    relationCompleteness: "COMPLETE",
    scopeTypeCount: visibleGraph.nodes.length,
    projectTypeCount: visibleGraph.nodes.length,
    projectClassCount: visibleGraph.nodes.filter((node) => node.type === "CLASS").length,
    scopeBasis: "CLASS_NEIGHBORHOOD",
    anchorTypeNodeId: visibleGraph.nodes[0]?.id ?? null,
    anchorTypeTitle: visibleGraph.nodes[0]?.title ?? null,
    anchorTypeQualifiedName: visibleGraph.nodes[0]?.signature ?? null,
    neighborhoodLimit: visibleGraph.nodes.length,
    memberLimit: 5,
    neighborhoodCandidateTypeCount: visibleGraph.nodes.length,
    neighborhoodTruncated: false,
  };
}

/**
 * 从方法或类型的完整签名中截取其所在的包路径。
 * 先去掉方法参数部分，再以最后一个点号切分，得到包名，
 * 用于在评审图谱汇总中按包维度统计受影响的范围。
 */
function packageFromSignature(signature?: string | null): string | null {
  if (!signature) {
    return null;
  }
  const owner = signature.split("(")[0] ?? signature;
  const index = owner.lastIndexOf(".");
  return index > 0 ? owner.slice(0, index) : null;
}

/**
 * 计算变更评审图谱视图的汇总信息。
 * 按 review.role 统计变更、上游、下游、相关测试节点数量，
 * 并按包/模块维度聚合受影响范围，辅以各类节点的容量上限，反映评审视图的覆盖与裁剪情况。
 */
export function deriveReviewGraphSummary(visibleGraph: LinkGraphDocument) {
  return {
    changedSymbolCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "CHANGED").length,
    upstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "UPSTREAM").length,
    downstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "DOWNSTREAM").length,
    relatedTestCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "RELATED_TEST").length,
    affectedPackageCount: Array.from(new Set(visibleGraph.nodes
      .map((node) => node.metadata?.["architecture.package"] ?? packageFromSignature(node.signature))
      .filter(Boolean))).length,
    affectedModuleCount: Array.from(new Set(visibleGraph.nodes
      .map((node) => node.metadata?.["architecture.module"])
      .filter(Boolean))).length,
    evidenceRefCount: visibleGraph.edges.filter((edge) => edge.metadata?.["review.edgeRole"] === "RELATION").length,
    truncated: Boolean(visibleGraph.truncated),
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
    selectedDiffItemIds: [],
    maxChangedNodes: 120,
    maxUpstreamNodes: 40,
    maxDownstreamNodes: 40,
    maxRelatedTestNodes: 40,
  };
}

/**
 * 选定图谱的锚点节点 id。
 * 若用户指定的偏好节点存在于图中则直接采用；
 * 否则优先挑选方法类型节点作为分析焦点，再退化为首个节点，保证视图始终有一个聚焦对象。
 */
export function resolveAnchorNodeId(
  nodes: LinkGraphNode[],
  preferredNodeId?: string | null,
): string | null {
  if (preferredNodeId && nodes.some((node) => node.id === preferredNodeId)) {
    return preferredNodeId;
  }
  return nodes.find((node) => node.type === "METHOD")?.id ?? nodes[0]?.id ?? null;
}

/**
 * 判断是否应当重置图谱的锚点节点。
 * 当工作区图谱发生变更，或最近一次引导消息为加载图谱/工作区变更时，
 * 视为上下文已被替换，需要重新选择锚点而非沿用旧值。
 */
export function shouldResetAnchorNode(
  state: LinkGraphBootstrapState,
  workspaceGraphChanged: boolean,
): boolean {
  if (workspaceGraphChanged) {
    return true;
  }
  return state.lastMessageType === "loadGraph" || state.lastMessageType === "workspaceGraphChanged";
}

/**
 * 把新一轮可见图合并进事实图谱的全量图缓存。
 * 用新可见节点替换同 id 的旧全量节点、追加全新节点，并清理在新可见图中已消失的节点与受牵连的边，
 * 让全量图始终保持"曾经展开过的完整集合"而避免重复加载。
 */
function mergeVisibleGraphIntoFactFullGraph(
  currentView: FactGraphViewDocument,
  nextVisibleGraph: LinkGraphDocument,
): LinkGraphDocument {
  const nextVisibleNodeById = new Map(nextVisibleGraph.nodes.map((node) => [node.id, node]));
  const nextVisibleEdgeById = new Map(nextVisibleGraph.edges.map((edge) => [edge.id, edge]));
  const currentFullNodeIds = new Set(currentView.fullGraph.nodes.map((node) => node.id));
  const currentFullEdgeIds = new Set(currentView.fullGraph.edges.map((edge) => edge.id));
  const removedVisibleNodeIds = new Set(
    currentView.visibleGraph.nodes
      .filter((node) => !nextVisibleNodeById.has(node.id))
      .map((node) => node.id),
  );
  const removedVisibleEdgeIds = new Set(
    currentView.visibleGraph.edges
      .filter((edge) => !nextVisibleEdgeById.has(edge.id))
      .map((edge) => edge.id),
  );

  const mergedNodes = currentView.fullGraph.nodes
    .filter((node) => !removedVisibleNodeIds.has(node.id))
    .map((node) => nextVisibleNodeById.get(node.id) ?? node);
  nextVisibleGraph.nodes.forEach((node) => {
    if (!currentFullNodeIds.has(node.id)) {
      mergedNodes.push(node);
    }
  });

  const mergedEdges = currentView.fullGraph.edges
    .filter((edge) =>
      !removedVisibleEdgeIds.has(edge.id)
      && !removedVisibleNodeIds.has(edge.source)
      && !removedVisibleNodeIds.has(edge.target),
    )
    .map((edge) => nextVisibleEdgeById.get(edge.id) ?? edge)
    .filter((edge) => !removedVisibleNodeIds.has(edge.source) && !removedVisibleNodeIds.has(edge.target));
  nextVisibleGraph.edges.forEach((edge) => {
    if (!currentFullEdgeIds.has(edge.id)) {
      mergedEdges.push(edge);
    }
  });

  return {
    ...currentView.fullGraph,
    nodes: mergedNodes,
    edges: mergedEdges,
  };
}

/**
 * 用新的可见图同步事实图谱视图文档。
 * 合并产生新的全量图、更新锚点并重算汇总信息，
 * 返回一个结构上完整刷新的视图对象供渲染层消费。
 */
export function syncFactGraphViewDocument(
  currentView: FactGraphViewDocument,
  nextVisibleGraph: LinkGraphDocument,
  nextAnchorNodeId: string | null,
): FactGraphViewDocument {
  const fullGraph = mergeVisibleGraphIntoFactFullGraph(currentView, nextVisibleGraph);
  return {
    ...currentView,
    visibleGraph: nextVisibleGraph,
    fullGraph,
    anchorNodeId: nextAnchorNodeId,
    summary: deriveFactGraphSummary(nextVisibleGraph, fullGraph, nextAnchorNodeId, currentView.summary),
  };
}

/**
 * 把上一份图谱中已经手工调整过的边走向迁移到新图谱上。
 * 避免每次重新加载图谱后用户之前调整的连线样式丢失，保持视觉连续性。
 */
export function applyBootstrapRoutesToDocument(
  nextDocument: LinkGraphDocument,
  currentDocument: LinkGraphDocument,
): LinkGraphDocument {
  const nextEdges = applyBootstrapEdgeRoutes(nextDocument.edges, currentDocument.edges);
  return nextEdges === nextDocument.edges
    ? nextDocument
    : {
        ...nextDocument,
        edges: nextEdges,
      };
}

/**
 * 对同时持有可见图与全量图的视图文档批量迁移边走向。
 * 泛型约束保证任何具备 visibleGraph/fullGraph 结构的视图都能复用此能力，
 * 内部对两张图分别调用单文档迁移逻辑。
 */
export function applyBootstrapRoutesToViewDocument<
  T extends {
    visibleGraph: LinkGraphDocument;
    fullGraph: LinkGraphDocument;
  },
>(
  nextView: T,
  currentView: T,
): T {
  return {
    ...nextView,
    visibleGraph: applyBootstrapRoutesToDocument(nextView.visibleGraph, currentView.visibleGraph),
    fullGraph: applyBootstrapRoutesToDocument(nextView.fullGraph, currentView.fullGraph),
  };
}

/**
 * 在新旧视图节点/边 id 完全一致时，复用旧视图的图对象引用。
 * 当 reuseCurrentGraphs 打开且元素集合未变化时，直接沿用旧图以保留布局等副作用，
 * 避免不必要的对象重建导致的 React Flow 状态丢失。
 */
export function reuseCurrentViewGraphs<
  T extends {
    visibleGraph: LinkGraphDocument;
    fullGraph: LinkGraphDocument;
  },
>(
  nextView: T,
  currentView: T,
  reuseCurrentGraphs: boolean,
): T {
  if (
    !reuseCurrentGraphs ||
    !haveSameGraphElementIds(nextView.visibleGraph, currentView.visibleGraph) ||
    !haveSameGraphElementIds(nextView.fullGraph, currentView.fullGraph)
  ) {
    return nextView;
  }
  if (nextView.visibleGraph === currentView.visibleGraph && nextView.fullGraph === currentView.fullGraph) {
    return nextView;
  }
  return {
    ...nextView,
    visibleGraph: currentView.visibleGraph,
    fullGraph: currentView.fullGraph,
  };
}

/**
 * 判断两张图谱的节点与边 id 集合是否完全一致。
 * 用于决定是否可以安全复用旧图对象而不丢失任何元素。
 */
function haveSameGraphElementIds(left: LinkGraphDocument, right: LinkGraphDocument): boolean {
  return haveSameIds(left.nodes.map((node) => node.id), right.nodes.map((node) => node.id))
    && haveSameIds(left.edges.map((edge) => edge.id), right.edges.map((edge) => edge.id));
}

/**
 * 比较两组标识在数量与成员上是否一致（无视顺序）。
 * 作为图元素集合相等性判断的基础工具。
 */
function haveSameIds(leftIds: string[], rightIds: string[]): boolean {
  if (leftIds.length !== rightIds.length) {
    return false;
  }
  const rightIdSet = new Set(rightIds);
  return leftIds.every((id) => rightIdSet.has(id));
}

/**
 * 把一批节点位置更新应用到一个图文档上。
 * 仅对位置真正发生变化的节点执行同步，若没有任何变化则原样返回图对象，
 * 避免产生无意义的引用变更触发上层重渲染。
 */
export function applyLayoutUpdatesToGraphDocument(
  currentGraph: LinkGraphDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): LinkGraphDocument {
  if (updates.length === 0 || currentGraph.nodes.length === 0) {
    return currentGraph;
  }
  const updateMap = new Map(updates.map((update) => [update.id, update.position]));
  let changed = false;
  const nextNodes = currentGraph.nodes.map((node) => {
    const nextPosition = updateMap.get(node.id);
    if (!nextPosition) {
      return node;
    }
    const currentPosition = resolveNodePosition(node);
    if (currentPosition?.x === nextPosition.x && currentPosition?.y === nextPosition.y) {
      return node;
    }
    changed = true;
    return syncNodePosition(node, nextPosition);
  });
  return changed
    ? {
        ...currentGraph,
        nodes: nextNodes,
        edges: currentGraph.edges,
      }
    : currentGraph;
}

/**
 * 把布局更新同步到流程图视图的可见图与全量图，并重算汇总。
 * 用于用户拖拽节点后持久化新位置，同时刷新顶部统计。
 */
export function syncFlowchartViewLayout(
  currentView: FlowchartViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): FlowchartViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: deriveFlowchartSummary(visibleGraph, fullGraph, currentView.summary),
  };
}

/**
 * 把布局更新同步到资源关系视图的可见图与全量图，并重算资源关系汇总。
 * 用于用户在资源关系画布上调整节点位置后持久化结果。
 */
export function syncResourceRelationViewLayout(
  currentView: ResourceRelationViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): ResourceRelationViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: deriveResourceRelationSummary(visibleGraph),
  };
}

/**
 * 把布局更新同步到架构图谱视图的可见图与全量图。
 * 该视图汇总信息与布局无关，因此保留原 summary 不做重算。
 */
export function syncArchitectureGraphViewLayout(
  currentView: ArchitectureGraphViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): ArchitectureGraphViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: currentView.summary,
  };
}

/**
 * 把布局更新同步到类图视图的可见图与全量图。
 * 类图汇总与节点位置无关，故直接保留原 summary。
 */
export function syncClassDiagramViewLayout(
  currentView: ClassDiagramViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): ClassDiagramViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: currentView.summary,
  };
}

/**
 * 把布局更新同步到评审图谱视图的可见图与全量图。
 * 评审汇总与布局无关，保留原 summary。
 */
export function syncReviewGraphViewLayout(
  currentView: ReviewGraphViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): ReviewGraphViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: currentView.summary,
  };
}

/**
 * 计算折叠一组节点后实际被隐藏的后代集合与每个折叠节点隐藏的后代数量。
 * 沿出边广度遍历，统计直接与间接子节点，并叠加节点元数据中声明的"溢出隐藏"计数，
 * 用于在折叠态下显示"该节点折叠了 N 个下游"的提示。
 */
export function resolveCollapsedDescendantSummary(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
  collapsedNodeIds: string[],
): {
  hiddenNodeIds: Set<string>;
  descendantCountByNodeId: Record<string, number>;
} {
  if (collapsedNodeIds.length === 0) {
    return {
      hiddenNodeIds: new Set(),
      descendantCountByNodeId: {},
    };
  }
  const outgoingEdgeMap = new Map<string, string[]>();
  edges.forEach((edge) => {
    const nextTargets = outgoingEdgeMap.get(edge.source) ?? [];
    nextTargets.push(edge.target);
    outgoingEdgeMap.set(edge.source, nextTargets);
  });
  const nodeById = new Map(nodes.map((node) => [node.id, node]));

  const hiddenNodeIds = new Set<string>();
  const descendantCountByNodeId: Record<string, number> = {};
  collapsedNodeIds.forEach((collapsedNodeId) => {
    const descendants = new Set<string>();
    let overflowHiddenCount = 0;
    const queue = new FifoQueue([...(outgoingEdgeMap.get(collapsedNodeId) ?? [])]);
    while (true) {
      const nextNodeId = queue.dequeue();
      if (!nextNodeId) {
        break;
      }
      if (descendants.has(nextNodeId) || nextNodeId === collapsedNodeId) {
        continue;
      }
      descendants.add(nextNodeId);
      hiddenNodeIds.add(nextNodeId);
      const overflowCount = Number(
        nodeById.get(nextNodeId)?.metadata?.["linkGraph.overflow.hiddenMethodCount"]
          ?? nodeById.get(nextNodeId)?.metadata?.["linkGraph.hiddenNodeCount"],
      );
      if (Number.isFinite(overflowCount) && overflowCount > 0) {
        overflowHiddenCount += overflowCount;
      }
      queue.enqueue(...(outgoingEdgeMap.get(nextNodeId) ?? []));
    }
    descendantCountByNodeId[collapsedNodeId] = descendants.size + overflowHiddenCount;
  });
  return {
    hiddenNodeIds,
    descendantCountByNodeId,
  };
}

/**
 * 确定问答会话当前要聚焦的目标节点集合。
 * 有显式目标节点时只关注它；没有时仅在用户多选了节点的情况下才把整组纳入，
 * 避免在没有明确焦点时误把整个画布作为问答上下文。
 */
export function resolveQaTargetNodeIds(
  targetNodeId: string | undefined,
  selectionGroupNodeIds: string[],
): string[] {
  if (targetNodeId) {
    return [targetNodeId];
  }
  return selectionGroupNodeIds.length > 1 ? selectionGroupNodeIds : [];
}
