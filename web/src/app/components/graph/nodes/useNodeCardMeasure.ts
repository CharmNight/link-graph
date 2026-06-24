// 节点卡片尺寸测量 Hook。
// 所有节点卡片（Fact/Flowchart/Resource/ClassDiagram/Architecture）共用此 Hook，
// 避免每个卡片各自内联 useLayoutEffect + offsetWidth/Height 测量逻辑。
import { useLayoutEffect, useRef } from "react";
import { measureNodeContentBox } from "./measureNodeContentBox";

/** 节点测量结果。 */
export interface NodeMeasure {
  /** 测量宽度。 */
  width: number;
  /** 测量高度。 */
  height: number;
}

/**
 * 节点卡片尺寸测量 Hook。
 *
 * 每个节点卡片之前都内联了相同的 useLayoutEffect 测量块。
 * 本 Hook 把这个约定集中到一处：
 * - 返回 rootRef，挂在卡片的根 div 上；
 * - 在 deps 变化时触发 re-measure；
 * - 通过 [measureNodeContentBox] 做多级回退测量（offsetWidth → clientWidth → boundingRect）。
 *
 * @param onMeasure 尺寸变化时的回调
 * @param deps 重新测量的依赖数组（传影响卡片渲染尺寸的值，如 node 对象、collapsed 标志等）
 * @return root ref（挂在根 div 上）
 */
export function useNodeCardMeasure(
  onMeasure: ((size: NodeMeasure) => void) | undefined,
  deps: ReadonlyArray<unknown>,
) {
  const rootRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    const size = measureNodeContentBox(rootRef.current);
    if (size) {
      onMeasure?.(size);
    }
    // deps 由调用方控制——此处故意不传标准 deps
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return rootRef;
}
