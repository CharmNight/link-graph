import { memo } from "react";
import { nodeTypeLabel } from "../../../labels";
import { IssueBadge } from "../../IssueBadge";
import type { DraftCompareStatus, LinkGraphNode } from "../../../types";
import { nodeTooltip, resourceLane, resourceLaneLabel, signaturePreview } from "./nodePresentation";
import { NodeCardBase } from "./NodeCardBase";

/** ResourceRelationNodeCard 组件的入参。 */
interface ResourceRelationNodeCardProps {
  /** 待渲染的图节点。 */
  node: LinkGraphNode;
  /** 是否被选中。 */
  selected: boolean;
  /** 是否处于讲解聚焦。 */
  explanationFocused?: boolean;
  /** 是否被草稿改动影响。 */
  draftChanged?: boolean;
  /** 草稿比对状态。 */
  draftCompareStatus?: DraftCompareStatus;
  /** 尺寸测量回调。 */
  onMeasure?: (size: { width: number; height: number }) => void;
}

/**
 * 资源关系图节点卡片。
 *
 * 基于 [NodeCardBase] 渲染：
 * - 头部：泳道标签（代码主体 / 外部接口 / 数据资源 等）+ IssueBadge；
 * - 标题：节点 title；
 * - 签名预览：方法签名 / 资源标识等。
 *
 * 泳道信息来自节点元数据 resource.lane，决定卡片的 lane-xxx class。
 * 使用 memo 包装避免不必要重渲染。
 */
export const ResourceRelationNodeCard = memo(function ResourceRelationNodeCard({
  node,
  selected,
  explanationFocused = false,
  draftChanged = false,
  draftCompareStatus,
  onMeasure,
}: ResourceRelationNodeCardProps) {
  const lane = resourceLane(node);

  return (
    <NodeCardBase
      node={node}
      selected={selected}
      explanationFocused={explanationFocused}
      draftChanged={draftChanged}
      onMeasure={onMeasure}
      measureDeps={[node, onMeasure]}
      variantClassName={["resource-node-card", `lane-${lane.toLowerCase()}`]}
    >
      <div className="flow-node-head">
        <span className="flow-node-doc">{resourceLaneLabel(lane)}</span>
        <div className="flow-node-tags">
          <IssueBadge certainty={undefined} diffStatus={node.diffStatus} draftCompareStatus={draftCompareStatus} />
        </div>
      </div>
      <strong className="flow-node-owner" title={node.title}>
        {node.title}
      </strong>
      <span className="flow-node-signature" title={nodeTooltip(node)}>
        {signaturePreview(node) ?? nodeTypeLabel(node.type)}
      </span>
    </NodeCardBase>
  );
});
