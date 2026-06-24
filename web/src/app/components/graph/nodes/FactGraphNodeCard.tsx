import { memo } from "react";
import { nodeTypeLabel } from "../../../labels";
import type { DraftCompareStatus, LinkGraphNode } from "../../../types";
import { IssueBadge } from "../../IssueBadge";
import {
  factNodeDocText,
  hierarchyDirection,
  hierarchyDirectionLabel,
  hierarchyLabel,
  isDecisionFlowScope,
  isFlowActionNode,
  nodeSourceBadge,
  nodeTooltip,
  overflowPresentation,
  ownerPreview,
  signaturePreview,
} from "./nodePresentation";
import { NodeCardBase } from "./NodeCardBase";

/** FactGraphNodeCard 组件的入参。 */
interface FactGraphNodeCardProps {
  /** 待渲染的图节点。 */
  node: LinkGraphNode;
  /** 是否被选中。 */
  selected: boolean;
  /** 是否已折叠。 */
  collapsed: boolean;
  /** 折叠的子节点数；用于展示"已折叠 N 个节点"。 */
  collapsedCount?: number;
  /** 是否处于讲解聚焦。 */
  explanationFocused?: boolean;
  /** 是否被草稿改动影响。 */
  draftChanged?: boolean;
  /** 草稿比对状态。 */
  draftCompareStatus?: DraftCompareStatus;
  /** 尺寸测量回调。 */
  onMeasure?: (size: { width: number; height: number }) => void;
  /** 展开溢出节点的回调。 */
  onExpandOverflow?: () => void;
}

/**
 * 事实图节点卡片。
 *
 * 这是事实图视图中最复杂的节点卡片，需要处理多种情况：
 * - 普通节点：展示文档摘要、所有者、签名、层级方向（上游/当前/下游）；
 * - 折叠节点：展示"已折叠下游 N 个节点"提示；
 * - 溢出节点：展示省略摘要 + 展开按钮；
 * - 不确定节点：展示 certainty 徽章。
 *
 * 基于 [NodeCardBase] 渲染外壳，slot 内容由本组件填充。
 * 使用 memo 包装避免不必要重渲染。
 */
export const FactGraphNodeCard = memo(function FactGraphNodeCard({
  node,
  selected,
  collapsed,
  collapsedCount,
  explanationFocused = false,
  draftChanged = false,
  draftCompareStatus,
  onMeasure,
  onExpandOverflow,
}: FactGraphNodeCardProps) {
  // 检查是否为溢出节点（投影裁剪产生的摘要节点）
  const overflow = overflowPresentation(node);
  // 来源徽章：溢出节点不显示来源
  const sourceBadge = overflow?.expandable ? null : nodeSourceBadge(node);
  // 元数据行：溢出 / 折叠 / 层级标签 三选一
  const metaText = overflow
    ? overflow.metaLine
    : collapsed
      ? `已折叠下游${collapsedCount && collapsedCount > 0 ? ` ${collapsedCount} 个节点` : "子树"}，右键可重新展开`
      : hierarchyLabel(node);
  const directionText = overflow?.expandable ? null : hierarchyDirectionLabel(node);
  // 不确定节点额外展示 certainty
  const certainty = !overflow && node.type === "UNCERTAIN_LINK" && node.certainty !== "PROVEN" ? node.certainty : undefined;
  const directionClassName = hierarchyDirection(node)?.toLowerCase() ?? "unknown";
  const signatureText = overflow?.signatureLine ?? signaturePreview(node) ?? nodeTypeLabel(node.type);
  const ownerText = overflow?.ownerLine ?? ownerPreview(node);

  return (
    <NodeCardBase
      node={node}
      selected={selected}
      explanationFocused={explanationFocused}
      draftChanged={draftChanged}
      onMeasure={onMeasure}
      measureDeps={[node, collapsed, collapsedCount, onMeasure]}
      variantClassName={[
        node.type === "FLOW_SCOPE" ? "is-flow-scope" : "",
        isFlowActionNode(node) ? "is-flow-action" : "",
        isDecisionFlowScope(node) ? "is-flow-decision" : "",
        `direction-${directionClassName}`,
      ]}
    >
      <div className="flow-node-head">
        <span className="flow-node-doc" title={node.doc ?? factNodeDocText(node)}>
          {factNodeDocText(node)}
        </span>
        <div className="flow-node-tags">
          {sourceBadge ? (
            <span className="badge source-tag-badge" title={sourceBadge.title}>
              {sourceBadge.text}
            </span>
          ) : null}
          {directionText ? <span className="badge hierarchy-badge">{directionText}</span> : null}
          <IssueBadge certainty={certainty} diffStatus={node.diffStatus} draftCompareStatus={draftCompareStatus} />
        </div>
      </div>
      <strong className="flow-node-owner" title={ownerText}>
        {ownerText}
      </strong>
      <span className="flow-node-signature" title={nodeTooltip(node)}>
        {signatureText}
      </span>
      {metaText ? <span className={`flow-node-meta ${collapsed ? "is-warning" : ""}`}>{metaText}</span> : null}
      {/* 可展开的溢出节点：展示展开按钮 */}
      {overflow?.expandable && overflow.expandActionLabel ? (
        <button
          type="button"
          className="flow-node-expand-button"
          onClick={(event) => {
            event.preventDefault();
            event.stopPropagation();
            onExpandOverflow?.();
          }}
          onDoubleClick={(event) => {
            event.preventDefault();
            event.stopPropagation();
          }}
          onContextMenu={(event) => {
            event.preventDefault();
            event.stopPropagation();
          }}
        >
          {overflow.expandActionLabel}
        </button>
      ) : null}
    </NodeCardBase>
  );
});
