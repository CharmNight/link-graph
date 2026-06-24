import { startTransition, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { publishNodeSelected } from "../api";
import { sameNodeIdList } from "../graphState";
import type { GraphFocusRequest } from "../types";
import type { useBridgeCommandController } from "./useBridgeCommandController";

/** useNodeSelectionController 的入参。 */
interface UseNodeSelectionControllerArgs {
  /** 桥接命令控制器（仅用到 runBridgeCommand）。 */
  bridgeCommands: Pick<ReturnType<typeof useBridgeCommandController>, "runBridgeCommand">;
  /** 设置当前选中节点 ID。 */
  setSelectedNodeId: Dispatch<SetStateAction<string | null>>;
  /** 设置当前检查节点 ID。 */
  setDetailNodeId: Dispatch<SetStateAction<string | null>>;
  /** 设置多选节点 ID 列表。 */
  setSelectionGroupNodeIds: Dispatch<SetStateAction<string[]>>;
}

/**
 * 节点选择控制器。
 *
 * 集中处理"选中节点 / 检查节点 / 多选"三类交互的状态更新与桥接同步。
 * 使用 React 的 startTransition 让这些状态更新作为低优先级过渡，
 * 避免阻塞用户输入。
 *
 * 提供视口聚焦请求（带 nonce）用于让画布镜头聚焦到指定节点。
 */
export function useNodeSelectionController({
  bridgeCommands,
  setSelectedNodeId,
  setDetailNodeId,
  setSelectionGroupNodeIds,
}: UseNodeSelectionControllerArgs) {
  /** 视口聚焦请求；nonce 让多次聚焦同一节点也能区分。 */
  const [focusNodeRequest, setFocusNodeRequest] = useState<GraphFocusRequest | null>(null);
  /** 下一个 nonce；单调递增。 */
  const nextFocusRequestNonceRef = useRef(0);

  /**
   * 把选中节点同步到桥接（让后端知道前端选中了哪个节点）。
   * 失败时不弹错误对话框（announceFailure=false），只给出 WARNING 反馈。
   */
  function syncSelectedNodeToBridge(nodeId: string) {
    bridgeCommands.runBridgeCommand("节点选中同步", () => publishNodeSelected(nodeId), {
      announceFailure: false,
      failureFeedbackLevel: "WARNING",
      failureMessage: "当前节点选择未同步到 IDE。",
    });
  }

  /**
   * 处理"选中节点"动作。
   * 空字符串视为取消选中。多选列表会被清空（单选与多选互斥）。
   */
  function handleSelectNode(nodeId: string) {
    startTransition(() => {
      const nextNodeId = nodeId || null;
      setSelectedNodeId((current) => (current === nextNodeId ? current : nextNodeId));
      setSelectionGroupNodeIds((current) => (current.length === 0 ? current : []));
      if (nextNodeId) {
        syncSelectedNodeToBridge(nextNodeId);
      }
    });
  }

  /**
   * 处理"检查节点"动作（选中并打开属性抽屉）。
   * 同时更新选中、详情与多选；并同步到桥接。
   */
  function handleInspectNode(nodeId: string) {
    startTransition(() => {
      setSelectedNodeId((current) => (current === nodeId ? current : nodeId));
      setDetailNodeId((current) => (current === nodeId ? current : nodeId));
      setSelectionGroupNodeIds((current) => (current.length === 0 ? current : []));
      syncSelectedNodeToBridge(nodeId);
    });
  }

  /**
   * 处理多选变化。
   * 去重后：多于 1 个时存为多选；只有 1 个时退化为单选。
   */
  function handleSelectionGroupChange(nodeIds: string[]) {
    startTransition(() => {
      const uniqueNodeIds = Array.from(new Set(nodeIds));
      const nextGroupNodeIds = uniqueNodeIds.length > 1 ? uniqueNodeIds : [];
      setSelectionGroupNodeIds((current) =>
        sameNodeIdList(current, nextGroupNodeIds) ? current : nextGroupNodeIds
      );
      // 单个节点的情况：同时更新单选选中
      if (uniqueNodeIds.length === 1) {
        const nextNodeId = uniqueNodeIds[0];
        setSelectedNodeId((current) => (current === nextNodeId ? current : nextNodeId));
      }
    });
  }

  /**
   * 请求视口聚焦到指定节点。
   * nonce 递增保证多次请求都被画布识别为新的请求。
   */
  function requestViewportFocus(nodeId: string) {
    nextFocusRequestNonceRef.current += 1;
    setFocusNodeRequest({
      nodeId,
      nonce: nextFocusRequestNonceRef.current,
    });
  }

  /**
   * 选中讲解目标节点。
   * 与 handleSelectNode 类似，但可选择是否同时请求视口聚焦。
   */
  function selectExplanationTargetNode(nodeId: string, options?: { focusViewport?: boolean }) {
    setSelectedNodeId((current) => (current === nodeId ? current : nodeId));
    setSelectionGroupNodeIds((current) => (current.length === 0 ? current : []));
    if (options?.focusViewport) {
      requestViewportFocus(nodeId);
    }
    syncSelectedNodeToBridge(nodeId);
  }

  return {
    focusNodeRequest,
    handleSelectNode,
    handleInspectNode,
    handleSelectionGroupChange,
    requestViewportFocus,
    selectExplanationTargetNode,
  };
}
