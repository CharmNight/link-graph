import { canNavigateToSource } from "../sourceNavigation";
import type {
  LinkGraphNode,
  OperationFeedback,
  SourceNavigationState,
} from "../types";
import { requestSourceNavigation } from "../api";
import type { useBridgeCommandController } from "./useBridgeCommandController";

interface UseSourceNavigationControllerArgs {
  nodes: LinkGraphNode[];
  selectNode: (nodeId: string) => void;
  setSourceNavigationState: (nextState: SourceNavigationState) => void;
  setOperationFeedback: (feedback: OperationFeedback | null) => void;
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand"
  >;
}

export function useSourceNavigationController({
  nodes,
  selectNode,
  setSourceNavigationState,
  setOperationFeedback,
  bridgeCommands,
}: UseSourceNavigationControllerArgs) {
  function handleRequestSourceNavigation(nodeId: string) {
    const targetNode = nodes.find((node) => node.id === nodeId) ?? null;
    selectNode(nodeId);

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

    bridgeCommands.runBridgeCommand("源码跳转", () => requestSourceNavigation(nodeId), {
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
