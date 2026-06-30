// 交互探针控制器：在调试模式下自动模拟用户操作（选择、检查、移动、跳转源码），
// 用于度量各操作的端到端耗时，便于发现性能问题。
//
// 仅当 window.__linkGraphInteractionProbe 为 true 时启用，正常用户路径不会触发。
import { useEffect, useRef, type MutableRefObject } from "react";
import {
  measureDuration,
  measureStart,
  summarizeGraph,
  traceLinkGraph,
} from "../debug";
import { sameNodeIdList } from "../graphState";
import { canNavigateToSource } from "../sourceNavigation";
import type {
  GraphPosition,
  LinkGraphEdge,
  LinkGraphNode,
  SourceNavigationState,
} from "../types";

/** 控制器入参。 */
interface UseInteractionProbeControllerArgs {
  /** 当前画布节点列表。 */
  nodes: LinkGraphNode[];
  /** 当前画布边列表。 */
  edges: LinkGraphEdge[];
  /** 节点列表的 ref；用于在定时器中拿到最新值。 */
  nodesRef: MutableRefObject<LinkGraphNode[]>;
  /** 多选节点 ID 集合。 */
  selectionGroupNodeIds: string[];
  /** 当前检查节点 ID。 */
  detailNodeId: string | null;
  /** 当前检查节点对象。 */
  detailNode: LinkGraphNode | null;
  /** 源码导航状态。 */
  sourceNavigationState: SourceNavigationState;
  /** 兜底坐标生成器；用于无 position 的节点。 */
  fallbackDesignPosition: (index: number) => GraphPosition;
  /** 多选变化回调。 */
  handleSelectionGroupChange: (nodeIds: string[]) => void;
  /** 检查节点回调。 */
  handleInspectNode: (nodeId: string) => void;
  /** 移动节点回调。 */
  handleMoveNode: (nodeId: string, position: GraphPosition) => void;
  /** 请求源码跳转回调。 */
  handleRequestSourceNavigation: (nodeId: string) => void;
}

/**
 * 交互探针 Hook。
 *
 * 流程：
 * 1) 在调试模式且画布有节点时启动一次模拟；
 * 2) 按固定时间间隔依次触发：多选 → 检查 → 移动 → 源码跳转；
 * 3) 各操作有"开始时间戳"，等对应状态变更到位后计算耗时并埋点。
 *
 * 通过 effect 监听各类状态变化判断"操作是否完成"，
 * 完成后立即记录耗时并清理 pending 状态。
 */
export function useInteractionProbeController(args: UseInteractionProbeControllerArgs) {
  const {
    nodes,
    edges,
    nodesRef,
    selectionGroupNodeIds,
    detailNodeId,
    detailNode,
    sourceNavigationState,
    fallbackDesignPosition,
    handleSelectionGroupChange,
    handleInspectNode,
    handleMoveNode,
    handleRequestSourceNavigation,
  } = args;

  // pending 操作集合：每种操作记录自己的目标与开始时间
  const interactionProbeRef = useRef<{
    scheduled: boolean;
    selection: { ids: string[]; startedAt: number } | null;
    inspect: { nodeId: string; startedAt: number } | null;
    move: { nodeId: string; position: GraphPosition; startedAt: number } | null;
    source: { nodeId: string; startedAt: number } | null;
  }>({
    scheduled: false,
    selection: null,
    inspect: null,
    move: null,
    source: null,
  });
  // 探针使用的定时器 ID 列表；卸载时统一清理
  const interactionProbeTimerIdsRef = useRef<number[]>([]);

  // 监听源码导航状态：完成时记录耗时
  useEffect(() => {
    const pendingSource = interactionProbeRef.current.source;
    // 不匹配或仍在进行中：忽略
    if (!pendingSource || sourceNavigationState.nodeId !== pendingSource.nodeId) {
      return;
    }
    if (sourceNavigationState.phase === "IDLE" || sourceNavigationState.phase === "RUNNING") {
      return;
    }
    traceLinkGraph("probe.app.sourceNavigation.completed", {
      nodeId: pendingSource.nodeId,
      phase: sourceNavigationState.phase,
      result: sourceNavigationState.result ?? null,
      targetPath: sourceNavigationState.targetPath ?? null,
      errorMessage: sourceNavigationState.errorMessage ?? null,
      durationMs: measureDuration(pendingSource.startedAt),
    });
    interactionProbeRef.current.source = null;
  }, [sourceNavigationState]);

  // 监听多选变化：完成时记录耗时
  useEffect(() => {
    const pendingSelection = interactionProbeRef.current.selection;
    if (!pendingSelection || !sameNodeIdList(selectionGroupNodeIds, pendingSelection.ids)) {
      return;
    }
    traceLinkGraph("probe.app.selection.completed", {
      nodeIds: pendingSelection.ids,
      durationMs: measureDuration(pendingSelection.startedAt),
    });
    interactionProbeRef.current.selection = null;
  }, [selectionGroupNodeIds]);

  // 监听检查节点变化：完成时记录耗时
  useEffect(() => {
    const pendingInspect = interactionProbeRef.current.inspect;
    if (!pendingInspect || detailNodeId !== pendingInspect.nodeId || detailNode?.id !== pendingInspect.nodeId) {
      return;
    }
    traceLinkGraph("probe.app.inspect.completed", {
      nodeId: pendingInspect.nodeId,
      durationMs: measureDuration(pendingInspect.startedAt),
    });
    interactionProbeRef.current.inspect = null;
  }, [detailNode, detailNodeId]);

  // 监听节点位置变化：移动到位时记录耗时
  useEffect(() => {
    const pendingMove = interactionProbeRef.current.move;
    if (!pendingMove) {
      return;
    }
    const movedNode = nodes.find((node) => node.id === pendingMove.nodeId);
    const position = movedNode?.position;
    if (!position) {
      return;
    }
    // 0.5 像素容差，避免浮点比较失败
    if (Math.abs(position.x - pendingMove.position.x) > 0.5 || Math.abs(position.y - pendingMove.position.y) > 0.5) {
      return;
    }
    traceLinkGraph("probe.app.move.completed", {
      nodeId: pendingMove.nodeId,
      position,
      durationMs: measureDuration(pendingMove.startedAt),
    });
    interactionProbeRef.current.move = null;
  }, [nodes]);

  // 卸载时清理所有定时器
  useEffect(() => {
    return () => {
      interactionProbeTimerIdsRef.current.forEach((timerId) => window.clearTimeout(timerId));
      interactionProbeTimerIdsRef.current = [];
    };
  }, []);

  // 启动一次模拟（仅在调试模式 + 有节点 + 未启动过的情况下）
  useEffect(() => {
    if (typeof window === "undefined" || window.__linkGraphInteractionProbe !== true) {
      return;
    }
    if (interactionProbeRef.current.scheduled || nodes.length === 0) {
      return;
    }
    // 选择目标节点：优先选可跳转源码的节点
    const targetNode = nodes.find((node) => canNavigateToSource(node)) ?? nodes[0];
    if (!targetNode) {
      return;
    }
    interactionProbeRef.current.scheduled = true;
    traceLinkGraph("probe.app.start", {
      graph: summarizeGraph({ nodes, edges }),
      targetNodeId: targetNode.id,
    });

    // 清理之前的定时器，开始新一轮模拟
    interactionProbeTimerIdsRef.current.forEach((timerId) => window.clearTimeout(timerId));
    interactionProbeTimerIdsRef.current = [];
    /** 注册一个定时器并记录其 ID，便于后续清理。 */
    const registerProbeTimer = (callback: () => void, delayMs: number) => {
      const timerId = window.setTimeout(callback, delayMs);
      interactionProbeTimerIdsRef.current.push(timerId);
    };

    // 第一步：多选前几个节点（>1 时才模拟）
    const selectionIds = nodes.slice(0, Math.min(3, nodes.length)).map((node) => node.id);
    if (selectionIds.length > 1) {
      interactionProbeRef.current.selection = {
        ids: selectionIds,
        startedAt: measureStart(),
      };
      registerProbeTimer(() => handleSelectionGroupChange(selectionIds), 40);
    }

    // 第二步：检查目标节点
    registerProbeTimer(() => {
      interactionProbeRef.current.inspect = {
        nodeId: targetNode.id,
        startedAt: measureStart(),
      };
      handleInspectNode(targetNode.id);
    }, 120);

    // 第三步：移动目标节点（小幅偏移）
    registerProbeTimer(() => {
      const latestNode = nodesRef.current.find((node) => node.id === targetNode.id) ?? targetNode;
      const basePosition = latestNode.position ?? fallbackDesignPosition(0);
      const nextPosition = {
        x: basePosition.x + 24,
        y: basePosition.y + 12,
      };
      interactionProbeRef.current.move = {
        nodeId: targetNode.id,
        position: nextPosition,
        startedAt: measureStart(),
      };
      handleMoveNode(targetNode.id, nextPosition);
    }, 220);

    // 第四步：源码跳转（仅当节点可跳转）
    if (canNavigateToSource(targetNode)) {
      registerProbeTimer(() => {
        interactionProbeRef.current.source = {
          nodeId: targetNode.id,
          startedAt: measureStart(),
        };
        traceLinkGraph("probe.app.sourceNavigation.start", {
          nodeId: targetNode.id,
        });
        handleRequestSourceNavigation(targetNode.id);
      }, 340);
    }
  }, [
    edges,
    fallbackDesignPosition,
    handleInspectNode,
    handleMoveNode,
    handleRequestSourceNavigation,
    handleSelectionGroupChange,
    nodes,
    nodesRef,
  ]);
}
