import { startTransition, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { publishLayoutChange } from "../api";
import { measureDuration, measureStart, traceLinkGraph } from "../debug";
import { extractLayoutState } from "../graphState";
import { canEditProjectedEdge, canEditProjectedNode } from "../graphProjectionPermissions";
import { canEditNodeLayout } from "../layoutEditability";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphPosition,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphLayoutState,
  LinkGraphNode,
  OperationFeedback,
  GraphProjectionIndex,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
} from "../types";

/**
 * 画布控制器 hook 的入参集合，承接来自宿主组件的图谱状态、各类视图的 setter，
 * 以及用于跨视图/文档同步布局的纯函数工具。控制器只负责响应画布上的用户交互
 * 并驱动这些 setter 完成节点增删改、布局同步等操作，不直接持有 React 状态。
 */
interface UseGraphCanvasControllerArgs {
  /** 当前画布上渲染的全部节点列表。 */
  nodes: LinkGraphNode[];
  /** 当前画布上渲染的全部边列表。 */
  edges: LinkGraphEdge[];
  /** 当前处于选中状态的节点 id，画布交互会在删除时据此清空选中。 */
  selectedNodeId: string | null;
  /** 当前在详情面板/抽屉中展开查看的节点 id。 */
  detailNodeId: string | null;
  /** 当前分析视图的模式，用于决定创建边时的类型与布局同步策略。 */
  analysisDisplayMode: AnalysisDisplayMode;
  /** 当前图谱投影索引，存在时用于校验节点/边是否允许手动编辑。 */
  projectionIndex?: GraphProjectionIndex | null;
  /** 已折叠子树的节点 id 列表，用于在折叠/展开时联动选择与详情状态。 */
  collapsedNodeIds: string[];
  /** 自增计数 ref，为手动新建的节点生成稳定且不冲突的序号。 */
  nextManualNodeIdRef: MutableRefObject<number>;
  /** 锚点节点 id ref，用于在事实图谱视图中确定聚焦参考点。 */
  anchorNodeIdRef: MutableRefObject<string | null>;
  /** 更新节点列表的 React 状态 setter。 */
  setNodes: Dispatch<SetStateAction<LinkGraphNode[]>>;
  /** 更新场景布局状态的 setter，记录各节点的坐标快照。 */
  setSceneLayoutState: Dispatch<SetStateAction<LinkGraphLayoutState>>;
  /** 更新草稿图谱文档的 setter，用于把布局变动回写到文档层。 */
  setDraftGraph: Dispatch<SetStateAction<LinkGraphDocument | null>>;
  /** 更新事实图谱视图文档的 setter。 */
  setFactGraphView: Dispatch<SetStateAction<FactGraphViewDocument>>;
  /** 更新流程图视图文档的 setter。 */
  setFlowchartView: Dispatch<SetStateAction<FlowchartViewDocument>>;
  /** 更新资源关系视图文档的 setter。 */
  setResourceRelationView: Dispatch<SetStateAction<ResourceRelationViewDocument>>;
  /** 更新架构图视图文档的 setter。 */
  setArchitectureGraphView: Dispatch<SetStateAction<ArchitectureGraphViewDocument>>;
  /** 更新类图视图文档的 setter。 */
  setClassDiagramView: Dispatch<SetStateAction<ClassDiagramViewDocument>>;
  /** 更新评审图视图文档的 setter。 */
  setReviewGraphView: Dispatch<SetStateAction<ReviewGraphViewDocument>>;
  /** 更新折叠节点 id 列表的 setter。 */
  setCollapsedNodeIds: Dispatch<SetStateAction<string[]>>;
  /** 更新选择分组节点 id 列表的 setter，需在节点被折叠隐藏时清理。 */
  setSelectionGroupNodeIds: Dispatch<SetStateAction<string[]>>;
  /** 更新 QA 目标节点 id 列表的 setter，需在节点被折叠隐藏时清理。 */
  setQaTargetNodeIds: Dispatch<SetStateAction<string[]>>;
  /** 更新当前选中节点 id 的 setter。 */
  setSelectedNodeId: Dispatch<SetStateAction<string | null>>;
  /** 更新当前详情节点 id 的 setter。 */
  setDetailNodeId: Dispatch<SetStateAction<string | null>>;
  /** 更新操作反馈信息的 setter，用于向用户提示操作结果。 */
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  /**
   * 将最新节点/边列表同步到画布并触发重排或刷新；可选强制重排，也可顺带更新选中节点。
   */
  syncGraph: (
    nextNodes: LinkGraphNode[],
    nextEdges: LinkGraphEdge[],
    nextSelectedNodeId?: string | null,
    options?: { forceRelayout?: boolean },
  ) => void;
  /** 根据序号计算新建节点在画布上的兜底摆放坐标，避免节点叠加在原点。 */
  fallbackDesignPosition: (index: number) => GraphPosition;
  /** 解析节点当前应有的渲染坐标，处理元数据与显式字段之间的差异。 */
  resolveNodePosition: (node: LinkGraphNode) => GraphPosition | null;
  /** 在保留节点既有元数据的前提下，把新坐标同步写回节点对象。 */
  syncNodePosition: (node: LinkGraphNode, position: GraphPosition) => LinkGraphNode;
  /** 计算某节点下游全部子树节点的 id 集合，用于整子树删除时一并清理。 */
  collectDownstreamSubtreeNodeIds: (nodeId: string, nodes: LinkGraphNode[], edges: LinkGraphEdge[]) => Set<string>;
  /** 把布局更新项应用到图谱文档，返回新的文档对象，保证不可变性。 */
  applyLayoutUpdatesToGraphDocument: (
    graph: LinkGraphDocument,
    updates: Array<{ id: string; position: GraphPosition }>,
  ) => LinkGraphDocument;
  /** 同步事实图谱视图文档：根据节点坐标与锚点重新计算视图状态。 */
  syncFactGraphViewDocument: (
    current: FactGraphViewDocument,
    graph: LinkGraphDocument,
    anchorNodeId: string | null,
  ) => FactGraphViewDocument;
  /** 将布局更新应用到流程图视图文档。 */
  syncFlowchartViewLayout: (
    current: FlowchartViewDocument,
    updates: Array<{ id: string; position: GraphPosition }>,
  ) => FlowchartViewDocument;
  /** 将布局更新应用到资源关系视图文档。 */
  syncResourceRelationViewLayout: (
    current: ResourceRelationViewDocument,
    updates: Array<{ id: string; position: GraphPosition }>,
  ) => ResourceRelationViewDocument;
  /** 将布局更新应用到架构图视图文档。 */
  syncArchitectureGraphViewLayout: (
    current: ArchitectureGraphViewDocument,
    updates: Array<{ id: string; position: GraphPosition }>,
  ) => ArchitectureGraphViewDocument;
  /** 将布局更新应用到类图视图文档。 */
  syncClassDiagramViewLayout: (
    current: ClassDiagramViewDocument,
    updates: Array<{ id: string; position: GraphPosition }>,
  ) => ClassDiagramViewDocument;
  /** 将布局更新应用到评审图视图文档。 */
  syncReviewGraphViewLayout: (
    current: ReviewGraphViewDocument,
    updates: Array<{ id: string; position: GraphPosition }>,
  ) => ReviewGraphViewDocument;
  /** 在折叠节点集合下汇总被隐藏的子孙节点 id 与计数，供联动选择/详情状态使用。 */
  resolveCollapsedDescendantSummary: (
    nodes: LinkGraphNode[],
    edges: LinkGraphEdge[],
    collapsedNodeIds: string[],
  ) => { hiddenNodeIds: Set<string>; descendantCountByNodeId: Record<string, number> };
}

/**
 * 画布控制器 hook：集中处理图谱画布上的节点/边交互（新增、删除、子树删除、连线、
 * 拖动、折叠、整理布局等），并驱动宿主组件传入的状态 setter 与视图同步函数，
 * 让画布、文档、各类视图保持一致。返回一组事件处理函数供画布组件挂载使用。
 */
export function useGraphCanvasController(args: UseGraphCanvasControllerArgs) {
  /**
   * 判断当前图谱投影下是否允许对指定节点执行某类编辑命令；当没有投影索引时，
   * 视为不受限制（手动草图场景）。
   */
  function canRunNodeCommand(
    nodeId: string,
    command: "ADD_NODE" | "UPDATE_NODE" | "DELETE_NODE" | "DELETE_NODE_SUBTREE" | "CONNECT_NODES",
  ) {
    return args.projectionIndex ? canEditProjectedNode(args.projectionIndex, nodeId, command) : true;
  }

  /**
   * 判断当前图谱投影下是否允许对指定边执行删除/插入节点命令；同样在没有投影索引时放行。
   */
  function canRunEdgeCommand(edgeId: string, command: "DELETE_EDGE" | "INSERT_NODE_INTO_EDGE") {
    return args.projectionIndex ? canEditProjectedEdge(args.projectionIndex, edgeId, command) : true;
  }

  /**
   * 构造一个手动新增的节点对象。根据类型分别生成方法节点（草稿）或文档说明页节点，
   * 默认带上手动来源标记与坐标元数据，便于后续持久化与回写坐标。
   */
  function buildManualNode(
    kind: "METHOD" | "DOC_PAGE",
    nextIndex: number,
    position: GraphPosition,
  ): LinkGraphNode {
    if (kind === "DOC_PAGE") {
      return {
        id: `design-note:${nextIndex}`,
        type: "DOC_PAGE",
        title: `说明${nextIndex}`,
        inputs: [],
        outputs: [],
        doc: "请填写业务说明",
        confidence: "VERIFIED",
        binding: "DESIGN_ONLY",
        provenance: "USER_DRAFT",
        position,
        metadata: {
          "linkGraph.manual": "true",
          "ui.x": String(position.x),
          "ui.y": String(position.y),
        },
      };
    }
    return {
      id: `design:${nextIndex}`,
      type: "METHOD",
      title: `新方法${nextIndex}`,
      inputs: [],
      outputs: [],
      confidence: "VERIFIED",
      binding: "DESIGN_ONLY",
      provenance: "USER_DRAFT",
      position,
      metadata: {
        "linkGraph.manual": "true",
        "ui.x": String(position.x),
        "ui.y": String(position.y),
      },
    };
  }

  /**
   * 沿边路由的折线轨迹计算出路径中点坐标，用于在边上插入新节点时给出合理的初始位置。
   * 当路由缺少足够点时返回 null。
   */
  function routeMidpoint(route?: LinkGraphEdge["route"]): GraphPosition | null {
    const points = route?.sections.flatMap((section) => [
      section.startPoint,
      ...(section.bendPoints ?? []),
      section.endPoint,
    ]) ?? [];
    if (points.length < 2) {
      return null;
    }
    const segments = points.slice(1).map((point, index) => {
      const startPoint = points[index]!;
      const endPoint = point;
      return {
        startPoint,
        endPoint,
        length: Math.hypot(endPoint.x - startPoint.x, endPoint.y - startPoint.y),
      };
    });
    const totalLength = segments.reduce((sum, segment) => sum + segment.length, 0);
    if (totalLength <= 0) {
      return points[Math.floor(points.length / 2)] ?? null;
    }
    const midpointOffset = totalLength / 2;
    let traversed = 0;
    for (const segment of segments) {
      if (traversed + segment.length >= midpointOffset) {
        const ratio = (midpointOffset - traversed) / segment.length;
        return {
          x: segment.startPoint.x + (segment.endPoint.x - segment.startPoint.x) * ratio,
          y: segment.startPoint.y + (segment.endPoint.y - segment.startPoint.y) * ratio,
        };
      }
      traversed += segment.length;
    }
    return segments[segments.length - 1]?.endPoint ?? null;
  }

  /**
   * 解析“在边上插入节点”时新节点应处的坐标：优先取边路由的中点；
   * 若无路由则回退到源/目标节点坐标的中点；都不可用时使用兜底排版位置。
   */
  function resolveEdgeInsertPosition(edge: LinkGraphEdge): GraphPosition {
    const routePosition = routeMidpoint(edge.route);
    if (routePosition) {
      return routePosition;
    }
    const sourceNode = args.nodes.find((node) => node.id === edge.source);
    const targetNode = args.nodes.find((node) => node.id === edge.target);
    const sourcePosition = sourceNode ? args.resolveNodePosition(sourceNode) : null;
    const targetPosition = targetNode ? args.resolveNodePosition(targetNode) : null;
    if (sourcePosition && targetPosition) {
      return {
        x: (sourcePosition.x + targetPosition.x) / 2,
        y: (sourcePosition.y + targetPosition.y) / 2,
      };
    }
    return args.fallbackDesignPosition(args.nodes.length);
  }

  /**
   * 处理新增节点事件：通过权限校验后生成草稿节点，调用 syncGraph 写入画布并选中，
   * 同时将其设为当前详情节点，便于用户立即编辑。
   */
  function handleAddNode(kind: "METHOD" | "DOC_PAGE", position?: GraphPosition) {
    if (!canRunNodeCommand("", "ADD_NODE")) {
      return;
    }
    startTransition(() => {
      const nextPosition = position ?? args.fallbackDesignPosition(args.nodes.length);
      const nextIndex = args.nextManualNodeIdRef.current++;
      const nextNode = buildManualNode(kind, nextIndex, nextPosition);
      args.syncGraph([...args.nodes, nextNode], args.edges, nextNode.id);
      args.setDetailNodeId(nextNode.id);
    });
  }

  /**
   * 处理删除单个节点事件：移除节点自身并清理所有与之相连的边，若被删的是当前选中节点则清空选中。
   */
  function handleDeleteNode(nodeId: string) {
    if (!canRunNodeCommand(nodeId, "DELETE_NODE")) {
      return;
    }
    startTransition(() => {
      const nextNodes = args.nodes.filter((node) => node.id !== nodeId);
      const nextEdges = args.edges.filter((edge) => edge.source !== nodeId && edge.target !== nodeId);
      args.syncGraph(nextNodes, nextEdges, args.selectedNodeId === nodeId ? null : args.selectedNodeId);
    });
  }

  /**
   * 处理删除整棵下游子树事件：根据边关系收集子孙节点集合，连同相关边一并删除，
   * 并在选中/详情节点落在被删集合内时清空对应状态。
   */
  function handleDeleteNodeSubtree(nodeId: string) {
    if (!canRunNodeCommand(nodeId, "DELETE_NODE_SUBTREE")) {
      return;
    }
    startTransition(() => {
      const deletedNodeIds = args.collectDownstreamSubtreeNodeIds(nodeId, args.nodes, args.edges);
      if (deletedNodeIds.size === 0) {
        return;
      }
      const nextNodes = args.nodes.filter((node) => !deletedNodeIds.has(node.id));
      const nextEdges = args.edges.filter((edge) => !deletedNodeIds.has(edge.source) && !deletedNodeIds.has(edge.target));
      const nextSelectedNodeId = args.selectedNodeId && deletedNodeIds.has(args.selectedNodeId) ? null : args.selectedNodeId;
      args.syncGraph(nextNodes, nextEdges, nextSelectedNodeId);
    });
  }

  /**
   * 处理节点字段更新事件：保留原有坐标（仅同步其它字段），随后写回画布、刷新选中与详情。
   */
  function handleUpdateNode(nextNode: LinkGraphNode) {
    if (!canRunNodeCommand(nextNode.id, "UPDATE_NODE")) {
      return;
    }
    startTransition(() => {
      const previousNode = args.nodes.find((node) => node.id === nextNode.id);
      const mergedNode = previousNode?.position ? args.syncNodePosition(nextNode, previousNode.position) : nextNode;
      args.syncGraph(
        args.nodes.map((node) => (node.id === mergedNode.id ? mergedNode : node)),
        args.edges,
        mergedNode.id,
      );
      args.setDetailNodeId(mergedNode.id);
    });
  }

  /**
   * 处理两个节点之间创建连线事件：两端均需通过权限校验；若已存在同源/同目标/同端口的重复边则跳过。
   * 边类型依据当前分析模式（流程图为 CONTROL_FLOW，其它为 CALL）。
   */
  function handleCreateEdge(
    sourceId: string,
    targetId: string,
    sourceHandle?: string | null,
    targetHandle?: string | null,
  ) {
    if (!canRunNodeCommand(sourceId, "CONNECT_NODES") || !canRunNodeCommand(targetId, "CONNECT_NODES")) {
      return;
    }
    startTransition(() => {
      const nextEdgeId = `design-link:${sourceId}->${targetId}`;
      if (args.edges.some((edge) =>
        edge.source === sourceId
        && edge.target === targetId
        && (edge.sourceHandle ?? null) === (sourceHandle ?? null)
        && (edge.targetHandle ?? null) === (targetHandle ?? null)
      )) {
        return;
      }
      const nextEdgeType = args.analysisDisplayMode === "FLOWCHART" ? "CONTROL_FLOW" : "CALL";
      args.syncGraph(
        args.nodes,
        [
          ...args.edges,
          {
            id: nextEdgeId,
            type: nextEdgeType,
            source: sourceId,
            target: targetId,
            sourceHandle: sourceHandle ?? null,
            targetHandle: targetHandle ?? null,
            provenance: "USER_DRAFT",
          },
        ],
      );
    });
  }

  /** 处理删除边事件：通过权限校验后从边列表中过滤掉目标边并写回画布。 */
  function handleDeleteEdge(edgeId: string) {
    if (!canRunEdgeCommand(edgeId, "DELETE_EDGE")) {
      return;
    }
    startTransition(() => {
      args.syncGraph(
        args.nodes,
        args.edges.filter((edge) => edge.id !== edgeId),
      );
    });
  }

  /**
   * 处理在边上插入新节点的事件：先做边与节点的双重权限校验，再删除原边并新增节点，
   * 同时用原边的源/目标拼接出“源→新节点”和“新节点→目标”两条新边，保持图谱连通性。
   */
  function handleInsertNodeIntoEdge(edgeId: string, kind: "METHOD" | "DOC_PAGE") {
    if (!canRunEdgeCommand(edgeId, "INSERT_NODE_INTO_EDGE") || !canRunNodeCommand("", "ADD_NODE")) {
      return;
    }
    startTransition(() => {
      const targetEdge = args.edges.find((edge) => edge.id === edgeId);
      if (!targetEdge) {
        return;
      }
      const nextIndex = args.nextManualNodeIdRef.current++;
      const nextNode = buildManualNode(kind, nextIndex, resolveEdgeInsertPosition(targetEdge));
      const nextEdgeType = args.analysisDisplayMode === "FLOWCHART" ? "CONTROL_FLOW" : targetEdge.type;
      const nextEdges = args.edges
        .filter((edge) => edge.id !== edgeId)
        .concat(
          {
            id: `${edgeId}:before`,
            type: nextEdgeType,
            source: targetEdge.source,
            target: nextNode.id,
            label: targetEdge.label,
            metadata: targetEdge.metadata,
            provenance: "USER_DRAFT",
          },
          {
            id: `${edgeId}:after`,
            type: nextEdgeType,
            source: nextNode.id,
            target: targetEdge.target,
            provenance: "USER_DRAFT",
          },
        );
      args.syncGraph([...args.nodes, nextNode], nextEdges, nextNode.id);
      args.setDetailNodeId(nextNode.id);
    });
  }

  /**
   * 处理单个节点拖动事件：在可编辑性校验通过后，更新节点列表、布局状态、草稿文档，
   * 并根据当前分析模式把新坐标同步到对应视图文档；同时打点追踪并对外发布布局变更。
   */
  function handleMoveNode(nodeId: string, position: GraphPosition) {
    const currentNode = args.nodes.find((node) => node.id === nodeId);
    if (!currentNode || !canEditNodeLayout(currentNode, args.analysisDisplayMode, args.projectionIndex)) {
      return;
    }
    startTransition(() => {
      const layoutUpdates = [{ id: nodeId, position }];
      const nextNodes = args.nodes.map((node) => (node.id === nodeId ? args.syncNodePosition(node, position) : node));
      args.setNodes(nextNodes);
      args.setSceneLayoutState(extractLayoutState(nextNodes));
      args.setDraftGraph((current) => current
        ? args.applyLayoutUpdatesToGraphDocument(current, layoutUpdates)
        : current);
      if (args.analysisDisplayMode === "FACT_GRAPH") {
        args.setFactGraphView((current) =>
          args.syncFactGraphViewDocument(
            current,
            { nodes: nextNodes, edges: args.edges },
            current.anchorNodeId ?? args.anchorNodeIdRef.current ?? null,
          ),
        );
      } else if (args.analysisDisplayMode === "FLOWCHART") {
        args.setFlowchartView((current) => args.syncFlowchartViewLayout(current, layoutUpdates));
      } else if (args.analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
        args.setResourceRelationView((current) => args.syncResourceRelationViewLayout(current, layoutUpdates));
      } else if (args.analysisDisplayMode === "ARCHITECTURE_GRAPH") {
        args.setArchitectureGraphView((current) => args.syncArchitectureGraphViewLayout(current, layoutUpdates));
      } else if (args.analysisDisplayMode === "CLASS_DIAGRAM") {
        args.setClassDiagramView((current) => args.syncClassDiagramViewLayout(current, layoutUpdates));
      } else if (args.analysisDisplayMode === "REVIEW_GRAPH") {
        args.setReviewGraphView((current) => args.syncReviewGraphViewLayout(current, layoutUpdates));
      }
      traceLinkGraph("app.layoutPublished", {
        reason: "single-node-drag",
        updateCount: 1,
        nodeIds: [nodeId],
        durationMs: measureDuration(measureStart()),
      });
      publishLayoutChange([
        {
          nodeId,
          x: position.x,
          y: position.y,
        },
      ]);
    });
  }

  /**
   * 处理多节点批量拖动事件：先过滤出当前模式下可编辑的节点，再统一更新坐标、
   * 同步布局状态、草稿文档及对应视图文档，最后对外发布分组拖动的布局变更。
   */
  function handleMoveNodes(updates: Array<{ id: string; position: GraphPosition }>) {
    const editableUpdates = updates.filter((update) => {
      const currentNode = args.nodes.find((node) => node.id === update.id);
      return Boolean(currentNode && canEditNodeLayout(currentNode, args.analysisDisplayMode, args.projectionIndex));
    });
    if (editableUpdates.length === 0) {
      return;
    }
    startTransition(() => {
      const updateMap = new Map(editableUpdates.map((item) => [item.id, item.position]));
      const nextNodes = args.nodes.map((node) => {
        const nextPosition = updateMap.get(node.id);
        return nextPosition ? args.syncNodePosition(node, nextPosition) : node;
      });
      args.setNodes(nextNodes);
      args.setSceneLayoutState(extractLayoutState(nextNodes));
      args.setDraftGraph((current) => current
        ? args.applyLayoutUpdatesToGraphDocument(current, editableUpdates)
        : current);
      if (args.analysisDisplayMode === "FACT_GRAPH") {
        args.setFactGraphView((current) =>
          args.syncFactGraphViewDocument(
            current,
            { nodes: nextNodes, edges: args.edges },
            current.anchorNodeId ?? args.anchorNodeIdRef.current ?? null,
          ),
        );
      } else if (args.analysisDisplayMode === "FLOWCHART") {
        args.setFlowchartView((current) => args.syncFlowchartViewLayout(current, editableUpdates));
      } else if (args.analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
        args.setResourceRelationView((current) => args.syncResourceRelationViewLayout(current, editableUpdates));
      } else if (args.analysisDisplayMode === "ARCHITECTURE_GRAPH") {
        args.setArchitectureGraphView((current) => args.syncArchitectureGraphViewLayout(current, editableUpdates));
      } else if (args.analysisDisplayMode === "CLASS_DIAGRAM") {
        args.setClassDiagramView((current) => args.syncClassDiagramViewLayout(current, editableUpdates));
      } else if (args.analysisDisplayMode === "REVIEW_GRAPH") {
        args.setReviewGraphView((current) => args.syncReviewGraphViewLayout(current, editableUpdates));
      }
      traceLinkGraph("app.layoutPublished", {
        reason: "group-drag",
        updateCount: editableUpdates.length,
        nodeIds: editableUpdates.slice(0, 8).map((update) => update.id),
      });
      publishLayoutChange(
        editableUpdates.map((update) => ({
          nodeId: update.id,
          x: update.position.x,
          y: update.position.y,
        })),
      );
    });
  }

  /**
   * 处理折叠/展开节点子树事件：切换折叠集合后重算被隐藏的子孙节点，
   * 清理选择分组/QA 目标中已被隐藏的节点，并在选中或详情节点被隐藏时进行修正。
   */
  function handleToggleCollapseNode(nodeId: string) {
    startTransition(() => {
      const nextCollapsedNodeIds = args.collapsedNodeIds.includes(nodeId)
        ? args.collapsedNodeIds.filter((item) => item !== nodeId)
        : [...args.collapsedNodeIds, nodeId];
      const nextHiddenNodeIds = args.resolveCollapsedDescendantSummary(args.nodes, args.edges, nextCollapsedNodeIds).hiddenNodeIds;

      args.setCollapsedNodeIds(nextCollapsedNodeIds);
      args.setSelectionGroupNodeIds((current) => current.filter((item) => !nextHiddenNodeIds.has(item)));
      args.setQaTargetNodeIds((current) => current.filter((item) => !nextHiddenNodeIds.has(item)));
      if (args.selectedNodeId && nextHiddenNodeIds.has(args.selectedNodeId)) {
        args.setSelectedNodeId(nodeId);
      }
      if (args.detailNodeId && nextHiddenNodeIds.has(args.detailNodeId)) {
        args.setDetailNodeId(null);
      }
    });
  }

  /** 处理整理布局事件：强制重新排版当前画布，并向用户反馈操作完成。 */
  function handleFormatLayout() {
    startTransition(() => {
      args.syncGraph(args.nodes, args.edges, args.selectedNodeId, { forceRelayout: true });
      args.setOperationFeedback({
        level: "INFO",
        message: "已重新整理当前画布布局。",
      });
    });
  }

  /** 暴露给画布组件的事件处理器集合，覆盖节点/边/布局相关的全部交互入口。 */
  return {
    handleAddNode,
    handleDeleteNode,
    handleDeleteNodeSubtree,
    handleUpdateNode,
    handleCreateEdge,
    handleDeleteEdge,
    handleInsertNodeIntoEdge,
    handleMoveNode,
    handleMoveNodes,
    handleToggleCollapseNode,
    handleFormatLayout,
  };
}
