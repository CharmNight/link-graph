import { canNavigateToSource } from "../sourceNavigation";
import type {
  LinkGraphNode,
  OperationFeedback,
  SourceNavigationState,
} from "../types";
import { requestSourceNavigation } from "../api";
import type { useBridgeCommandController } from "./useBridgeCommandController";

/** useSourceNavigationController 的入参。 */
interface UseSourceNavigationControllerArgs {
  /** 当前画布节点列表；用于查找目标节点。 */
  nodes: LinkGraphNode[];
  /** 选中节点的回调。 */
  selectNode: (nodeId: string) => void;
  /** 设置源码导航状态。 */
  setSourceNavigationState: (nextState: SourceNavigationState) => void;
  /** 设置操作反馈。 */
  setOperationFeedback: (feedback: OperationFeedback | null) => void;
  /** 桥接命令控制器（仅用到 runBridgeCommand）。 */
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand"
  >;
}

/**
 * 源码跳转控制器。
 *
 * 处理"请求跳转到节点源码"的完整流程：
 * 1) 找到节点；不存在或不可跳转时给出失败反馈；
 * 2) 通过桥接派发跳转命令；成功时状态进入 RUNNING，失败时进入 FAILED；
 * 3) 给出 INFO 反馈"正在定位源码"。
 */
export function useSourceNavigationController({
  nodes,
  selectNode,
  setSourceNavigationState,
  setOperationFeedback,
  bridgeCommands,
}: UseSourceNavigationControllerArgs) {
  /**
   * 请求跳转到指定节点的源码位置。
   *
   * @param nodeId 目标节点 ID
   */
  function handleRequestSourceNavigation(nodeId: string) {
    const targetNode = nodes.find((node) => node.id === nodeId) ?? null;
    // 选中节点：让画布与跳转保持视觉一致
    selectNode(nodeId);

    // 节点不存在或不可跳转：直接给失败反馈，不发起桥接请求
    if (!targetNode || !canNavigateToSource(targetNode)) {
      setSourceNavigationState({
        nodeId,
        phase: "FAILED",
        result: null,
        targetPath: null,
        line: null,
        column: null,
        errorMessage: `节点 ${targetNode?.title ?? nodeId} 暂无可跳转的源码位置。`,
      });
      setOperationFeedback({
        level: "WARNING",
        message: `节点 ${targetNode?.title ?? nodeId} 暂无可跳转的源码位置。`,
      });
      return;
    }

    // 通过桥接派发跳转命令
    bridgeCommands.runBridgeCommand("源码跳转", () => requestSourceNavigation(nodeId), {
      // 桥接接受：状态进入 RUNNING（实际跳转完成后由后端推送状态更新）
      onAccepted: () => {
        setSourceNavigationState({
          nodeId,
          phase: "RUNNING",
          result: null,
          targetPath: null,
          line: null,
          column: null,
          errorMessage: null,
        });
      },
      // 桥接拒绝：状态进入 FAILED 并提示用户
      onRejected: () => {
        setSourceNavigationState({
          nodeId,
          phase: "FAILED",
          result: null,
          targetPath: null,
          line: null,
          column: null,
          errorMessage: "IDE bridge 尚未就绪，本次请求没有发出。",
        });
      },
      // 成功反馈：让用户知道"正在跳转"
      successFeedback: {
        level: "INFO",
        message: `正在定位源码：${targetNode.title}`,
      },
    });
  }

  return {
    handleRequestSourceNavigation,
  };
}
