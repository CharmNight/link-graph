import { useRef } from "react";
import { nextManualNodeSequence } from "../manualNodeIds";

/**
 * 人工节点 ID 控制器。
 *
 * 维护一个单调递增的"下一个可用序号"ref，让多个新建节点操作都能拿到唯一 ID。
 * 当外部图变化时，通过 [syncManualNodeIdCounters] 把当前序号与外部图的实际最大序号
 * 取最大值同步，避免新节点 ID 冲突。
 *
 * @param initialNodes 初始节点集合；用于计算初始序号
 * @return ref 与同步函数
 */
export function useManualNodeIdController(initialNodes: Array<{ id: string }>) {
  // 初始序号：基于初始节点集合推断
  const nextManualNodeIdRef = useRef(nextManualNodeSequence(initialNodes));

  /**
   * 与新节点集合同步序号。
   * 取当前序号与外部图实际序号的最大值，避免回退。
   */
  function syncManualNodeIdCounters(nextNodes: Array<{ id: string }>) {
    nextManualNodeIdRef.current = Math.max(
      nextManualNodeIdRef.current,
      nextManualNodeSequence(nextNodes),
    );
  }

  return {
    nextManualNodeIdRef,
    syncManualNodeIdCounters,
  };
}
