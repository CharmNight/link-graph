import { startTransition, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { publishNodeSelected } from "../api";
import { sameNodeIdList } from "../graphState";
import type { GraphFocusRequest } from "../types";
import type { useBridgeCommandController } from "./useBridgeCommandController";

interface UseNodeSelectionControllerArgs {
  bridgeCommands: Pick<ReturnType<typeof useBridgeCommandController>, "runBridgeCommand">;
  setSelectedNodeId: Dispatch<SetStateAction<string | null>>;
  setDetailNodeId: Dispatch<SetStateAction<string | null>>;
  setSelectionGroupNodeIds: Dispatch<SetStateAction<string[]>>;
}

export function useNodeSelectionController({
  bridgeCommands,
  setSelectedNodeId,
  setDetailNodeId,
  setSelectionGroupNodeIds,
}: UseNodeSelectionControllerArgs) {
  const [focusNodeRequest, setFocusNodeRequest] = useState<GraphFocusRequest | null>(null);
  const nextFocusRequestNonceRef = useRef(0);

  function syncSelectedNodeToBridge(nodeId: string) {
    bridgeCommands.runBridgeCommand("节点选中同步", () => publishNodeSelected(nodeId), {
      announceFailure: false,
      failureFeedbackLevel: "WARNING",
      failureMessage: "当前节点选择未同步到 IDE。",
    });
  }

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

  function handleInspectNode(nodeId: string) {
    startTransition(() => {
      setSelectedNodeId((current) => (current === nodeId ? current : nodeId));
      setDetailNodeId((current) => (current === nodeId ? current : nodeId));
      setSelectionGroupNodeIds((current) => (current.length === 0 ? current : []));
      syncSelectedNodeToBridge(nodeId);
    });
  }

  function handleSelectionGroupChange(nodeIds: string[]) {
    startTransition(() => {
      const uniqueNodeIds = Array.from(new Set(nodeIds));
      const nextGroupNodeIds = uniqueNodeIds.length > 1 ? uniqueNodeIds : [];
      setSelectionGroupNodeIds((current) =>
        sameNodeIdList(current, nextGroupNodeIds) ? current : nextGroupNodeIds
      );
      if (uniqueNodeIds.length === 1) {
        const nextNodeId = uniqueNodeIds[0];
        setSelectedNodeId((current) => (current === nextNodeId ? current : nextNodeId));
      }
    });
  }

  function requestViewportFocus(nodeId: string) {
    nextFocusRequestNonceRef.current += 1;
    setFocusNodeRequest({
      nodeId,
      nonce: nextFocusRequestNonceRef.current,
    });
  }

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
