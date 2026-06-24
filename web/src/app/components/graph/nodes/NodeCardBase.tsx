import type { ReactNode } from "react";
import type { DraftCompareStatus, LinkGraphNode } from "../../../types";
import { GraphNodeStateBadges } from "./GraphNodeStateBadges";
import { useNodeCardMeasure, type NodeMeasure } from "./useNodeCardMeasure";

/** NodeCardBase 组件的入参。 */
export interface NodeCardBaseProps {
  /** 待渲染的图节点。 */
  node: LinkGraphNode;
  /** 是否被选中。 */
  selected: boolean;
  /** 是否处于讲解聚焦。 */
  explanationFocused?: boolean;
  /** 是否被草稿改动影响。 */
  draftChanged?: boolean;
  /** 草稿比对状态；传递给共享的状态徽章条。 */
  draftCompareStatus?: DraftCompareStatus;
  /** 尺寸测量回调。 */
  onMeasure?: (size: NodeMeasure) => void;
  /** 影响卡片渲染尺寸的依赖数组；变化时触发重新测量。 */
  measureDeps: ReadonlyArray<unknown>;
  /**
   * 追加在基础 shell class 后面的变体 class。
   * 支持字符串或数组，例如 "flowchart-node-card kind-process"
   * 或 ["resource-node-card", "lane-upstream"]。
   */
  variantClassName?: string | ReadonlyArray<string>;
  /** 覆盖默认的 shell class（"flow-node-card"）。 */
  baseClassName?: string;
  /** 卡片 slot 内容（标题 / 签名 / 元信息等）。 */
  children: ReactNode;
}

/**
 * 所有图节点卡片的共享骨架组件。
 *
 * 集中了之前五种卡片各自重复的约定：
 *   - root `<div ref>` 由 [useNodeCardMeasure] 测量尺寸；
 *   - `data-node-id` 属性用于点击命中测试；
 *   - className 组合逻辑：`[base, ...variant, is-selected]`；
 *   - [GraphNodeStateBadges] 状态徽章条作为第一个子元素。
 *
 * 卡片特定的 slot 内容通过 children 传入——不同卡片的 slot 结构差异太大
 * （类图有 UML 分区；事实图有展开按钮等），无法用固定 slot API 表达，
 * 所以本组件故意只做结构化骨架，不做内容分发。
 */
export function NodeCardBase({
  node,
  selected,
  explanationFocused = false,
  draftChanged = false,
  onMeasure,
  measureDeps,
  variantClassName,
  baseClassName = "flow-node-card",
  children,
}: NodeCardBaseProps) {
  // 测量用 ref：useNodeCardMeasure 内部注册 useLayoutEffect 在尺寸变化时回调
  const rootRef = useNodeCardMeasure(onMeasure, measureDeps);
  // variantClassName 统一为数组形式
  const variantClasses = Array.isArray(variantClassName)
    ? variantClassName.filter(Boolean)
    : variantClassName
      ? [variantClassName]
      : [];
  // 拼接最终 className：[base, ...variant, is-selected]
  const className = [baseClassName, ...variantClasses, selected ? "is-selected" : ""]
    .join(" ")
    .trim();

  return (
    <div ref={rootRef} className={className} data-node-id={node.id}>
      {/* 状态徽章条始终在第一个 */}
      <GraphNodeStateBadges
        selected={selected}
        explanationFocused={explanationFocused}
        draftChanged={draftChanged}
      />
      {children}
    </div>
  );
}
