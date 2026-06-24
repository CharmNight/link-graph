import { useLayoutEffect, useRef } from "react";
import { useUpdateNodeInternals } from "@xyflow/react";

/**
 * 在节点内部状态签名变化时，稳定地触发 React Flow 的 updateNodeInternals。
 *
 * React Flow 的 updateNodeInternals 会让节点重新计算连接柄位置。
 * 但如果在每次渲染都调用会导致性能问题与无限循环。
 * 本 Hook 通过签名比较确保只在签名真正变化时才触发。
 *
 * @param nodeId 节点 ID
 * @param internalsSignature 当前节点的内部状态签名（由 reactFlowNodeInternalsSignature 计算）
 */
export function useStableNodeInternalsUpdate(nodeId: string, internalsSignature: string) {
  const updateNodeInternals = useUpdateNodeInternals();
  // 保存上一次签名，用于判断本次是否真的变化
  const previousSignatureRef = useRef<string | null>(null);

  useLayoutEffect(() => {
    const previousSignature = previousSignatureRef.current;
    previousSignatureRef.current = internalsSignature;
    // 首次（null）或签名未变化时跳过；只有真正变化才触发更新
    if (previousSignature === null || previousSignature === internalsSignature) {
      return;
    }
    updateNodeInternals(nodeId);
  }, [internalsSignature, nodeId, updateNodeInternals]);
}
